package config

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	P "path"
	"regexp"
	"sort"
	"strings"

	"github.com/metacubex/mihomo/config"
)

const chainFileName = "chain.json"

var (
	chainPatchProviders   func(*config.RawConfig, string) error
	chainRepairConfigYAML func([]byte) ([]byte, error)
)

var privateCIDRNets = mustParseCIDRs([]string{
	"10.0.0.0/8",
	"172.16.0.0/12",
	"192.168.0.0/16",
	"127.0.0.0/8",
	"169.254.0.0/16",
	"0.0.0.0/32",
	"fc00::/7",
	"fe80::/10",
	"::1/128",
	"::/128",
})

func mustParseCIDRs(rawRanges []string) []*net.IPNet {
	out := make([]*net.IPNet, 0, len(rawRanges))
	for _, raw := range rawRanges {
		if _, network, err := net.ParseCIDR(raw); err == nil && network != nil {
			out = append(out, network)
		}
	}
	return out
}

// ChainFile is written beside config.yaml at load time. It is not part of the subscription.
type ChainFile struct {
	Entry          string `json:"entry"`
	LandingProfile string `json:"landingProfile"`
	Landing        string `json:"landing"`
}

type profileOutline struct {
	Groups    []string `json:"groups"`
	Proxies   []string `json:"proxies"`
	Providers []string `json:"providers"`
	Error     string   `json:"error,omitempty"`
}

// Outline lists selectable groups and proxies in a profile directory.
func Outline(profilePath string) (string, error) {
	raw, err := readRaw(P.Join(profilePath, "config.yaml"))
	if err != nil {
		return "", err
	}
	out := profileOutline{
		Groups:    groupNames(raw),
		Proxies:   proxyNames(raw),
		Providers: providerNames(raw),
	}
	buf, err := json.Marshal(out)
	if err != nil {
		return "", err
	}
	return string(buf), nil
}

func patchChain(cfg *config.RawConfig, profileDir string) error {
	if cfg == nil {
		return fmt.Errorf("链式代理配置为空")
	}
	// Preserve the original no-chain fast path without cloning or normalizing
	// configuration values when there is nothing to patch.
	if _, err := os.Stat(P.Join(profileDir, chainFileName)); err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	// Work on isolated copies of every field chain processing may mutate. A
	// failed validation must not leave a partially rewritten live configuration.
	working := *cfg
	var err error
	if working.Proxy, err = cloneMapSliceStrict(cfg.Proxy); err != nil {
		return fmt.Errorf("复制代理配置失败: %w", err)
	}
	if working.ProxyGroup, err = cloneMapSliceStrict(cfg.ProxyGroup); err != nil {
		return fmt.Errorf("复制代理组配置失败: %w", err)
	}
	if working.ProxyProvider, err = cloneProviderMapStrict(cfg.ProxyProvider); err != nil {
		return fmt.Errorf("复制代理订阅配置失败: %w", err)
	}
	working.Rule = append([]string(nil), cfg.Rule...)
	if err := patchChainInPlace(&working, profileDir); err != nil {
		return err
	}
	cfg.Proxy = working.Proxy
	cfg.ProxyGroup = working.ProxyGroup
	cfg.ProxyProvider = working.ProxyProvider
	cfg.Rule = working.Rule
	return nil
}

func cloneMapSliceStrict(items []map[string]any) ([]map[string]any, error) {
	if items == nil {
		return nil, nil
	}
	out := make([]map[string]any, len(items))
	for i, item := range items {
		cloned, err := cloneMapStrict(item)
		if err != nil {
			return nil, fmt.Errorf("第 %d 项: %w", i, err)
		}
		out[i] = cloned
	}
	return out, nil
}

func cloneProviderMapStrict(items map[string]map[string]any) (map[string]map[string]any, error) {
	if items == nil {
		return nil, nil
	}
	out := make(map[string]map[string]any, len(items))
	for name, item := range items {
		cloned, err := cloneMapStrict(item)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", name, err)
		}
		out[name] = cloned
	}
	return out, nil
}

func cloneMapStrict(item map[string]any) (map[string]any, error) {
	if item == nil {
		return nil, nil
	}
	out := make(map[string]any, len(item))
	for key, value := range item {
		cloned, err := cloneConfigValue(value)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", key, err)
		}
		out[key] = cloned
	}
	return out, nil
}

func cloneConfigValue(value any) (any, error) {
	switch v := value.(type) {
	case map[string]any:
		return cloneMapStrict(v)
	case []any:
		out := make([]any, len(v))
		for i, item := range v {
			cloned, err := cloneConfigValue(item)
			if err != nil {
				return nil, fmt.Errorf("索引 %d: %w", i, err)
			}
			out[i] = cloned
		}
		return out, nil
	case []string:
		if v == nil {
			return []string(nil), nil
		}
		out := make([]string, len(v))
		copy(out, v)
		return out, nil
	case []map[string]any:
		return cloneMapSliceStrict(v)
	default:
		// Preserve scalar types (notably integer values) instead of JSON
		// round-tripping them into different Go representations.
		return value, nil
	}
}

func patchChainInPlace(cfg *config.RawConfig, profileDir string) error {
	buf, err := os.ReadFile(P.Join(profileDir, chainFileName))
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	var spec ChainFile
	if err := json.Unmarshal(buf, &spec); err != nil {
		return fmt.Errorf("链式代理读取失败: %s", err.Error())
	}
	spec.Entry = strings.TrimSpace(spec.Entry)
	spec.Landing = strings.TrimSpace(spec.Landing)
	spec.LandingProfile = strings.TrimSpace(spec.LandingProfile)
	if spec.Entry == "" && spec.Landing == "" {
		return nil
	}
	if spec.Entry == "" || spec.Landing == "" {
		return fmt.Errorf("链式代理要同时选择入口和落地")
	}

	current := P.Base(profileDir)
	landingName := spec.Landing
	if spec.LandingProfile != "" && spec.LandingProfile != current {
		if !isProfileID(spec.LandingProfile) {
			return fmt.Errorf("落地配置标识无效")
		}
		landingProfileDir := P.Join(P.Dir(profileDir), spec.LandingProfile)
		other, err := readRaw(P.Join(landingProfileDir, "config.yaml"))
		if err != nil {
			return fmt.Errorf("落地配置读不出来: %s", err.Error())
		}
		if !hasName(other, spec.Landing) {
			return fmt.Errorf("落地「%s」不在所选配置里", spec.Landing)
		}
		if chainPatchProviders != nil {
			_ = chainPatchProviders(other, landingProfileDir)
		}
		pruneLandingConfig(other, spec.Landing)
		landingName, err = mergeLanding(cfg, other, spec.LandingProfile, spec.Landing)
		if err != nil {
			return fmt.Errorf("落地配置合并失败: %w", err)
		}
	}

	if spec.Entry == landingName {
		return fmt.Errorf("入口和落地不能是同一个")
	}
	if !hasName(cfg, spec.Entry) {
		return fmt.Errorf("入口「%s」不在当前配置里", spec.Entry)
	}
	if !hasName(cfg, landingName) {
		return fmt.Errorf("落地「%s」不在所选配置里", spec.Landing)
	}

	if err := stripDirect(cfg, landingName, map[string]bool{}, nil); err != nil {
		return err
	}
	landingLeaves := map[string]struct{}{}
	landingGroups := map[string]bool{}
	if err := collectLeaves(cfg, landingName, map[string]bool{}, landingLeaves, landingGroups, nil); err != nil {
		return err
	}
	if len(landingLeaves) == 0 {
		return fmt.Errorf("落地「%s」没有可拨号的节点", spec.Landing)
	}

	landingProxyNames := map[string]bool{landingName: true}
	for leaf := range landingLeaves {
		if kind, name, ok := strings.Cut(leaf, ":"); ok && (kind == "proxy" || kind == "provider") {
			landingProxyNames[name] = true
		}
	}

	if err := stripDirect(cfg, spec.Entry, map[string]bool{}, landingProxyNames); err != nil {
		return err
	}

	entryLeaves := map[string]struct{}{}
	entryGroups := map[string]bool{}
	if err := collectLeaves(cfg, spec.Entry, map[string]bool{}, entryLeaves, entryGroups, landingProxyNames); err != nil {
		return err
	}
	if len(entryLeaves) == 0 {
		return fmt.Errorf("入口「%s」没有可拨号的节点", spec.Entry)
	}
	for name := range landingLeaves {
		if _, ok := entryLeaves[name]; ok {
			return fmt.Errorf("入口和落地包含同一个节点「%s」", name)
		}
	}

	// Ensure non-landing include-all groups never absorb landing nodes and
	// trigger recursive URL-test or dialer-proxy self-loops.
	excludeLandingFromIncludeAllGroups(cfg, landingProxyNames, landingGroups)

	for name := range entryLeaves {
		clearDialer(cfg, name)
	}
	for name := range landingLeaves {
		if err := setDialer(cfg, name, spec.Entry); err != nil {
			return err
		}
	}
	rewriteRules(cfg, landingName)
	return nil
}

// pruneLandingConfig trims an imported landing profile down to the minimal
// closure reachable from the selected landing target (including internal
// dialer-proxy hops) so unused proxies, background url-test groups, and
// unrelated providers from a large subscription are not merged into the active
// runtime configuration.
func pruneLandingConfig(src *config.RawConfig, root string) {
	if src == nil || root == "" {
		return
	}
	proxyByName := make(map[string]map[string]any, len(src.Proxy))
	for _, p := range src.Proxy {
		if name := mapString(p, "name"); name != "" {
			proxyByName[name] = p
		}
	}
	groupByName := make(map[string]map[string]any, len(src.ProxyGroup))
	for _, g := range src.ProxyGroup {
		if name := mapString(g, "name"); name != "" {
			groupByName[name] = g
		}
	}

	keepProxy := map[string]bool{}
	keepGroup := map[string]bool{}
	keepProvider := map[string]bool{}
	visited := map[string]bool{}

	var visit func(name string)
	visit = func(name string) {
		name = strings.TrimSpace(name)
		if name == "" || visited[name] || isDirectLike(name) || isRejectLike(name) {
			return
		}
		visited[name] = true
		if p, ok := proxyByName[name]; ok {
			keepProxy[name] = true
			if dialer := mapString(p, "dialer-proxy"); dialer != "" {
				visit(dialer)
			}
		}
		if prov, ok := src.ProxyProvider[name]; ok {
			keepProvider[name] = true
			if dialer := mapString(prov, "dialer-proxy"); dialer != "" {
				visit(dialer)
			}
		}
		if g, ok := groupByName[name]; ok {
			keepGroup[name] = true
			for _, member := range mapStrings(g, "proxies") {
				visit(member)
			}
			for _, provName := range mapStrings(g, "use") {
				visit(provName)
			}
			if mapBool(g, "include-all") || mapBool(g, "include-all-proxies") {
				for pName := range proxyByName {
					visit(pName)
				}
			}
			if mapBool(g, "include-all") || mapBool(g, "include-all-providers") {
				for provName := range src.ProxyProvider {
					visit(provName)
				}
			}
		}
	}

	visit(root)

	if len(src.Proxy) > 0 {
		prunedProxies := make([]map[string]any, 0, len(keepProxy))
		for _, p := range src.Proxy {
			if keepProxy[mapString(p, "name")] {
				prunedProxies = append(prunedProxies, p)
			}
		}
		src.Proxy = prunedProxies
	}
	if len(src.ProxyGroup) > 0 {
		prunedGroups := make([]map[string]any, 0, len(keepGroup))
		for _, g := range src.ProxyGroup {
			if keepGroup[mapString(g, "name")] {
				prunedGroups = append(prunedGroups, g)
			}
		}
		src.ProxyGroup = prunedGroups
	}
	if len(src.ProxyProvider) > 0 {
		prunedProviders := make(map[string]map[string]any, len(keepProvider))
		for name, prov := range src.ProxyProvider {
			if keepProvider[name] {
				prunedProviders[name] = prov
			}
		}
		src.ProxyProvider = prunedProviders
	}
}

func excludeLandingFromIncludeAllGroups(cfg *config.RawConfig, landingNames map[string]bool, landingGroups map[string]bool) {
	if len(landingNames) == 0 {
		return
	}
	escaped := make([]string, 0, len(landingNames)+1)
	escaped = append(escaped, `^chain-landing-`)
	for name := range landingNames {
		if name != "" && !strings.HasPrefix(name, "chain-landing-") {
			escaped = append(escaped, `^`+regexp.QuoteMeta(name)+`$`)
		}
	}
	sort.Strings(escaped)
	pattern := strings.Join(escaped, "|")
	for _, group := range cfg.ProxyGroup {
		gName := mapString(group, "name")
		if landingGroups[gName] {
			continue
		}
		if !mapBool(group, "include-all") && !mapBool(group, "include-all-proxies") {
			continue
		}
		existing := mapString(group, "exclude-filter")
		if existing == "" {
			group["exclude-filter"] = pattern
		} else if !strings.Contains(existing, pattern) {
			group["exclude-filter"] = "(" + existing + ")|(" + pattern + ")"
		}
	}
}

func readRaw(path string) (*config.RawConfig, error) {
	buf, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	raw, err := config.UnmarshalRawConfig(buf)
	if err != nil && strings.Contains(err.Error(), "not found") && chainRepairConfigYAML != nil {
		if fixed, ferr := chainRepairConfigYAML(buf); ferr == nil {
			if next, nerr := config.UnmarshalRawConfig(fixed); nerr == nil {
				return next, nil
			}
		}
	}
	return raw, err
}

func mergeLanding(dst, src *config.RawConfig, profileID, landing string) (string, error) {
	rename := func(name string) string {
		return "chain-landing-" + profileID + "-" + name
	}
	// Validate every imported name before mutating dst, so a collision cannot
	// leave a partially merged configuration or silently shadow an existing item.
	existingNames := map[string]struct{}{}
	for _, item := range dst.Proxy {
		if name := mapString(item, "name"); name != "" {
			existingNames[name] = struct{}{}
		}
	}
	for _, item := range dst.ProxyGroup {
		if name := mapString(item, "name"); name != "" {
			existingNames[name] = struct{}{}
		}
	}
	for name := range dst.ProxyProvider {
		existingNames[name] = struct{}{}
	}
	importedNames := map[string]struct{}{}
	checkName := func(name string) error {
		if name == "" {
			return nil
		}
		newName := rename(name)
		if _, ok := existingNames[newName]; ok {
			return fmt.Errorf("导入名称冲突：%s", newName)
		}
		if _, ok := importedNames[newName]; ok {
			return fmt.Errorf("导入配置内存在重复名称：%s", newName)
		}
		importedNames[newName] = struct{}{}
		return nil
	}
	for _, item := range src.Proxy {
		if err := checkName(mapString(item, "name")); err != nil {
			return "", err
		}
	}
	for _, item := range src.ProxyGroup {
		if err := checkName(mapString(item, "name")); err != nil {
			return "", err
		}
	}
	for name := range src.ProxyProvider {
		if err := checkName(name); err != nil {
			return "", err
		}
	}
	// Only rewrite dialer references that resolve inside the imported profile.
	// References outside this profile must not be accidentally redirected.
	sourceNames := map[string]struct{}{}
	for _, item := range src.Proxy {
		if name := mapString(item, "name"); name != "" {
			sourceNames[name] = struct{}{}
		}
	}
	for _, item := range src.ProxyGroup {
		if name := mapString(item, "name"); name != "" {
			sourceNames[name] = struct{}{}
		}
	}
	for name := range src.ProxyProvider {
		sourceNames[name] = struct{}{}
	}
	renameReference := func(name string) string {
		if _, ok := sourceNames[name]; ok {
			return rename(name)
		}
		return name
	}
	for _, item := range src.Proxy {
		cloned := cloneMap(item)
		if name := mapString(cloned, "name"); name != "" {
			cloned["name"] = rename(name)
		}
		if dialer := mapString(cloned, "dialer-proxy"); dialer != "" {
			cloned["dialer-proxy"] = renameReference(dialer)
		}
		dst.Proxy = append(dst.Proxy, cloned)
	}
	if dst.ProxyProvider == nil {
		dst.ProxyProvider = map[string]map[string]any{}
	}
	for name, item := range src.ProxyProvider {
		cloned := cloneMap(item)
		if dialer := mapString(cloned, "dialer-proxy"); dialer != "" {
			cloned["dialer-proxy"] = renameReference(dialer)
		}
		dst.ProxyProvider[rename(name)] = cloned
	}
	for _, item := range src.ProxyGroup {
		cloned := cloneMap(item)
		if name := mapString(cloned, "name"); name != "" {
			cloned["name"] = rename(name)
		}
		cloned["proxies"] = renameList(mapStrings(cloned, "proxies"), renameReference)
		cloned["use"] = renameList(mapStrings(cloned, "use"), renameReference)
		dst.ProxyGroup = append(dst.ProxyGroup, cloned)
	}
	return rename(landing), nil
}

func rewriteRules(cfg *config.RawConfig, landing string) {
	known := map[string]struct{}{landing: {}}
	for _, name := range proxyNames(cfg) {
		known[name] = struct{}{}
	}
	for _, name := range groupNames(cfg) {
		known[name] = struct{}{}
	}
	for _, builtin := range []string{"DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL"} {
		known[builtin] = struct{}{}
	}
	changed := false
	for i, rule := range cfg.Rule {
		idx, target, ok := ruleTarget(rule)
		if !ok {
			continue
		}
		if _, exists := known[target]; !exists && !isDirectLike(target) && !isRejectLike(target) {
			continue
		}
		if isRejectLike(target) {
			continue
		}
		parts := splitRule(rule)
		kind := ""
		if len(parts) > 0 {
			kind = strings.ToUpper(parts[0])
		}
		// Preserve all explicit non-MATCH DIRECT bypass rules (such as domestic
		// domains, domestic DNS IPs, direct rule-sets, and LAN CIDRs) so domestic
		// traffic and DNS servers are not forced through the 2-hop chain.
		if isDirectLike(target) && (kind != "MATCH" || isBypassRule(rule)) {
			continue
		}
		parts[idx] = landing
		if isIPRuleKind(kind) && !hasNoResolve(parts) {
			parts = append(parts, "no-resolve")
		}
		cfg.Rule[i] = strings.Join(parts, ",")
		changed = true
	}
	if !changed {
		cfg.Rule = append(cfg.Rule, "MATCH,"+landing)
	}
}

func isIPRuleKind(kind string) bool {
	switch kind {
	case "IP-CIDR", "IP-CIDR6", "GEOIP", "IP-ASN":
		return true
	default:
		return false
	}
}

func hasNoResolve(parts []string) bool {
	for _, p := range parts {
		if strings.EqualFold(strings.TrimSpace(p), "no-resolve") {
			return true
		}
	}
	return false
}

func isBypassRule(rule string) bool {
	parts := splitRule(rule)
	if len(parts) < 2 {
		return false
	}
	kind := strings.ToUpper(parts[0])
	payload := strings.ToLower(parts[1])
	switch kind {
	case "GEOIP":
		return payload == "cn" || payload == "private"
	case "GEOSITE":
		return geositeIsChina(payload)
	case "DOMAIN-SUFFIX":
		return payload == "cn" || strings.HasSuffix(payload, ".cn")
	case "IP-CIDR", "IP-CIDR6":
		return isPrivateCIDR(parts[1])
	case "RULE-SET":
		return ruleSetIsBypass(payload)
	default:
		return false
	}
}

// geositeIsChina matches CN-ish geosite codes as whole tokens. Substring
// matching turned unrelated codes such as `ncnc` into "domestic" traffic.
func geositeIsChina(payload string) bool {
	switch payload {
	case "cn", "private", "geolocation-cn":
		return true
	}
	base, suffix, hasSuffix := strings.Cut(payload, "@")
	if !hasSuffix {
		return false
	}
	// `cn@ads` and `category-games@cn` are both China-scoped rule sets.
	return base == "cn" || suffix == "cn"
}

// ruleSetIsBypass matches provider names that are clearly domestic/LAN/direct
// rule sets. Tokens are separated by '-', '_', '@' and '.', so `my-direct-rules`
// matches while `ncnc` does not.
func ruleSetIsBypass(payload string) bool {
	for _, token := range strings.FieldsFunc(payload, func(r rune) bool {
		return r == '-' || r == '_' || r == '@' || r == '.'
	}) {
		switch token {
		case "cn", "private", "lan", "direct":
			return true
		}
	}
	return false
}

func isPrivateCIDR(raw string) bool {
	raw = strings.TrimSpace(raw)
	_, network, err := net.ParseCIDR(raw)
	if err != nil {
		// Keep support for a bare IP address in rule payloads.
		ip := net.ParseIP(raw)
		if ip == nil {
			return false
		}
		if ipv4 := ip.To4(); ipv4 != nil {
			network = &net.IPNet{IP: ipv4, Mask: net.CIDRMask(32, 32)}
		} else {
			network = &net.IPNet{IP: ip, Mask: net.CIDRMask(128, 128)}
		}
	}
	prefix, bits := network.Mask.Size()
	// A broad CIDR must not be classified as private merely because its
	// network address is private (for example, 0.0.0.0/0 or 10.0.0.0/7).
	for _, allowed := range privateCIDRNets {
		allowedPrefix, allowedBits := allowed.Mask.Size()
		if bits == allowedBits && prefix >= allowedPrefix && allowed.Contains(network.IP) {
			return true
		}
	}
	return false
}

func ruleTarget(rule string) (int, string, bool) {
	parts := splitRule(rule)
	if len(parts) < 2 {
		return 0, "", false
	}
	last := parts[len(parts)-1]
	if strings.EqualFold(last, "no-resolve") {
		if len(parts) < 3 {
			return 0, "", false
		}
		return len(parts) - 2, parts[len(parts)-2], true
	}
	return len(parts) - 1, last, true
}

func splitRule(rule string) []string {
	parts := strings.Split(rule, ",")
	for i := range parts {
		parts[i] = strings.TrimSpace(parts[i])
	}
	return parts
}

func collectLeaves(
	cfg *config.RawConfig,
	name string,
	stack map[string]bool,
	out map[string]struct{},
	visitedGroups map[string]bool,
	excludeNames map[string]bool,
) error {
	if len(stack) > 24 {
		return fmt.Errorf("分组嵌套过深：%s", name)
	}
	if stack[name] {
		return fmt.Errorf("分组存在循环：%s", name)
	}
	if isDirectLike(name) || isRejectLike(name) {
		return nil
	}
	if proxy := findProxy(cfg, name); proxy != nil {
		out["proxy:"+name] = struct{}{}
		return nil
	}
	if _, ok := cfg.ProxyProvider[name]; ok {
		out["provider:"+name] = struct{}{}
		return nil
	}
	group := findGroup(cfg, name)
	if group == nil {
		return fmt.Errorf("找不到「%s」", name)
	}
	if visitedGroups != nil {
		visitedGroups[name] = true
	}
	stack[name] = true
	defer delete(stack, name)
	members := append(mapStrings(group, "proxies"), mapStrings(group, "use")...)
	if mapBool(group, "include-all") || mapBool(group, "include-all-proxies") {
		for _, pName := range proxyNames(cfg) {
			if excludeNames != nil && (excludeNames[pName] || strings.HasPrefix(pName, "chain-landing-")) {
				continue
			}
			members = append(members, pName)
		}
	}
	if mapBool(group, "include-all") || mapBool(group, "include-all-providers") {
		for _, provName := range providerNames(cfg) {
			if excludeNames != nil && (excludeNames[provName] || strings.HasPrefix(provName, "chain-landing-")) {
				continue
			}
			members = append(members, provName)
		}
	}
	if len(members) > 2048 {
		return fmt.Errorf("分组「%s」节点过多", name)
	}
	for _, member := range members {
		if err := collectLeaves(cfg, member, stack, out, visitedGroups, excludeNames); err != nil {
			return err
		}
	}
	return nil
}

func clearDialer(cfg *config.RawConfig, leaf string) {
	kind, name, ok := strings.Cut(leaf, ":")
	if !ok {
		return
	}
	switch kind {
	case "proxy":
		if item := findProxy(cfg, name); item != nil {
			delete(item, "dialer-proxy")
		}
	case "provider":
		if item := cfg.ProxyProvider[name]; item != nil {
			delete(item, "dialer-proxy")
		}
	}
}

func setDialer(cfg *config.RawConfig, leaf, entry string) error {
	kind, name, ok := strings.Cut(leaf, ":")
	if !ok {
		return fmt.Errorf("无法给「%s」设置入口", leaf)
	}
	switch kind {
	case "proxy":
		item := findProxy(cfg, name)
		if item == nil {
			return fmt.Errorf("落地节点不存在：%s", name)
		}
		item["dialer-proxy"] = entry
	case "provider":
		item := cfg.ProxyProvider[name]
		if item == nil {
			return fmt.Errorf("落地订阅不存在：%s", name)
		}
		item["dialer-proxy"] = entry
	default:
		return fmt.Errorf("无法给「%s」设置入口", leaf)
	}
	return nil
}

func stripDirect(cfg *config.RawConfig, name string, stack map[string]bool, excludeLanding map[string]bool) error {
	if isDirectLike(name) || isRejectLike(name) {
		return nil
	}
	if findProxy(cfg, name) != nil || cfg.ProxyProvider[name] != nil {
		return nil
	}
	group := findGroup(cfg, name)
	if group == nil {
		return fmt.Errorf("找不到「%s」", name)
	}
	if len(stack) > 24 {
		return fmt.Errorf("分组嵌套过深：%s", name)
	}
	if stack[name] {
		return fmt.Errorf("分组存在循环：%s", name)
	}
	stack[name] = true
	defer delete(stack, name)
	proxies := filterDirect(mapStrings(group, "proxies"))
	use := filterDirect(mapStrings(group, "use"))
	if len(excludeLanding) > 0 {
		if trimmed := filterExcluded(proxies, excludeLanding); len(trimmed)+len(use) > 0 || mapBool(group, "include-all") || mapBool(group, "include-all-proxies") {
			proxies = trimmed
		}
		if trimmedUse := filterExcluded(use, excludeLanding); len(proxies)+len(trimmedUse) > 0 || mapBool(group, "include-all") || mapBool(group, "include-all-providers") {
			use = trimmedUse
		}
	}
	includeAll := mapBool(group, "include-all") || mapBool(group, "include-all-proxies") || mapBool(group, "include-all-providers")
	if len(proxies)+len(use) == 0 && !includeAll {
		return fmt.Errorf("「%s」去掉直连后没有可用节点", name)
	}
	if len(proxies) > 0 || !includeAll {
		group["proxies"] = toAny(proxies)
	} else {
		delete(group, "proxies")
	}
	if len(use) == 0 {
		delete(group, "use")
	} else {
		group["use"] = toAny(use)
	}
	for _, member := range append(append([]string{}, proxies...), use...) {
		if err := stripDirect(cfg, member, stack, excludeLanding); err != nil {
			return err
		}
	}
	return nil
}

func filterDirect(items []string) []string {
	out := make([]string, 0, len(items))
	for _, item := range items {
		if isDirectLike(item) {
			continue
		}
		out = append(out, item)
	}
	return out
}

func filterExcluded(items []string, excluded map[string]bool) []string {
	if len(excluded) == 0 {
		return items
	}
	out := make([]string, 0, len(items))
	for _, item := range items {
		if excluded[item] {
			continue
		}
		out = append(out, item)
	}
	return out
}

func toAny(items []string) []any {
	out := make([]any, len(items))
	for i, item := range items {
		out[i] = item
	}
	return out
}

func providerNames(cfg *config.RawConfig) []string {
	out := make([]string, 0, len(cfg.ProxyProvider))
	for name := range cfg.ProxyProvider {
		name = strings.TrimSpace(name)
		if name == "" || isDirectLike(name) || isRejectLike(name) {
			continue
		}
		out = append(out, name)
	}
	sort.Strings(out)
	return out
}

func hasName(cfg *config.RawConfig, name string) bool {
	return findProxy(cfg, name) != nil || findGroup(cfg, name) != nil || cfg.ProxyProvider[name] != nil
}

func findProxy(cfg *config.RawConfig, name string) map[string]any {
	for _, item := range cfg.Proxy {
		if mapString(item, "name") == name {
			return item
		}
	}
	return nil
}

func findGroup(cfg *config.RawConfig, name string) map[string]any {
	for _, item := range cfg.ProxyGroup {
		if mapString(item, "name") == name {
			return item
		}
	}
	return nil
}

func proxyNames(cfg *config.RawConfig) []string {
	out := make([]string, 0, len(cfg.Proxy))
	for _, item := range cfg.Proxy {
		name := mapString(item, "name")
		if name == "" || isDirectLike(name) || isRejectLike(name) {
			continue
		}
		out = append(out, name)
	}
	return out
}

func groupNames(cfg *config.RawConfig) []string {
	out := make([]string, 0, len(cfg.ProxyGroup))
	for _, item := range cfg.ProxyGroup {
		name := mapString(item, "name")
		if name == "" || isDirectLike(name) || isRejectLike(name) {
			continue
		}
		out = append(out, name)
	}
	return out
}

func mapBool(item map[string]any, key string) bool {
	if item == nil {
		return false
	}
	v, _ := item[key].(bool)
	return v
}

func mapString(item map[string]any, key string) string {
	switch v := item[key].(type) {
	case string:
		return strings.TrimSpace(v)
	default:
		return ""
	}
}

func mapStrings(item map[string]any, key string) []string {
	switch v := item[key].(type) {
	case []string:
		return v
	case []any:
		out := make([]string, 0, len(v))
		for _, one := range v {
			if s, ok := one.(string); ok && strings.TrimSpace(s) != "" {
				out = append(out, strings.TrimSpace(s))
			}
		}
		return out
	default:
		return nil
	}
}

func renameList(items []string, rename func(string) string) []any {
	out := make([]any, 0, len(items))
	for _, item := range items {
		if isDirectLike(item) || isRejectLike(item) {
			out = append(out, item)
			continue
		}
		out = append(out, rename(item))
	}
	return out
}

func cloneMap(item map[string]any) map[string]any {
	// Keep imported YAML scalar types intact; JSON round-tripping converts
	// integers to float64 and can subtly change the configuration handed to mihomo.
	out, err := cloneMapStrict(item)
	if err != nil {
		return item
	}
	return out
}

// Profile directories are named with canonical UUID-shaped identifiers. Validate
// the persisted value before joining it to the profile root so a malformed chain.json
// cannot select a config outside that root.
func isProfileID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for i := 0; i < len(value); i++ {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if value[i] != '-' {
				return false
			}
			continue
		}
		c := value[i]
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

func isDirectLike(name string) bool {
	switch strings.ToLower(strings.TrimSpace(name)) {
	case "direct", "直连":
		return true
	default:
		return false
	}
}

func isRejectLike(name string) bool {
	n := strings.ToUpper(strings.TrimSpace(name))
	return n == "REJECT" || n == "REJECT-DROP" || strings.HasPrefix(n, "REJECT")
}
