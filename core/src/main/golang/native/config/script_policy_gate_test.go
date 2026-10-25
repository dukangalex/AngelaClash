package config

import (
	"testing"

	"github.com/metacubex/mihomo/config"
)

// 策略组归属：分流显示选项只能影响「开启了覆写脚本」的那份配置。
// 没有 script.on 的配置必须原样保留订阅自带的策略组与规则，
// 否则在脚本页拨一个开关，会把别的配置的代理页也一起改掉。

func displayOptions(values map[string]bool) scriptOptionFile {
	return scriptOptionFile{Enabled: true, Values: values}
}

// rawGroupNames keeps every group, including DIRECT/REJECT style ones that the
// production helper filters out.
func rawGroupNames(cfg *config.RawConfig) []string {
	out := make([]string, 0, len(cfg.ProxyGroup))
	for _, g := range cfg.ProxyGroup {
		name, _ := g["name"].(string)
		out = append(out, name)
	}
	return out
}

func hasGroup(cfg *config.RawConfig, name string) bool {
	for _, got := range groupNames(cfg) {
		if got == name {
			return true
		}
	}
	return false
}

func hasRule(cfg *config.RawConfig, want string) bool {
	for _, rule := range cfg.Rule {
		if rule == want {
			return true
		}
	}
	return false
}

// A profile that never enabled the script keeps exactly the groups and rules
// its subscription ships, even when every 分流显示选项 switch is on.
func TestPolicyGroupsStayUntouchedWithoutScript(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(sampleProfile))
	if err != nil {
		t.Fatal(err)
	}

	opts := displayOptions(map[string]bool{
		"rule\u001fAI":         true,
		"rule\u001fYouTube":    true,
		"rule\u001f手动选择":       true,
		"rule\u001f生成地区自动选择组":  false,
		"rule\u001f极简模式":       true, // would normally strip every group
		"rule\u001f屏蔽国外QUIC":   true,
		"leak\u001fDNS 走代理":    true,
		"cn\u001f中国大陆 IP 直连":   false,
		"cn\u001f中国大陆域名直连":     false,
		"cn\u001f局域网直连":        false,
		"privacy\u001f拦截 STUN": false,
		"privacy\u001f屏蔽局域网发现": false,
	})

	applyScriptDisplayOptions(raw, opts, false, false)

	names := rawGroupNames(raw)
	if len(names) != 1 || names[0] != "Old" {
		t.Fatalf("profile groups must not change without a script: %#v", names)
	}
	for _, unexpected := range []string{"AI", "YouTube", "手动选择"} {
		if hasGroup(raw, unexpected) {
			t.Fatalf("group %q must not be synthesised for a profile without a script", unexpected)
		}
	}
	if hasRule(raw, "GEOSITE,openai,AI") {
		t.Fatalf("service rules must not be injected without a script: %#v", raw.Rule)
	}
	if !hasRule(raw, "MATCH,Old") {
		t.Fatalf("the subscription's own MATCH rule must survive: %#v", raw.Rule)
	}
	// 极简模式 must not have removed the only group.
	if !hasGroup(raw, "Old") {
		t.Fatalf("minimal mode leaked into a profile without a script: %#v", names)
	}
}

// Rule level leak guards still work without a script: they never touch groups.
func TestQuicGuardAppliesWithoutScript(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(sampleProfile))
	if err != nil {
		t.Fatal(err)
	}
	opts := displayOptions(map[string]bool{"rule\u001f屏蔽国外QUIC": true})
	applyScriptDisplayOptions(raw, opts, false, false)

	if !hasRule(raw, "AND,((NETWORK,UDP),(DST-PORT,443),(NOT,((GEOSITE,cn)))),REJECT") {
		t.Fatalf("QUIC guard must still be prepended: %#v", raw.Rule)
	}
	if len(raw.ProxyGroup) != 1 {
		t.Fatalf("QUIC guard must not create groups: %#v", rawGroupNames(raw))
	}
}

// With the script enabled the switches own the policy groups again.
func TestPolicyGroupsFollowSwitchesWithScript(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(sampleProfile))
	if err != nil {
		t.Fatal(err)
	}
	opts := displayOptions(map[string]bool{
		"rule\u001fAI":      true,
		"rule\u001fYouTube": true,
		"leak\u001fDNS 走代理": true,
	})
	applyScriptDisplayOptions(raw, opts, true, false)

	if !hasGroup(raw, "AI") || !hasGroup(raw, "YouTube") {
		t.Fatalf("enabled switches must create their groups: %#v", rawGroupNames(raw))
	}
	if !hasRule(raw, "GEOSITE,openai,AI") {
		t.Fatalf("service rules must be injected: %#v", raw.Rule)
	}
}

// Turning a switch off while the script is on still removes the group.
func TestDisabledSwitchDropsGroupWithScript(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(`proxies:
  - name: node-a
    type: ss
    server: 1.1.1.1
    port: 443
    cipher: aes-128-gcm
    password: secret
proxy-groups:
  - name: AI
    type: select
    proxies: [node-a]
  - name: Old
    type: select
    proxies: [node-a]
rules:
  - RULE-SET,ai,AI
  - MATCH,Old
`))
	if err != nil {
		t.Fatal(err)
	}
	opts := displayOptions(map[string]bool{"rule\u001fAI": false})
	applyScriptDisplayOptions(raw, opts, true, false)

	if hasGroup(raw, "AI") {
		t.Fatalf("a disabled switch must drop its group: %#v", rawGroupNames(raw))
	}
	if hasRule(raw, "RULE-SET,ai,AI") {
		t.Fatalf("rules pointing at the dropped group must be retargeted: %#v", raw.Rule)
	}
}
