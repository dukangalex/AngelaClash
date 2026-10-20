package config

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	P "path"
	"sort"
	"strings"

	"github.com/metacubex/mihomo/config"
)

const chainFileName = "chain.json"

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
		return append([]string(nil), v...), nil
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
		other, err := readRaw(P.Join(P.Dir(profileDir), spec.LandingProfile, "config.yaml"))
		if err != nil {
			return fmt.Errorf("落地配置读不出来: %s", err.Error())
		}
		if !hasName(other, spec.Landing) {
			return fmt.Errorf("落地「%s」不在所选配置里", spec.Landing)
		}
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
	if err := stripDirect(cfg, spec.Entry, map[string]bool{}); err != nil {
		return err
	}
	if err := stripDirect(cfg, landingName, map[string]bool{}); err != nil {
		return err
	}

	entryLeaves := map[string]struct{}{}
	landingLeaves := map[string]struct{}{}
	if err := collectLeaves(cfg, spec.Entry, map[string]bool{}, entryLeaves); err != nil {
		return err
	}
	if err := collectLeaves(cfg, landingName, map[string]bool{}, landingLeaves); err != nil {
		return err
	}
	if len(entryLeaves) == 0 {
		return fmt.Errorf("入口「%s」没有可拨号的节点", spec.Entry)
	}
	if len(landingLeaves) == 0 {
		return fmt.Errorf("落地「%s」没有可拨号的节点", spec.Landing)
	}
	for name := range landingLeaves {
		if _, ok := entryLeaves[name]; ok {
			return fmt.Errorf("入口和落地包含同一个节点「%s」", name)
		}
	}

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

func readRaw(path string) (*config.RawConfig, error) {
	buf, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return config.UnmarshalRawConfig(buf)
}

func mergeLanding(dst, src *config.RawConfig, profileID, landing string) (string, error) {
	rename := func(name string) string {
		return "chain-landing-" + profileID + "-" + name
	}
	// Validate every imported name before mutating dst, so a collision cannot
	// leave a partially merged configuration or silently shadow an existing item.
	existingNames := map[string]struct{}{}
	for _, item := range dst.Proxy {
		if name := mapString(item, "name"); name != "" { existingNames[name] = struct{}{} }
	}
	for _, item := range dst.ProxyGroup {
		if name := mapString(item, "name"); name != "" { existingNames[name] = struct{}{} }
	}
	for name := range dst.ProxyProvider { existingNames[name] = struct{}{} }
	importedNames := map[string]struct{}{}
	checkName := func(name string) error {
		if name == "" { return nil }
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
		if err := checkName(mapString(item, "name")); err != nil { return "", err }
	}
	for _, item := range src.ProxyGroup {
		if err := checkName(mapString(item, "name")); err != nil { return "", err }
	}
	for name := range src.ProxyProvider {
		if err := checkName(name); err != nil { return "", err }
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
		if isDirectLike(target) && isBypassRule(rule) {
			continue
		}
		if target == landing {
			changed = true
			continue
		}
		parts := splitRule(rule)
		parts[idx] = landing
		cfg.Rule[i] = strings.Join(parts, ",")
		changed = true
	}
	if !changed {
		cfg.Rule = append(cfg.Rule, "MATCH,"+landing)
	}
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
		return payload == "cn" || payload == "geolocation-cn" || strings.Contains(payload, "geolocation-cn")
	case "DOMAIN-SUFFIX":
		return payload == "cn" || strings.HasSuffix(payload, ".cn")
	case "IP-CIDR", "IP-CIDR6":
		return isPrivateCIDR(parts[1])
	case "RULE-SET":
		return strings.Contains(payload, "cn") || strings.Contains(payload, "private") || strings.Contains(payload, "lan")
	default:
		return false
	}
}

func isPrivateCIDR(raw string) bool {
	raw = strings.TrimSpace(raw)
	ip, _, err := net.ParseCIDR(raw)
	if err != nil {
		// Keep support for a bare IP address in rule payloads.
		ip = net.ParseIP(raw)
		if ip == nil {
			return false
		}
	}
	return ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsUnspecified()
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

func collectLeaves(cfg *config.RawConfig, name string, stack map[string]bool, out map[string]struct{}) error {
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
	stack[name] = true
	defer delete(stack, name)
	members := append(mapStrings(group, "proxies"), mapStrings(group, "use")...)
	if len(members) > 512 {
		return fmt.Errorf("分组「%s」节点过多", name)
	}
	for _, member := range members {
		if err := collectLeaves(cfg, member, stack, out); err != nil {
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

func stripDirect(cfg *config.RawConfig, name string, stack map[string]bool) error {
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
	if len(proxies)+len(use) == 0 {
		return fmt.Errorf("「%s」去掉直连后没有可用节点", name)
	}
	group["proxies"] = toAny(proxies)
	if len(use) == 0 {
		delete(group, "use")
	} else {
		group["use"] = toAny(use)
	}
	for _, member := range append(append([]string{}, proxies...), use...) {
		if err := stripDirect(cfg, member, stack); err != nil {
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
	buf, err := json.Marshal(item)
	if err != nil {
		return item
	}
	var out map[string]any
	if json.Unmarshal(buf, &out) != nil {
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
