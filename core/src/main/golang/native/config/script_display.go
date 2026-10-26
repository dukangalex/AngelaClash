package config

import (
	"encoding/json"
	"os"
	P "path"
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/common/orderedmap"
	procmode "github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

// System explicit options. Written to files/clash/script-options.json.
// processor order is: script override -> overrides/patches -> script display ->
// chain, i.e. these switches win over the profile and the override script, but
// chain routing is applied last (see process.go). Keep this comment and the
// processors list in sync.
func patchScriptDisplay(cfg *config.RawConfig, profileDir string) error {
	opts, _ := readScriptOptions()
	applyScriptDisplayOptions(cfg, opts, profileScriptEnabled(profileDir), hasActiveScript(profileDir))
	if cfg.DNS.Enable && len(cfg.DNS.NameServer) == 0 {
		cfg.DNS.NameServer = []string{"https://223.5.5.5/dns-query", "https://1.1.1.1/dns-query"}
	}
	repairRawConfig(cfg)
	return nil
}

// applyScriptDisplayOptions is the whole switchboard in one place, split out of
// patchScriptDisplay so the profile gate can be tested without a filesystem.
//
// Policy groups belong to the profile. The 分流显示选项 switches may only rewrite
// them when THIS profile opted into the override script (script.on). Otherwise a
// subscription's own 策略组 must reach the proxy page untouched - otherwise
// toggling a switch on the script page silently renames/creates/drops groups in
// profiles that never enabled the script.
func applyScriptDisplayOptions(cfg *config.RawConfig, opts scriptOptionFile, scriptOn, scriptRan bool) {
	groups := splitScriptOptions(opts.Values)
	if scriptOn {
		applyRouting(cfg, groups, scriptRan)
	} else {
		// Leak guards are rule-level only: they never create or drop a group.
		applyQuicWebRtcGuards(cfg, groups.rule, scriptRan)
	}
	// Leak protection, China direct and strict routing stay on for every
	// profile: they only prepend rules the user can see, and never touch the
	// policy groups the subscription ships.
	applyLeak(cfg, groups.leak)
	applyChina(cfg, groups.cn)
	applyStrict(cfg, groups.strict)
	applyPrivacy(cfg, groups.privacy)
}

// profileScriptEnabled reports whether the profile opted into the override
// script. The flag is written beside config.yaml by ProfileProcessor and
// ConfigurationModule.
func profileScriptEnabled(profileDir string) bool {
	if profileDir == "" {
		return false
	}
	_, err := os.Stat(P.Join(profileDir, "script.on"))
	return err == nil
}

// hasActiveScript is kept for callers that want to know whether the profile's
// override script contributed to the running configuration. It is driven by the
// loader instead of re-reading and re-scanning script.js, so a script that was
// skipped, timed out or failed can never be mistaken for an applied one.
func hasActiveScript(profileDir string) bool {
	if profileDir == "" {
		return false
	}
	return scriptApplied.Load()
}

type scriptOptionFile struct {
	Enabled       bool            `json:"enabled"`
	ScriptEnabled *bool           `json:"scriptEnabled"`
	Values        map[string]bool `json:"values"`
	// RuleOverrides is keyed by profile uuid and holds that profile's rule
	// draft. See rule_override.go.
	RuleOverrides map[string]RuleOverride `json:"ruleOverrides"`
	// DisabledGroups is keyed by profile uuid and lists the policy group names
	// the user switched off for that profile. See group_override.go.
	DisabledGroups map[string][]string `json:"disabledGroups"`
}

type scriptGroups struct {
	rule, leak, cn, strict, privacy map[string]bool
}

func readScriptOptions() (scriptOptionFile, bool) {
	return readOptionsFrom(C.Path.Resolve("script-options.json"))
}

func readOptionsFrom(path string) (scriptOptionFile, bool) {
	buf, err := os.ReadFile(path)
	if err != nil {
		return scriptOptionFile{}, false
	}
	var opts scriptOptionFile
	if err := json.Unmarshal(buf, &opts); err != nil {
		log.Warnln("script display options: %s", err.Error())
		return scriptOptionFile{}, false
	}
	return opts, true
}

func splitScriptOptions(values map[string]bool) scriptGroups {
	g := scriptGroups{
		rule:    map[string]bool{},
		leak:    map[string]bool{},
		cn:      map[string]bool{},
		strict:  map[string]bool{},
		privacy: map[string]bool{},
	}
	for key, on := range values {
		group, name, ok := strings.Cut(key, "\u001f")
		if !ok || name == "" {
			continue
		}
		switch group {
		case "rule":
			g.rule[name] = on
		case "leak":
			g.leak[name] = on
		case "cn":
			g.cn[name] = on
		case "strict":
			g.strict[name] = on
		case "privacy":
			g.privacy[name] = on
		}
	}
	return g
}

func on(m map[string]bool, fallback bool, keys ...string) bool {
	for _, key := range keys {
		if value, ok := m[key]; ok {
			return value
		}
	}
	return fallback
}

func applyLeak(cfg *config.RawConfig, leak map[string]bool) {
	cfg.DNS.Enable = true
	if on(leak, true, "DNS 走代理", "dns-via-proxy") {
		// Seed sane defaults ONLY when the profile does not configure DNS.
		// Overwriting a subscription's nameservers breaks unlock policies,
		// fake-ip filters and split-DNS setups the user cannot see.
		if len(cfg.DNS.NameServer) == 0 {
			cfg.DNS.NameServer = []string{"https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"}
		}
		if len(cfg.DNS.ProxyServerNameserver) == 0 {
			cfg.DNS.ProxyServerNameserver = []string{"https://223.5.5.5/dns-query"}
		}
	}
	if on(leak, true, "禁止系统 DNS", "no-system-dns") {
		cfg.ClashForAndroid.AppendSystemDNS = false
		cfg.DNS.NameServer = stripSystemDNS(cfg.DNS.NameServer)
		cfg.DNS.Fallback = stripSystemDNS(cfg.DNS.Fallback)
		cfg.DNS.DefaultNameserver = stripSystemDNS(cfg.DNS.DefaultNameserver)
	} else {
		cfg.ClashForAndroid.AppendSystemDNS = true
		cfg.DNS.NameServer = appendUnique(cfg.DNS.NameServer, "system://")
	}
	if on(leak, true, "关闭 IPv6", "disable-ipv6") {
		cfg.IPv6 = false
		cfg.DNS.IPv6 = false
	} else {
		cfg.IPv6 = true
		cfg.DNS.IPv6 = true
	}
	if on(leak, false, "阻断 QUIC", "block-quic") {
		cfg.Rule = dropMatching(cfg.Rule, isQuicReject)
		rule := "AND,((NETWORK,UDP),(DST-PORT,443)),REJECT"
		if on(leak, false, "放行中国 QUIC", "allow-cn-quic") {
			rule = "AND,((NETWORK,UDP),(DST-PORT,443),(NOT,((GEOSITE,cn)))),REJECT"
		}
		cfg.Rule = append([]string{rule}, cfg.Rule...)
	}
	if on(leak, true, "嗅探防泄漏", "sniff-leak") {
		cfg.Sniffer.Enable = true
		cfg.Sniffer.OverrideDest = true
		cfg.Sniffer.ForceDnsMapping = true
		cfg.Sniffer.ParsePureIp = true
	} else {
		cfg.Sniffer.Enable = false
	}
}

func applyChina(cfg *config.RawConfig, cn map[string]bool) {
	cfg.Rule = dropMatching(cfg.Rule, func(rule string) bool {
		return strings.HasPrefix(rule, "GEOIP,CN,DIRECT") ||
			strings.HasPrefix(rule, "GEOSITE,cn,DIRECT") ||
			strings.HasPrefix(rule, "GEOIP,private,DIRECT")
	})
	var front []string
	if on(cn, true, "局域网直连", "lan-direct") {
		front = append(front, "GEOIP,private,DIRECT,no-resolve")
	}
	if on(cn, true, "中国大陆域名直连", "geosite-cn-direct") {
		front = append(front, "GEOSITE,cn,DIRECT")
	}
	cfg.Rule = prependMissing(cfg.Rule, front)
	if on(cn, true, "中国大陆 IP 直连", "geoip-cn-direct") {
		cfg.Rule = insertBeforeMatch(cfg.Rule, "GEOIP,CN,DIRECT")
	}
	if on(cn, true, "国内 DNS", "domestic-dns") {
		if cfg.DNS.NameServerPolicy == nil {
			cfg.DNS.NameServerPolicy = orderedmap.New[string, any]()
		}
		cfg.DNS.NameServerPolicy.Set("geosite:cn", "https://223.5.5.5/dns-query")
		cfg.DNS.DirectNameServer = appendUnique(cfg.DNS.DirectNameServer, "https://223.5.5.5/dns-query")
	} else if cfg.DNS.NameServerPolicy != nil {
		cfg.DNS.NameServerPolicy.Delete("geosite:cn")
	}
}

func insertBeforeMatch(rules []string, extra string) []string {
	for _, r := range rules {
		if r == extra {
			return rules
		}
	}
	for i, r := range rules {
		if strings.HasPrefix(strings.ToUpper(strings.TrimSpace(r)), "MATCH,") {
			out := make([]string, 0, len(rules)+1)
			out = append(out, rules[:i]...)
			out = append(out, extra)
			out = append(out, rules[i:]...)
			return out
		}
	}
	return append(rules, extra)
}

func applyPrivacy(cfg *config.RawConfig, privacy map[string]bool) {
	var front []string
	if on(privacy, false, "拦截 STUN", "block-stun") {
		cfg.Rule = dropMatching(cfg.Rule, isStunReject)
		front = append(front,
			"AND,((NETWORK,UDP),(DST-PORT,3478)),REJECT",
			"AND,((NETWORK,UDP),(DST-PORT,19302)),REJECT",
		)
	}
	if on(privacy, false, "屏蔽局域网发现", "block-discovery") {
		cfg.Rule = dropMatching(cfg.Rule, isDiscoveryReject)
		front = append(front,
			"AND,((NETWORK,UDP),(DST-PORT,5353)),REJECT",
			"AND,((NETWORK,UDP),(DST-PORT,1900)),REJECT",
			"AND,((NETWORK,UDP),(DST-PORT,137)),REJECT",
			"AND,((NETWORK,UDP),(DST-PORT,138)),REJECT",
		)
	}
	cfg.Rule = prependMissing(cfg.Rule, front)
}

func isStunReject(rule string) bool {
	if !strings.Contains(strings.ToUpper(rule), "REJECT") {
		return false
	}
	for _, port := range []int{3478, 5349, 19302} {
		if ruleDstPort(rule, port) {
			return true
		}
	}
	return false
}

func isDiscoveryReject(rule string) bool {
	if !strings.Contains(strings.ToUpper(rule), "REJECT") {
		return false
	}
	for _, port := range []int{5353, 1900, 137, 138} {
		if ruleDstPort(rule, port) {
			return true
		}
	}
	return false
}

func applyStrict(cfg *config.RawConfig, strict map[string]bool) {
	cfg.Tun.StrictRoute = on(strict, false, "严格路由", "strict-route")
	if on(strict, true, "DNS 遵循规则", "dns-respect-rules") || cfg.Tun.StrictRoute {
		cfg.DNS.RespectRules = true
	} else {
		cfg.DNS.RespectRules = false
	}
	if on(strict, false, "进程严格匹配", "find-process-strict") {
		cfg.FindProcessMode = procmode.FindProcessStrict
	} else {
		cfg.FindProcessMode = procmode.FindProcessOff
	}
}

type serviceRule struct {
	keys  []string
	group bool
	rules []string
}

var serviceRules = []serviceRule{
	{[]string{"AI"}, true, []string{"GEOSITE,openai,%s", "DOMAIN-SUFFIX,claude.ai,%s", "DOMAIN-SUFFIX,anthropic.com,%s", "DOMAIN-SUFFIX,gemini.google.com,%s"}},
	{[]string{"Google"}, true, []string{"GEOSITE,google,%s"}},
	{[]string{"YouTube"}, true, []string{"GEOSITE,youtube,%s"}},
	{[]string{"Telegram"}, true, []string{"GEOSITE,telegram,%s"}},
	{[]string{"Netflix"}, true, []string{"GEOSITE,netflix,%s"}},
	{[]string{"广告拦截"}, false, []string{"GEOSITE,category-ads-all,REJECT"}},
}

// nonGroupRuleOptions are behavioral toggles declared in ruleOptionsEnable
// that control script generation behavior rather than representing a proxy-group.
var nonGroupRuleOptions = map[string]bool{
	"极简模式":      true,
	"生成地区自动选择组": true,
	"隐藏地区手动选择组": true,
	"生成倍率组":     true,
	"分流组添加所有节点": true,
	"过滤低倍率节点":   true,
	"过滤高倍率节点":   true,
	"过滤非地区节点":   true,
	"屏蔽国外QUIC":  true,
	"屏蔽WebRTC":  true,
	"强制TUN":     true,
	"代理IPV4优先":  true,
	"代理IPV6优先":  true,
	"链式代理":      true,
	"广告拦截":      true,
	"AdBlock":   true,
}

var baseStrategyOptions = map[string]bool{
	"手动选择": true,
	"自动选择": true,
	"负载均衡": true,
	"故障转移": true,
}

func applyRouting(cfg *config.RawConfig, groups scriptGroups, scriptRan bool) {
	applyQuicWebRtcGuards(cfg, groups.rule, scriptRan)
	applyPolicyGroups(cfg, groups.rule, scriptRan)
}

// applyQuicWebRtcGuards only inserts or removes REJECT rules. It never creates
// a proxy group, so it is safe to run for profiles without an override script.
func applyQuicWebRtcGuards(cfg *config.RawConfig, rule map[string]bool, scriptRan bool) {
	if quicOn, ok := rule["屏蔽国外QUIC"]; ok {
		if !quicOn {
			cfg.Rule = dropMatching(cfg.Rule, isQuicReject)
		} else if !scriptRan {
			cfg.Rule = dropMatching(cfg.Rule, isQuicReject)
			cfg.Rule = append([]string{"AND,((NETWORK,UDP),(DST-PORT,443),(NOT,((GEOSITE,cn)))),REJECT"}, cfg.Rule...)
		}
	}
	if webrtcOn, ok := rule["屏蔽WebRTC"]; ok {
		if !webrtcOn {
			cfg.Rule = dropMatching(cfg.Rule, isStunReject)
		} else if !scriptRan {
			cfg.Rule = dropMatching(cfg.Rule, isStunReject)
			cfg.Rule = append([]string{
				"AND,((NETWORK,UDP),(DST-PORT,3478-3497)),REJECT",
				"AND,((NETWORK,UDP),(DST-PORT,5349)),REJECT",
				"AND,((NETWORK,UDP),(DST-PORT,19302-19309)),REJECT",
			}, cfg.Rule...)
		}
	}
}

// applyPolicyGroups owns the proxy groups: it creates a select group per enabled
// service option, drops the groups whose switch was turned off and rewrites the
// matching rules. Only reached when the profile enabled the override script.
func applyPolicyGroups(cfg *config.RawConfig, rule map[string]bool, scriptRan bool) {
	minimalMode := rule["极简模式"]
	known := map[string]bool{}
	var front []string
	disabled := map[string]bool{}

	for name, on := range rule {
		if nonGroupRuleOptions[name] {
			continue
		}
		if !on || minimalMode {
			disabled[name] = true
		}
	}

	if adKey, hasAd := firstPresent(rule, []string{"AdBlock", "广告拦截"}); hasAd {
		if !rule[adKey] || minimalMode {
			cfg.ProxyGroup = dropGroup(cfg.ProxyGroup, "AdBlock")
			cfg.ProxyGroup = dropGroup(cfg.ProxyGroup, "广告拦截")
			cfg.Rule = dropMatching(cfg.Rule, isAdBlockRule)
		} else if !scriptRan {
			front = append(front, "GEOSITE,category-ads-all,REJECT")
		}
	}

	fallback := fallbackGroup(cfg, disabled)

	for _, spec := range serviceRules {
		for _, key := range spec.keys {
			known[key] = true
		}
		if minimalMode {
			continue
		}
		name, ok := firstPresent(rule, spec.keys)
		if !ok || !rule[name] {
			continue
		}
		if spec.group && !scriptRan {
			ensureSelectGroup(cfg, name)
		}
		if !scriptRan {
			for _, pattern := range spec.rules {
				if strings.Contains(pattern, "%s") {
					front = append(front, strings.ReplaceAll(pattern, "%s", name))
				} else {
					front = append(front, pattern)
				}
			}
		}
	}

	for name, on := range rule {
		if nonGroupRuleOptions[name] {
			continue
		}
		if !on || minimalMode {
			cfg.ProxyGroup = dropGroup(cfg.ProxyGroup, name)
			continue
		}
		if known[name] || baseStrategyOptions[name] || scriptRan {
			continue
		}
		ensureSelectGroup(cfg, name)
	}

	if genAuto, ok := rule["生成地区自动选择组"]; ok && (!genAuto || minimalMode) {
		out := make([]map[string]any, 0, len(cfg.ProxyGroup))
		for _, g := range cfg.ProxyGroup {
			n, _ := g["name"].(string)
			if strings.HasSuffix(n, "-自动选择") {
				disabled[n] = true
				continue
			}
			out = append(out, g)
		}
		cfg.ProxyGroup = out
	}

	if genRate, ok := rule["生成倍率组"]; ok && (!genRate || minimalMode) {
		for _, rName := range []string{"低倍率", "高倍率", "低倍率-自动选择", "高倍率-自动选择"} {
			cfg.ProxyGroup = dropGroup(cfg.ProxyGroup, rName)
			disabled[rName] = true
		}
	}

	cfg.Rule = retargetRules(cfg.Rule, disabled, fallback)
	cfg.Rule = prependMissing(cfg.Rule, front)
	repairRawConfig(cfg)
}

func isAdBlockRule(rule string) bool {
	trimmed := strings.TrimSpace(rule)
	upper := strings.ToUpper(trimmed)
	if strings.Contains(upper, "CATEGORY-ADS") {
		return true
	}
	if strings.HasSuffix(trimmed, ",AdBlock") || strings.HasSuffix(trimmed, ",AdBlock,no-resolve") {
		return true
	}
	if strings.HasSuffix(trimmed, ",广告拦截") || strings.HasSuffix(trimmed, ",广告拦截,no-resolve") {
		return true
	}
	return false
}

func dropMatching(rules []string, drop func(string) bool) []string {
	if len(rules) == 0 {
		return rules
	}
	out := make([]string, 0, len(rules))
	for _, rule := range rules {
		if drop(rule) {
			continue
		}
		out = append(out, rule)
	}
	return out
}

// ruleDstPort reports whether a rule pins DST-PORT to a value that covers port.
// Ports are compared as tokens so that 4430 is not treated as 443, and ranges
// such as 3478-3497 are understood.
func ruleDstPort(rule string, port int) bool {
	upper := strings.ToUpper(rule)
	index := strings.Index(upper, "DST-PORT")
	if index < 0 {
		return false
	}
	rest := upper[index+len("DST-PORT"):]
	rest = strings.TrimLeft(rest, " \t,=")
	end := strings.IndexAny(rest, ",() ")
	if end >= 0 {
		rest = rest[:end]
	}
	rest = strings.TrimSpace(rest)
	if rest == "" {
		return false
	}
	if from, to, isRange := strings.Cut(rest, "-"); isRange {
		low, lowErr := strconv.Atoi(strings.TrimSpace(from))
		high, highErr := strconv.Atoi(strings.TrimSpace(to))
		if lowErr != nil || highErr != nil {
			return false
		}
		return port >= low && port <= high
	}
	value, err := strconv.Atoi(rest)
	return err == nil && value == port
}

func isQuicReject(rule string) bool {
	return strings.Contains(strings.ToUpper(rule), "UDP") && ruleDstPort(rule, 443)
}

func firstPresent(m map[string]bool, keys []string) (string, bool) {
	for _, key := range keys {
		if _, ok := m[key]; ok {
			return key, true
		}
	}
	return "", false
}

func ensureSelectGroup(cfg *config.RawConfig, name string) {
	if name == "" || name == "DIRECT" || name == "REJECT" || nonGroupRuleOptions[name] {
		return
	}
	for _, group := range cfg.ProxyGroup {
		if n, _ := group["name"].(string); n == name {
			return
		}
	}
	cfg.ProxyGroup = append(cfg.ProxyGroup, map[string]any{
		"name":           name,
		"type":           "select",
		"proxies":        []any{"DIRECT"},
		"include-all":    true,
		"exclude-filter": `(?i)剩余流量|流量重置|到期|官网|套餐`,
	})
}

func dropGroup(groups []map[string]any, name string) []map[string]any {
	if name == "" || name == "DIRECT" || name == "REJECT" {
		return groups
	}
	out := make([]map[string]any, 0, len(groups))
	for _, group := range groups {
		if n, _ := group["name"].(string); n == name {
			continue
		}
		out = append(out, group)
	}
	return out
}

func fallbackGroup(cfg *config.RawConfig, disabled map[string]bool) string {
	prefer := []string{"默认代理", "主代理", "PROXY", "Proxy", "节点选择", "手动切换", "SELECT", "漏网之鱼"}
	names := map[string]bool{}
	first := ""
	for _, group := range cfg.ProxyGroup {
		name, _ := group["name"].(string)
		if name == "" || disabled[name] {
			continue
		}
		names[name] = true
		if first == "" && name != "GLOBAL" && name != "直连" && name != "AdBlock" && name != "广告拦截" {
			first = name
		}
	}
	for _, name := range prefer {
		if names[name] {
			return name
		}
	}
	if first != "" {
		return first
	}
	return "DIRECT"
}

func retargetRules(rules []string, disabled map[string]bool, fallback string) []string {
	if len(disabled) == 0 {
		return rules
	}
	out := make([]string, len(rules))
	for i, rule := range rules {
		next := rule
		for name := range disabled {
			if name == fallback {
				continue
			}
			next = retarget(next, name, fallback)
		}
		out[i] = next
	}
	return out
}

func retarget(rule, from, to string) string {
	if from == "" || from == to {
		return rule
	}
	if strings.HasSuffix(rule, ","+from) {
		return strings.TrimSuffix(rule, from) + to
	}
	suffix := "," + from + ",no-resolve"
	if strings.HasSuffix(rule, suffix) {
		return strings.TrimSuffix(rule, from+",no-resolve") + to + ",no-resolve"
	}
	return rule
}

func prependMissing(rules []string, extra []string) []string {
	if len(extra) == 0 {
		return rules
	}
	have := map[string]bool{}
	for _, rule := range rules {
		have[rule] = true
	}
	front := make([]string, 0, len(extra))
	for _, rule := range extra {
		if have[rule] {
			continue
		}
		front = append(front, rule)
		have[rule] = true
	}
	return append(front, rules...)
}

func stripSystemDNS(servers []string) []string {
	if len(servers) == 0 {
		return servers
	}
	out := make([]string, 0, len(servers))
	for _, server := range servers {
		if strings.EqualFold(server, "system://") || strings.HasPrefix(strings.ToLower(server), "system://") {
			continue
		}
		out = append(out, server)
	}
	return out
}

func appendUnique(list []string, item string) []string {
	for _, have := range list {
		if have == item {
			return list
		}
	}
	return append(list, item)
}
