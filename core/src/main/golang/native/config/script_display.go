package config

import (
	"encoding/json"
	"os"
	"strings"

	"github.com/metacubex/mihomo/common/orderedmap"
	procmode "github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

// System explicit options. Written to files/clash/script-options.json.
// They are applied after the profile, the override script, and chain, so a
// conflict is resolved in favor of these switches.
func patchScriptDisplay(cfg *config.RawConfig, _ string) error {
	opts, _ := readScriptOptions()
	groups := splitScriptOptions(opts.Values)
	applyRouting(cfg, groups)
	applyLeak(cfg, groups.leak)
	applyChina(cfg, groups.cn)
	applyStrict(cfg, groups.strict)
	applyPrivacy(cfg, groups.privacy)
	if cfg.DNS.Enable && len(cfg.DNS.NameServer) == 0 {
		cfg.DNS.NameServer = []string{"https://223.5.5.5/dns-query", "https://1.1.1.1/dns-query"}
	}
	return nil
}

type scriptOptionFile struct {
	Enabled       bool            `json:"enabled"`
	ScriptEnabled *bool           `json:"scriptEnabled"`
	Values        map[string]bool `json:"values"`
}

type scriptGroups struct {
	rule, leak, cn, strict, privacy map[string]bool
}

func readScriptOptions() (scriptOptionFile, bool) {
	path := C.Path.Resolve("script-options.json")
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
	if on(leak, true, "DNS 走代理", "dns-via-proxy") {
		cfg.DNS.Enable = true
		cfg.DNS.NameServer = []string{"https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"}
		cfg.DNS.Fallback = nil
		cfg.DNS.ProxyServerNameserver = []string{"https://223.5.5.5/dns-query"}
	} else {
		cfg.DNS.Enable = true
		cfg.DNS.NameServer = []string{"https://223.5.5.5/dns-query", "https://119.29.29.29/dns-query"}
		cfg.DNS.Fallback = nil
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
	cfg.Rule = dropMatching(cfg.Rule, isQuicReject)
	if on(leak, false, "阻断 QUIC", "block-quic") {
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
	if on(cn, true, "中国大陆 IP 直连", "geoip-cn-direct") {
		front = append(front, "GEOIP,CN,DIRECT")
	}
	cfg.Rule = prependMissing(cfg.Rule, front)
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

func applyPrivacy(cfg *config.RawConfig, privacy map[string]bool) {
	cfg.Rule = dropMatching(cfg.Rule, isPrivacyReject)
	var front []string
	if on(privacy, false, "拦截 STUN", "block-stun") {
		front = append(front,
			"AND,((NETWORK,UDP),(DST-PORT,3478)),REJECT",
			"AND,((NETWORK,UDP),(DST-PORT,19302)),REJECT",
		)
	}
	if on(privacy, false, "屏蔽局域网发现", "block-discovery") {
		front = append(front,
			"AND,((NETWORK,UDP),(DST-PORT,5353)),REJECT",
			"AND,((NETWORK,UDP),(DST-PORT,1900)),REJECT",
			"AND,((NETWORK,UDP),(DST-PORT,137)),REJECT",
			"AND,((NETWORK,UDP),(DST-PORT,138)),REJECT",
		)
	}
	cfg.Rule = prependMissing(cfg.Rule, front)
}

func isPrivacyReject(rule string) bool {
	upper := strings.ToUpper(rule)
	if !strings.Contains(upper, "REJECT") {
		return false
	}
	for _, port := range []string{"3478", "19302", "5353", "1900", "137", "138"} {
		if strings.Contains(upper, "DST-PORT,"+port) {
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

func applyRouting(cfg *config.RawConfig, groups scriptGroups) {
	rule := groups.rule
	known := map[string]bool{}
	var front []string
	disabled := map[string]bool{}
	for name, on := range rule {
		if !on {
			disabled[name] = true
		}
	}
	fallback := fallbackGroup(cfg, disabled)

	for _, spec := range serviceRules {
		for _, key := range spec.keys {
			known[key] = true
		}
		name, ok := firstPresent(rule, spec.keys)
		if !ok || !rule[name] {
			continue
		}
		if spec.group {
			ensureSelectGroup(cfg, name)
		}
		for _, pattern := range spec.rules {
			if strings.Contains(pattern, "%s") {
				front = append(front, strings.ReplaceAll(pattern, "%s", name))
			} else {
				front = append(front, pattern)
			}
		}
	}

	for name, on := range rule {
		if known[name] {
			if !on {
				cfg.ProxyGroup = dropGroup(cfg.ProxyGroup, name)
			}
			continue
		}
		if on {
			ensureSelectGroup(cfg, name)
			continue
		}
		cfg.ProxyGroup = dropGroup(cfg.ProxyGroup, name)
	}

	cfg.Rule = retargetRules(cfg.Rule, disabled, fallback)
	cfg.Rule = prependMissing(cfg.Rule, front)
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

func isQuicReject(rule string) bool {
	upper := strings.ToUpper(rule)
	return strings.Contains(upper, "DST-PORT,443") && strings.Contains(upper, "UDP")
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
	if name == "" || name == "DIRECT" || name == "REJECT" || name == "广告拦截" {
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
	prefer := []string{"主代理", "PROXY", "Proxy", "节点选择", "手动切换", "SELECT"}
	names := map[string]bool{}
	first := ""
	for _, group := range cfg.ProxyGroup {
		name, _ := group["name"].(string)
		if name == "" || disabled[name] {
			continue
		}
		names[name] = true
		if first == "" {
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
