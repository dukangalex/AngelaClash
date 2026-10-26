package config

import (
	"encoding/json"
	"os"
	P "path"
	"testing"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/constant"
)

const profileUUID = "6f1e1f0a-2a5a-4c3f-9c55-0d4a2f6bd0a1"

// withOptions writes a script-options.json into a temporary home directory and
// returns the profile directory the kernel would be handed.
func withOptions(t *testing.T, payload map[string]any) string {
	t.Helper()

	previous := constant.Path.HomeDir()
	home := t.TempDir()
	constant.SetHomeDir(home)
	t.Cleanup(func() { constant.SetHomeDir(previous) })

	buf, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal options: %v", err)
	}
	if err := os.WriteFile(P.Join(home, "script-options.json"), buf, 0o600); err != nil {
		t.Fatalf("write options: %v", err)
	}

	profileDir := P.Join(home, "profiles", profileUUID)
	if err := os.MkdirAll(profileDir, 0o700); err != nil {
		t.Fatalf("mkdir profile: %v", err)
	}

	return profileDir
}

func ruleOverrideOptions(override map[string]any) map[string]any {
	return map[string]any{"ruleOverrides": map[string]any{profileUUID: override}}
}

func disabledGroupsOptions(names []string) map[string]any {
	return map[string]any{"disabledGroups": map[string]any{profileUUID: names}}
}

func newConfig(rules ...string) *config.RawConfig {
	return &config.RawConfig{Rule: rules}
}

func TestPatchRuleOverrideReplacesList(t *testing.T) {
	profileDir := withOptions(t, ruleOverrideOptions(map[string]any{
		"enabled": true,
		"mode":    ModeOverride,
		"rules":   []string{"DOMAIN-SUFFIX,corp.example,DIRECT", "MATCH,PROXY"},
	}))

	cfg := newConfig("DOMAIN-SUFFIX,google.com,PROXY", "MATCH,DIRECT")
	patchRuleOverride(cfg, profileDir)

	want := []string{"DOMAIN-SUFFIX,corp.example,DIRECT", "MATCH,PROXY"}
	if len(cfg.Rule) != len(want) {
		t.Fatalf("got %d rules, want %d: %v", len(cfg.Rule), len(want), cfg.Rule)
	}
	for i := range want {
		if cfg.Rule[i] != want[i] {
			t.Fatalf("rule %d = %q, want %q", i, cfg.Rule[i], want[i])
		}
	}
}

func TestPatchRuleOverridePrependsInAddedMode(t *testing.T) {
	profileDir := withOptions(t, ruleOverrideOptions(map[string]any{
		"enabled": true,
		"mode":    ModeAdded,
		"rules":   []string{"DOMAIN-SUFFIX,corp.example,DIRECT"},
	}))

	cfg := newConfig("DOMAIN-SUFFIX,google.com,PROXY", "MATCH,DIRECT")
	patchRuleOverride(cfg, profileDir)

	if len(cfg.Rule) != 3 {
		t.Fatalf("got %d rules, want 3: %v", len(cfg.Rule), cfg.Rule)
	}
	if cfg.Rule[0] != "DOMAIN-SUFFIX,corp.example,DIRECT" {
		t.Fatalf("draft rule is not first: %v", cfg.Rule)
	}
	if cfg.Rule[2] != "MATCH,DIRECT" {
		t.Fatalf("profile MATCH was not kept last: %v", cfg.Rule)
	}
}

// Dropping MATCH in override mode would leave unmatched traffic with nowhere to
// go, so the profile's own catch-all survives.
func TestPatchRuleOverrideKeepsCatchAll(t *testing.T) {
	profileDir := withOptions(t, ruleOverrideOptions(map[string]any{
		"enabled": true,
		"mode":    ModeOverride,
		"rules":   []string{"DOMAIN-SUFFIX,corp.example,DIRECT"},
	}))

	cfg := newConfig("DOMAIN-SUFFIX,google.com,PROXY", "MATCH,DIRECT")
	patchRuleOverride(cfg, profileDir)

	if len(cfg.Rule) != 2 {
		t.Fatalf("got %d rules, want 2: %v", len(cfg.Rule), cfg.Rule)
	}
	if cfg.Rule[1] != "MATCH,DIRECT" {
		t.Fatalf("catch-all not appended: %v", cfg.Rule)
	}
}

func TestPatchRuleOverrideDisabledIsNoop(t *testing.T) {
	profileDir := withOptions(t, ruleOverrideOptions(map[string]any{
		"enabled": false,
		"mode":    ModeOverride,
		"rules":   []string{"MATCH,REJECT"},
	}))

	cfg := newConfig("MATCH,DIRECT")
	patchRuleOverride(cfg, profileDir)

	if len(cfg.Rule) != 1 || cfg.Rule[0] != "MATCH,DIRECT" {
		t.Fatalf("disabled draft changed the list: %v", cfg.Rule)
	}
}

func TestPatchRuleOverrideIgnoresOtherProfiles(t *testing.T) {
	payload := map[string]any{"ruleOverrides": map[string]any{
		"00000000-0000-0000-0000-000000000000": map[string]any{
			"enabled": true,
			"mode":    ModeOverride,
			"rules":   []string{"MATCH,REJECT"},
		},
	}}
	profileDir := withOptions(t, payload)

	cfg := newConfig("MATCH,DIRECT")
	patchRuleOverride(cfg, profileDir)

	if len(cfg.Rule) != 1 || cfg.Rule[0] != "MATCH,DIRECT" {
		t.Fatalf("other profile's draft leaked in: %v", cfg.Rule)
	}
}

func TestPatchRuleOverrideBlankRulesAreIgnored(t *testing.T) {
	profileDir := withOptions(t, ruleOverrideOptions(map[string]any{
		"enabled": true,
		"mode":    ModeAdded,
		"rules":   []string{"   ", ""},
	}))

	cfg := newConfig("MATCH,DIRECT")
	patchRuleOverride(cfg, profileDir)

	if len(cfg.Rule) != 1 || cfg.Rule[0] != "MATCH,DIRECT" {
		t.Fatalf("blank draft changed the list: %v", cfg.Rule)
	}
}

func TestPatchRuleOverrideNoOptionsFile(t *testing.T) {
	previous := constant.Path.HomeDir()
	constant.SetHomeDir(t.TempDir())
	t.Cleanup(func() { constant.SetHomeDir(previous) })

	cfg := newConfig("MATCH,DIRECT")
	patchRuleOverride(cfg, "/nonexistent/profile")

	if len(cfg.Rule) != 1 || cfg.Rule[0] != "MATCH,DIRECT" {
		t.Fatalf("missing file changed the list: %v", cfg.Rule)
	}
}

// ---------- 策略组禁用 ----------

func group(name string, members ...string) map[string]any {
	proxies := make([]any, 0, len(members))
	for _, member := range members {
		proxies = append(proxies, member)
	}

	return map[string]any{"name": name, "type": "select", "proxies": proxies}
}

func TestPatchDisabledGroupsDropsGroupAndRewritesRule(t *testing.T) {
	profileDir := withOptions(t, disabledGroupsOptions([]string{"广告拦截"}))

	cfg := newConfig(
		"DOMAIN-SUFFIX,ads.example,广告拦截",
		"IP-CIDR,1.1.1.1/32,DIRECT,no-resolve",
		"MATCH,默认代理",
	)
	cfg.ProxyGroup = []map[string]any{
		group("默认代理", "DIRECT", "广告拦截"),
		group("广告拦截", "REJECT", "DIRECT"),
	}

	if err := patchDisabledGroups(cfg, profileDir); err != nil {
		t.Fatalf("patch: %v", err)
	}

	if len(cfg.ProxyGroup) != 1 || cfg.ProxyGroup[0]["name"] != "默认代理" {
		t.Fatalf("广告拦截 should be gone, got %v", cfg.ProxyGroup)
	}
	members := cfg.ProxyGroup[0]["proxies"].([]any)
	if len(members) != 1 || members[0] != "DIRECT" {
		t.Fatalf("广告拦截 should be removed from members, got %v", members)
	}
	// The rule keeps its slot but no longer targets a missing group.
	if cfg.Rule[0] != "DOMAIN-SUFFIX,ads.example,PASS" {
		t.Fatalf("rule target not rewritten: %q", cfg.Rule[0])
	}
	if cfg.Rule[1] != "IP-CIDR,1.1.1.1/32,DIRECT,no-resolve" {
		t.Fatalf("unrelated rule changed: %q", cfg.Rule[1])
	}
}

// Switching off the only member of a nested group must take the now-empty parent
// with it, otherwise the config keeps a group with nothing to select.
func TestPatchDisabledGroupsCascades(t *testing.T) {
	profileDir := withOptions(t, disabledGroupsOptions([]string{"落地"}))

	cfg := newConfig("MATCH,自选")
	cfg.ProxyGroup = []map[string]any{
		group("自选", "自动"),
		group("自动", "落地"),
		group("落地", "DIRECT"),
	}

	if err := patchDisabledGroups(cfg, profileDir); err != nil {
		t.Fatalf("patch: %v", err)
	}

	if len(cfg.ProxyGroup) != 0 {
		t.Fatalf("all three groups should be gone, got %v", cfg.ProxyGroup)
	}
	// MATCH pointing at a removed group falls back to PASS.
	if cfg.Rule[0] != "MATCH,PASS" {
		t.Fatalf("MATCH not rewritten: %q", cfg.Rule[0])
	}
}

// A group fed by providers stays even when its static members are gone.
func TestPatchDisabledGroupsKeepsDynamicGroup(t *testing.T) {
	profileDir := withOptions(t, disabledGroupsOptions([]string{"广告拦截"}))

	cfg := newConfig("MATCH,自选")
	cfg.ProxyGroup = []map[string]any{
		{"name": "自选", "type": "select", "proxies": []any{"广告拦截"}, "include-all": true},
	}

	if err := patchDisabledGroups(cfg, profileDir); err != nil {
		t.Fatalf("patch: %v", err)
	}

	if len(cfg.ProxyGroup) != 1 {
		t.Fatalf("dynamic group should survive: %v", cfg.ProxyGroup)
	}
}

func TestPatchDisabledGroupsNoOptionsIsNoop(t *testing.T) {
	previous := constant.Path.HomeDir()
	constant.SetHomeDir(t.TempDir())
	t.Cleanup(func() { constant.SetHomeDir(previous) })

	cfg := newConfig("MATCH,DIRECT")
	cfg.ProxyGroup = []map[string]any{group("默认代理", "DIRECT")}

	if err := patchDisabledGroups(cfg, "/nonexistent/profile"); err != nil {
		t.Fatalf("patch: %v", err)
	}
	if len(cfg.ProxyGroup) != 1 || cfg.Rule[0] != "MATCH,DIRECT" {
		t.Fatalf("noop changed the config: %v %v", cfg.ProxyGroup, cfg.Rule)
	}
}

func TestSplitRuleTarget(t *testing.T) {
	cases := []struct {
		rule, head, target string
		ok                 bool
	}{
		{"DOMAIN,a.com,PROXY", "DOMAIN,a.com", "PROXY", true},
		{"MATCH,DIRECT", "MATCH", "DIRECT", true},
		{"IP-CIDR,1.1.1.1/32,DIRECT,no-resolve", "IP-CIDR,1.1.1.1/32", "DIRECT", true},
		{"GEOIP,CN,DIRECT", "GEOIP,CN", "DIRECT", true},
		{"NO-TARGET", "", "", false},
		{"", "", "", false},
	}

	for _, c := range cases {
		head, target, ok := splitRuleTarget(c.rule)
		if ok != c.ok || head != c.head || target != c.target {
			t.Fatalf("splitRuleTarget(%q) = (%q,%q,%v), want (%q,%q,%v)",
				c.rule, head, target, ok, c.head, c.target, c.ok)
		}
	}
}
