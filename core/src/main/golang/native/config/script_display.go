package config

import (
	"encoding/json"
	"os"
	"strings"

	"github.com/metacubex/mihomo/common/orderedmap"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

// Script display options. The UI writes files/clash/script-options.json.
// Keys are "<group>\x1f<label>". Missing file or enabled=false is a no-op.
func patchScriptDisplay(cfg *config.RawConfig, _ string) error {
	opts, ok := readScriptOptions()
	if !ok || !opts.Enabled {
		return nil
	}
	groups := splitScriptOptions(opts.Values)
	applyLeak(cfg, groups.leak)
	applyChina(cfg, groups.cn)
	applyStrict(cfg, groups.strict)
	applyRouting(cfg, groups)
	if cfg.DNS.Enable && len(cfg.DNS.NameServer) == 0 {
		cfg.DNS.NameServer = []string{"https://223.5.5.5/dns-query", "https://1.1.1.1/dns-query"}
	}
	if cfg.DNS.RespectRules && len(cfg.DNS.ProxyServerNameserver) == 0 {
		cfg.DNS.ProxyServerNameserver = []string{"https://223.5.5.5/dns-query"}
	}
	return nil
}

type scriptOptionFile struct {
	Enabled bool            `json:"enabled"`
	Values  map[string]bool `json:"values"`
}

type scriptGroups struct {
	rule, leak, cn, strict map[string]bool
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
		rule:   map[string]bool{},
		leak:   map[string]bool{},
		cn:     map[string]bool{},
		strict: map[string]bool{},
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
		}
	}
	return g
}

func flag(m map[string]bool, keys ...string) bool {
	for _, key := range keys {
		if on, ok := m[key]; ok {
			return on
		}
	}
	return false
}

func applyLeak(cfg *config.RawConfig, leak map[string]bool) {
	if flag(leak, "DNS 走代理", "dns-via-proxy") {
		cfg.DNS.Enable = true
		cfg.DNS.RespectRules = true
		cfg.DNS.NameServer = []string{"https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"}
		cfg.DNS.Fallback = nil
		cfg.DNS.ProxyServerNameserver = []string{"https://223.5.5.5/dns-query"}
	}
	if flag(leak, "禁止系统 DNS", "no-system-dns") {
		cfg.ClashForAndroid.AppendSystemDNS = false
		cfg.DNS.NameServer = stripSystemDNS(cfg.DNS.NameServer)
		cfg.DNS.Fallback = stripSystemDNS(cfg.DNS.Fallback)
		cfg.DNS.DefaultNameserver = stripSystemDNS(cfg.DNS.DefaultNameserver)
	}
	if flag(leak, "关闭 IPv6", "disable-ipv6") {
		cfg.IPv6 = false
		cfg.DNS.IPv6 = false
	}
	if flag(leak, "嗅探防泄漏", "sniff-leak") {
		cfg.Sniffer.Enable = true
		cfg.Sniffer.OverrideDest = true
		cfg.Sniffer.ForceDnsMapping = true
		cfg.Sniffer.ParsePureIp = true
	}
}

func applyChina(cfg *config.RawConfig, cn map[string]bool) {
	if flag(cn, "国内 DNS", "domestic-dns") {
		if cfg.DNS.NameServerPolicy == nil {
			cfg.DNS.NameServerPolicy = orderedmap.New[string, any]()
		}
		cfg.DNS.NameServerPolicy.Set("geosite:cn", "https://223.5.5.5/dns-query")
		cfg.DNS.DirectNameServer = appendUnique(cfg.DNS.DirectNameServer, "https://223.5.5.5/dns-query")
	}
}

func applyStrict(cfg *config.RawConfig, strict map[string]bool) {
	if flag(strict, "严格路由", "strict-route") {
		cfg.Tun.StrictRoute = true
	}
	if flag(strict, "DNS 遵循规则", "dns-respect-rules") || flag(strict, "严格路由", "strict-route") {
		cfg.DNS.RespectRules = true
	}
	if flag(strict, "进程严格匹配", "find-process-strict") {
		cfg.FindProcessMode = process.FindProcessStrict
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

	if flag(groups.leak, "阻断 QUIC", "block-quic") {
		front = append([]string{"AND,((NETWORK,UDP),(DST-PORT,443)),REJECT"}, front...)
	}
	if flag(groups.cn, "局域网直连", "lan-direct") {
		front = append([]string{"GEOIP,private,DIRECT,no-resolve"}, front...)
	}
	if flag(groups.cn, "中国大陆域名直连", "geosite-cn-direct") {
		front = append(front, "GEOSITE,cn,DIRECT")
	}
	if flag(groups.cn, "中国大陆 IP 直连", "geoip-cn-direct") {
		front = append(front, "GEOIP,CN,DIRECT")
	}

	cfg.Rule = retargetRules(cfg.Rule, disabled, fallback)
	cfg.Rule = prependMissing(cfg.Rule, front)
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
