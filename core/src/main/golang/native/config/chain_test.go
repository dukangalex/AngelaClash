package config

import (
	"os"
	"path"
	"testing"

	"github.com/metacubex/mihomo/config"
)

func TestChainRewritesRulesAndDialer(t *testing.T) {
	cfg := &config.RawConfig{
		Proxy: []map[string]any{
			{"name": "entry-node", "type": "socks5", "server": "1.1.1.1", "port": 1, "dialer-proxy": "old"},
			{"name": "landing-node", "type": "socks5", "server": "8.8.8.8", "port": 1},
		},
		ProxyGroup: []map[string]any{
			{"name": "入口组", "type": "select", "proxies": []any{"entry-node"}},
			{"name": "落地组", "type": "select", "proxies": []any{"landing-node"}},
		},
		Rule: []string{
			"GEOIP,CN,DIRECT",
			"GEOIP,private,DIRECT,no-resolve",
			"DOMAIN-SUFFIX,google.com,入口组",
			"MATCH,入口组",
		},
	}
	err := patchChain(cfg, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	// no chain file: unchanged
	if cfg.Rule[2] != "DOMAIN-SUFFIX,google.com,入口组" {
		t.Fatalf("rules changed without a chain file: %v", cfg.Rule)
	}

	dir := t.TempDir()
	if err := osWrite(dir, chainFileName, `{"entry":"入口组","landingProfile":"","landing":"落地组"}`); err != nil {
		t.Fatal(err)
	}
	if err := patchChain(cfg, dir); err != nil {
		t.Fatal(err)
	}
	if got := cfg.Proxy[0]["dialer-proxy"]; got != nil {
		t.Fatalf("entry dialer = %v, want cleared", got)
	}
	if got := cfg.Proxy[1]["dialer-proxy"]; got != "入口组" {
		t.Fatalf("landing dialer = %v", got)
	}
	if cfg.Rule[0] != "GEOIP,CN,DIRECT" {
		t.Fatalf("cn rule = %s", cfg.Rule[0])
	}
	if cfg.Rule[1] != "GEOIP,private,DIRECT,no-resolve" {
		t.Fatalf("private rule = %s", cfg.Rule[1])
	}
	if cfg.Rule[2] != "DOMAIN-SUFFIX,google.com,落地组" || cfg.Rule[3] != "MATCH,落地组" {
		t.Fatalf("rules = %v", cfg.Rule)
	}
}

func TestChainRejectsSameNode(t *testing.T) {
	cfg := &config.RawConfig{
		Proxy: []map[string]any{
			{"name": "only", "type": "socks5"},
		},
	}
	dir := t.TempDir()
	if err := osWrite(dir, chainFileName, `{"entry":"only","landing":"only"}`); err != nil {
		t.Fatal(err)
	}
	if err := patchChain(cfg, dir); err == nil {
		t.Fatal("expected error")
	}
}

func TestChainMergesOtherProfile(t *testing.T) {
	cfg := &config.RawConfig{
		Proxy: []map[string]any{{"name": "entry-node", "type": "socks5"}},
		ProxyGroup: []map[string]any{
			{"name": "入口组", "type": "select", "proxies": []any{"entry-node"}},
		},
		Rule: []string{"MATCH,入口组"},
	}
	landing := &config.RawConfig{
		Proxy: []map[string]any{{"name": "us", "type": "socks5"}},
		ProxyGroup: []map[string]any{
			{"name": "落地组", "type": "select", "proxies": []any{"us"}},
		},
	}
	merged := mergeLanding(cfg, landing, "abc", "落地组")
	if merged != "chain-landing-abc-落地组" {
		t.Fatalf("merged name = %s", merged)
	}
	if mapString(cfg.Proxy[1], "name") != "chain-landing-abc-us" {
		t.Fatalf("imported proxy = %v", cfg.Proxy[1])
	}
}

func TestChainStripsDirectMember(t *testing.T) {
	cfg := &config.RawConfig{
		Proxy: []map[string]any{
			{"name": "entry-node", "type": "socks5"},
			{"name": "landing-node", "type": "socks5"},
		},
		ProxyGroup: []map[string]any{
			{"name": "入口组", "type": "select", "proxies": []any{"entry-node", "DIRECT"}},
			{"name": "落地组", "type": "select", "proxies": []any{"DIRECT", "landing-node"}},
		},
		Rule: []string{"MATCH,DIRECT"},
	}
	dir := t.TempDir()
	if err := osWrite(dir, chainFileName, `{"entry":"入口组","landing":"落地组"}`); err != nil {
		t.Fatal(err)
	}
	if err := patchChain(cfg, dir); err != nil {
		t.Fatal(err)
	}
	if got := mapStrings(cfg.ProxyGroup[1], "proxies"); len(got) != 1 || got[0] != "landing-node" {
		t.Fatalf("landing members = %v", got)
	}
	if cfg.Rule[0] != "MATCH,落地组" {
		t.Fatalf("rule = %s", cfg.Rule[0])
	}
}

func TestChainRejectsDirectOnlyLanding(t *testing.T) {
	cfg := &config.RawConfig{
		Proxy: []map[string]any{{"name": "entry-node", "type": "socks5"}},
		ProxyGroup: []map[string]any{
			{"name": "入口组", "type": "select", "proxies": []any{"entry-node"}},
			{"name": "落地组", "type": "select", "proxies": []any{"DIRECT"}},
		},
	}
	dir := t.TempDir()
	if err := osWrite(dir, chainFileName, `{"entry":"入口组","landing":"落地组"}`); err != nil {
		t.Fatal(err)
	}
	if err := patchChain(cfg, dir); err == nil {
		t.Fatal("expected error")
	}
}

func TestChainRejectsInvalidLandingProfilePath(t *testing.T) {
	baseDir := t.TempDir()
	profileDir := path.Join(baseDir, "profiles", "current")
	if err := os.MkdirAll(profileDir, 0o700); err != nil {
		t.Fatal(err)
	}
	outsideDir := path.Join(baseDir, "outside-profile")
	if err := os.MkdirAll(outsideDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path.Join(outsideDir, "config.yaml"), []byte(`proxies:
  - name: landing-node
    type: socks5
    server: 8.8.8.8
    port: 443
`), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg := &config.RawConfig{
		Proxy: []map[string]any{{"name": "entry-node", "type": "socks5"}},
		Rule:  []string{"MATCH,entry-node"},
	}
	if err := osWrite(profileDir, chainFileName, `{"entry":"entry-node","landingProfile":"../outside-profile","landing":"landing-node"}`); err != nil {
		t.Fatal(err)
	}
	if err := patchChain(cfg, profileDir); err == nil {
		t.Fatal("expected invalid landing profile identifier to be rejected")
	}
}


func TestIsProfileID(t *testing.T) {
	tests := []struct {
		value string
		want  bool
	}{
		{"11111111-1111-4111-8111-111111111111", true},
		{"ABCDEF12-3456-7890-abcd-ef1234567890", true},
		{"", false},
		{"../outside-profile", false},
		{"11111111/1111/4111/8111/111111111111", false},
		{"11111111111141118111111111111111", false},
		{"11111111-1111-4111-8111-11111111111g", false},
	}
	for _, tt := range tests {
		t.Run(tt.value, func(t *testing.T) {
			if got := isProfileID(tt.value); got != tt.want {
				t.Fatalf("isProfileID(%q) = %v, want %v", tt.value, got, tt.want)
			}
		})
	}
}

func TestChainLoadsLandingFromValidOtherProfile(t *testing.T) {
	baseDir := t.TempDir()
	currentID := "11111111-1111-4111-8111-111111111111"
	landingID := "22222222-2222-4222-8222-222222222222"
	profilesDir := path.Join(baseDir, "profiles")
	profileDir := path.Join(profilesDir, currentID)
	landingDir := path.Join(profilesDir, landingID)
	for _, dir := range []string{profileDir, landingDir} {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(path.Join(landingDir, "config.yaml"), []byte(`proxies:
  - name: landing-node
    type: socks5
    server: 8.8.8.8
    port: 443
proxy-groups:
  - name: landing-group
    type: select
    proxies:
      - landing-node
rules:
  - MATCH,landing-group
`), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg := &config.RawConfig{
		Proxy: []map[string]any{{"name": "entry-node", "type": "socks5", "server": "1.1.1.1", "port": 443}},
		ProxyGroup: []map[string]any{
			{"name": "entry-group", "type": "select", "proxies": []any{"entry-node"}},
		},
		Rule: []string{"MATCH,entry-group"},
	}
	if err := osWrite(profileDir, chainFileName, `{"entry":"entry-group","landingProfile":"22222222-2222-4222-8222-222222222222","landing":"landing-group"}`); err != nil {
		t.Fatal(err)
	}
	if err := patchChain(cfg, profileDir); err != nil {
		t.Fatalf("valid cross-profile chain was rejected: %v", err)
	}
	renamedLanding := "chain-landing-" + landingID + "-landing-node"
	if got := findProxy(cfg, renamedLanding)["dialer-proxy"]; got != "entry-group" {
		t.Fatalf("landing dialer = %v, want entry-group", got)
	}
	if cfg.Rule[0] != "MATCH,chain-landing-"+landingID+"-landing-group" {
		t.Fatalf("rule = %s", cfg.Rule[0])
	}
}

func osWrite(dir, name, body string) error {
	return os.WriteFile(path.Join(dir, name), []byte(body), 0o600)
}
