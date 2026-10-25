package config

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/metacubex/mihomo/config"
)

const gateProfile = `proxies:
  - {name: hk-01, type: ss, server: 1.2.3.4, port: 443, cipher: aes-128-gcm, password: p}
  - {name: us-01, type: ss, server: 5.6.7.8, port: 443, cipher: aes-128-gcm, password: p}
proxy-groups:
  - {name: 默认代理, type: select, proxies: [hk-01, us-01]}
  - {name: 出口优选, type: url-test, proxies: [hk-01, us-01], url: "http://cp.cloudflare.com", interval: 300}
  - {name: 漏网之鱼, type: select, proxies: [默认代理, 出口优选]}
rules:
  - GEOIP,CN,默认代理
  - MATCH,漏网之鱼
`

func TestProfileGateEndToEnd(t *testing.T) {
	dir := t.TempDir()
	optPath := filepath.Join(dir, "script-options.json")
	// every service switch on: without the gate this would create AI/YouTube/... groups
	os.WriteFile(optPath, []byte(`{"enabled":true,"scriptEnabled":true,"values":{
		"rule\u001fAI":true,"rule\u001fYouTube":true,"rule\u001fGoogle":true,
		"rule\u001fNetflix":true,"rule\u001f手动选择":true,"rule\u001f自动选择":true}}`), 0o644)

	profileA := filepath.Join(dir, "profile-a")
	profileB := filepath.Join(dir, "profile-b")
	os.MkdirAll(profileA, 0o755)
	os.MkdirAll(profileB, 0o755)
	os.WriteFile(filepath.Join(profileB, "script.on"), []byte("1"), 0o644)

	opts, ok := readOptionsFrom(optPath)
	if !ok {
		t.Fatal("options must parse")
	}

	run := func(dir string) []string {
		raw, err := config.UnmarshalRawConfig([]byte(gateProfile))
		if err != nil {
			t.Fatal(err)
		}
		applyScriptDisplayOptions(raw, opts, profileScriptEnabled(dir), false)
		out := []string{}
		for _, g := range raw.ProxyGroup {
			out = append(out, g["name"].(string))
		}
		return out
	}

	a := run(profileA)
	b := run(profileB)
	t.Logf("script OFF -> %v", a)
	t.Logf("script ON  -> %v", b)
	if len(a) != 3 {
		t.Fatalf("profile without script must keep exactly its 3 groups, got %v", a)
	}
	if len(b) <= len(a) {
		t.Fatalf("profile with script must gain the switch groups, got %v", b)
	}
}
