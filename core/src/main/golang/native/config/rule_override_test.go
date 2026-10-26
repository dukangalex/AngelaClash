package config

import (
	"os"
	P "path"
	"testing"

	"github.com/metacubex/mihomo/config"
	constant "github.com/metacubex/mihomo/constant"
)

// script-options.json is read from C.Path.Resolve("script-options.json"), which
// points at the app's clash directory. Tests redirect C.Path to a temp dir.
func withOptionFile(t *testing.T, body string) string {
	t.Helper()

	dir := t.TempDir()
	if err := os.WriteFile(P.Join(dir, "script-options.json"), []byte(body), 0o600); err != nil {
		t.Fatalf("write options: %v", err)
	}

	previous := constant.Path.HomeDir()
	constant.SetHomeDir(dir)

	t.Cleanup(func() { constant.SetHomeDir(previous) })

	return dir
}

func TestPatchRuleOverridesDropsDisabledRules(t *testing.T) {
	dir := withOptionFile(t, `{"disabledRules":{"11111111-2222-3333-4444-555555555555":["DOMAIN-SUFFIX,ads.example,REJECT"]}}`)

	profileDir := P.Join(dir, "imported", "11111111-2222-3333-4444-555555555555")
	cfg := &config.RawConfig{
		Rule: []string{
			"DOMAIN-SUFFIX,ads.example,REJECT",
			"DOMAIN-SUFFIX,corp.example,DIRECT",
			"MATCH,PROXY",
		},
	}

	if err := patchRuleOverrides(cfg, profileDir); err != nil {
		t.Fatalf("patch: %v", err)
	}

	want := []string{"DOMAIN-SUFFIX,corp.example,DIRECT", "MATCH,PROXY"}
	if len(cfg.Rule) != len(want) {
		t.Fatalf("rules = %v, want %v", cfg.Rule, want)
	}
	for i := range want {
		if cfg.Rule[i] != want[i] {
			t.Fatalf("rules = %v, want %v", cfg.Rule, want)
		}
	}
}

func TestPatchRuleOverridesIgnoresOtherProfiles(t *testing.T) {
	dir := withOptionFile(t, `{"disabledRules":{"11111111-2222-3333-4444-555555555555":["MATCH,PROXY"]}}`)

	profileDir := P.Join(dir, "imported", "99999999-2222-3333-4444-555555555555")
	rules := []string{"DOMAIN-SUFFIX,corp.example,DIRECT", "MATCH,PROXY"}
	cfg := &config.RawConfig{Rule: append([]string(nil), rules...)}

	if err := patchRuleOverrides(cfg, profileDir); err != nil {
		t.Fatalf("patch: %v", err)
	}

	if len(cfg.Rule) != len(rules) {
		t.Fatalf("another profile's selection leaked in: %v", cfg.Rule)
	}
}

// Disabling every rule would leave a runtime config that rejects all traffic.
// The patcher must refuse instead of silently dropping the list.
func TestPatchRuleOverridesKeepsEverythingWhenAllDisabled(t *testing.T) {
	dir := withOptionFile(t, `{"disabledRules":{"11111111-2222-3333-4444-555555555555":["MATCH,PROXY"]}}`)

	profileDir := P.Join(dir, "imported", "11111111-2222-3333-4444-555555555555")
	cfg := &config.RawConfig{Rule: []string{"MATCH,PROXY"}}

	if err := patchRuleOverrides(cfg, profileDir); err != nil {
		t.Fatalf("patch: %v", err)
	}

	if len(cfg.Rule) != 1 || cfg.Rule[0] != "MATCH,PROXY" {
		t.Fatalf("rules = %v, want the original MATCH kept", cfg.Rule)
	}
}

func TestPatchRuleOverridesNoopWithoutOptionsFile(t *testing.T) {
	dir := t.TempDir()
	previous := constant.Path.HomeDir()
	constant.SetHomeDir(dir)
	t.Cleanup(func() { constant.SetHomeDir(previous) })

	profileDir := P.Join(dir, "imported", "11111111-2222-3333-4444-555555555555")
	cfg := &config.RawConfig{Rule: []string{"MATCH,PROXY"}}

	if err := patchRuleOverrides(cfg, profileDir); err != nil {
		t.Fatalf("patch: %v", err)
	}

	if len(cfg.Rule) != 1 {
		t.Fatalf("rules = %v, want untouched", cfg.Rule)
	}
}
