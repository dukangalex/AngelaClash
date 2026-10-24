package config

import (
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/config"
)

// 审计修复回归测试：脚本沙箱、async 入口、端口/规则精确匹配、DNS 叠加。

func TestScriptInfiniteLoopIsInterrupted(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(sampleProfile))
	if err != nil {
		t.Fatal(err)
	}
	previous := scriptExecutionTimeout
	scriptExecutionTimeout = 150 * time.Millisecond
	defer func() { scriptExecutionTimeout = previous }()

	started := time.Now()
	err = applyScriptText(raw, `function main(config) { while (true) {} }`)
	elapsed := time.Since(started)
	if err == nil {
		t.Fatal("runaway script must fail the load")
	}
	if !strings.Contains(err.Error(), "已被中止") {
		t.Fatalf("expected timeout message, got %v", err)
	}
	if elapsed > 3*time.Second {
		t.Fatalf("watchdog did not fire in time: %v", elapsed)
	}
}

func TestAsyncMainIsAwaited(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(sampleProfile))
	if err != nil {
		t.Fatal(err)
	}
	script := `
async function main(config) {
  config.rules = ["MATCH,Old"];
  return config;
}
`
	if err = applyScriptText(raw, script); err != nil {
		t.Fatalf("async main should resolve: %v", err)
	}
	if len(raw.Rule) != 1 || raw.Rule[0] != "MATCH,Old" {
		t.Fatalf("async main did not modify config: %#v", raw.Rule)
	}
}

func TestMainInsideStringIsNotAnEntry(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(sampleProfile))
	if err != nil {
		t.Fatal(err)
	}
	if err = applyScriptText(raw, "const note = \"function main(config) { return config }\";"); err != nil {
		t.Fatalf("script without an entry point must be skipped, got %v", err)
	}
	if raw.ProxyGroup[0]["name"] != "Old" {
		t.Fatalf("string literal was treated as a script: %#v", raw.ProxyGroup)
	}
}

func TestSyntaxErrorIsReported(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(sampleProfile))
	if err != nil {
		t.Fatal(err)
	}
	err = applyScriptText(raw, "function main(config) { if ( }")
	if err == nil || !strings.Contains(err.Error(), "语法错误") {
		t.Fatalf("syntax error must surface, got %v", err)
	}
}

func TestQuicRejectMatchesExactPort(t *testing.T) {
	cases := map[string]bool{
		"AND,((NETWORK,UDP),(DST-PORT,443)),REJECT":     true,
		"AND,((NETWORK,UDP),(DST-PORT,4430)),REJECT":    false,
		"AND,((NETWORK,UDP),(DST-PORT,400-450)),REJECT": true,
		"AND,((NETWORK,TCP),(DST-PORT,443)),REJECT":     false,
	}
	for rule, want := range cases {
		if got := isQuicReject(rule); got != want {
			t.Fatalf("isQuicReject(%q) = %v, want %v", rule, got, want)
		}
	}
}

func TestRuleDstPortHandlesSpacingAndRanges(t *testing.T) {
	if !ruleDstPort("AND,((NETWORK,UDP),(DST-PORT, 3478)),REJECT", 3478) {
		t.Fatal("spaced port should match")
	}
	if !ruleDstPort("AND,((NETWORK,UDP),(DST-PORT,3478-3497)),REJECT", 3490) {
		t.Fatal("range should contain 3490")
	}
	if ruleDstPort("AND,((NETWORK,UDP),(DST-PORT,34780)),REJECT", 3478) {
		t.Fatal("34780 must not match 3478")
	}
}

func TestBypassRuleUsesTokens(t *testing.T) {
	cases := map[string]bool{
		"GEOSITE,cn,DIRECT":                    true,
		"GEOSITE,category-games@cn,DIRECT":     true,
		"GEOSITE,ncnc,DIRECT":                  false,
		"RULE-SET,my-direct-rules,DIRECT":      true,
		"RULE-SET,lancidr,DIRECT":              false,
		"GEOIP,CN,DIRECT":                      true,
		"IP-CIDR,10.0.0.0/8,DIRECT,no-resolve": true,
		"DOMAIN-SUFFIX,example.com,DIRECT":     false,
	}
	for rule, want := range cases {
		if got := isBypassRule(rule); got != want {
			t.Fatalf("isBypassRule(%q) = %v, want %v", rule, got, want)
		}
	}
}

func TestApplyLeakKeepsProfileDNS(t *testing.T) {
	dnsConfig, err := config.UnmarshalRawConfig([]byte(sampleProfile))
	if err != nil {
		t.Fatal(err)
	}
	dnsConfig.DNS.NameServer = []string{"https://dns.example.net/dns-query"}
	dnsConfig.DNS.Fallback = []string{"https://fallback.example.net/dns-query"}
	applyLeak(dnsConfig, map[string]bool{"DNS 走代理": true})
	if len(dnsConfig.DNS.NameServer) != 1 || dnsConfig.DNS.NameServer[0] != "https://dns.example.net/dns-query" {
		t.Fatalf("profile nameservers were overwritten: %#v", dnsConfig.DNS.NameServer)
	}
	if len(dnsConfig.DNS.Fallback) != 1 {
		t.Fatalf("profile fallback was cleared: %#v", dnsConfig.DNS.Fallback)
	}

	empty, err := config.UnmarshalRawConfig([]byte(sampleProfile))
	if err != nil {
		t.Fatal(err)
	}
	empty.DNS.NameServer = nil
	applyLeak(empty, map[string]bool{"DNS 走代理": true})
	if len(empty.DNS.NameServer) == 0 {
		t.Fatal("a profile without DNS must get seeded defaults")
	}
}
