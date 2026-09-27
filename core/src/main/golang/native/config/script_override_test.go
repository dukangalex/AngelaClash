package config

import (
	"strings"
	"testing"

	"github.com/metacubex/mihomo/config"
)

func TestScriptKeepsNodes(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(`
proxies:
  - name: node-a
    type: ss
    server: 1.1.1.1
    port: 443
    cipher: aes-128-gcm
    password: secret
proxy-groups:
  - name: Old
    type: select
    proxies: [node-a]
rules:
  - MATCH,Old
`))
	if err != nil {
		t.Fatal(err)
	}
	script := `
function main(config) {
  config["proxy-groups"] = [{name: "PROXY", type: "select", proxies: ["node-a"]}];
  config.rules = ["MATCH,PROXY"];
  config.proxies = [];
  return config;
}
`
	if err = applyScriptText(raw, script); err != nil {
		t.Fatal(err)
	}
	if len(raw.Proxy) != 1 || raw.Proxy[0]["name"] != "node-a" {
		t.Fatalf("nodes were overwritten: %#v", raw.Proxy)
	}
	if len(raw.ProxyGroup) != 1 || raw.ProxyGroup[0]["name"] != "PROXY" {
		t.Fatalf("groups were not overwritten: %#v", raw.ProxyGroup)
	}
	joined := strings.Join(raw.Rule, "\n")
	if !strings.Contains(joined, "MATCH,PROXY") {
		t.Fatalf("rules were not overwritten: %s", joined)
	}
}
