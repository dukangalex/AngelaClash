package config

import (
	"testing"

	"github.com/metacubex/mihomo/config"
)

func TestScriptKeepsNodesWhenCleared(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(sampleProfile))
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
}

func TestCommentOnlyScriptIsSkipped(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(sampleProfile))
	if err != nil {
		t.Fatal(err)
	}
	script := "// function main(config) { return config }\nconst ruleOptionsEnable = { AI: true };\n"
	if err = applyScriptText(raw, script); err != nil {
		t.Fatal(err)
	}
	if raw.ProxyGroup[0]["name"] != "Old" {
		t.Fatalf("comment was treated as a script: %#v", raw.ProxyGroup)
	}
}

func TestRepairDropsMissingProxy(t *testing.T) {
	fixed, err := repairConfigYAML([]byte(`
proxies:
  - name: node-a
    type: ss
    server: 1.1.1.1
    port: 443
    cipher: aes-128-gcm
    password: secret
proxy-groups:
  - name: 美国-自动选择
    type: select
    proxies:
      - "missing-node"
      - node-a
  - name: 直连
    type: select
    proxies:
      - "gone"
`))
	if err != nil {
		t.Fatal(err)
	}
	raw, err := config.UnmarshalRawConfig(fixed)
	if err != nil {
		t.Fatal(err)
	}
	if got := raw.ProxyGroup[0]["proxies"]; !containsName(got, "node-a") || containsName(got, "missing-node") {
		t.Fatalf("group was not repaired: %#v", got)
	}
}

func TestScriptMayRenameNodes(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(sampleProfile))
	if err != nil {
		t.Fatal(err)
	}
	script := `
function main(config) {
  config.proxies = [{name: "renamed", type: "ss", server: "1.1.1.1", port: 443, cipher: "aes-128-gcm", password: "secret"}];
  config["proxy-groups"] = [{name: "PROXY", type: "select", proxies: ["renamed"]}];
  config.rules = ["MATCH,PROXY"];
  return config;
}
`
	if err = applyScriptText(raw, script); err != nil {
		t.Fatal(err)
	}
	if len(raw.Proxy) != 1 || raw.Proxy[0]["name"] != "renamed" {
		t.Fatalf("renamed nodes were discarded: %#v", raw.Proxy)
	}
}

func containsName(v any, name string) bool {
	switch list := v.(type) {
	case []string:
		for _, item := range list {
			if item == name {
				return true
			}
		}
	case []any:
		for _, item := range list {
			if item == name {
				return true
			}
		}
	}
	return false
}

const sampleProfile = `
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
`
