package config

import (
	"fmt"
	"os"
	"strings"

	"github.com/dop251/goja"
	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
)

func patchScriptOverride(cfg *config.RawConfig, _ string) error {
	path := C.Path.Resolve("script.js")
	text, err := os.ReadFile(path)
	if err != nil || strings.TrimSpace(string(text)) == "" {
		return nil
	}
	return applyScriptText(cfg, string(text))
}

func applyScriptText(cfg *config.RawConfig, src string) error {
	src = strings.TrimSpace(src)
	if src == "" {
		return nil
	}
	if hasScriptMain(src) {
		return runScriptMain(cfg, src)
	}
	return nil
}

func hasScriptMain(src string) bool {
	return strings.Contains(src, "function main") ||
		strings.Contains(src, "main =") ||
		strings.Contains(src, "main=")
}

func runScriptMain(cfg *config.RawConfig, src string) error {
	blob, err := yaml.Marshal(cfg)
	if err != nil {
		return err
	}
	var doc map[string]any
	if err = yaml.Unmarshal(blob, &doc); err != nil {
		return err
	}
	proxies, hasProxies := doc["proxies"]
	providers, hasProviders := doc["proxy-providers"]
	proxies = cloneYaml(proxies)
	providers = cloneYaml(providers)

	vm := goja.New()
	if _, err = vm.RunString(src); err != nil {
		return fmt.Errorf("script: %w", err)
	}
	fn, ok := goja.AssertFunction(vm.Get("main"))
	if !ok {
		return fmt.Errorf("script: main is not a function")
	}
	val, err := fn(goja.Undefined(), vm.ToValue(doc))
	if err != nil {
		return fmt.Errorf("script main: %w", err)
	}
	if val == nil || goja.IsUndefined(val) || goja.IsNull(val) {
		return fmt.Errorf("script main() must return the config")
	}
	exported := normalizeYAML(val.Export())
	out, ok := exported.(map[string]any)
	if !ok {
		return fmt.Errorf("script main() must return an object")
	}
	if hasProxies {
		out["proxies"] = proxies
	} else {
		delete(out, "proxies")
	}
	if hasProviders {
		out["proxy-providers"] = providers
	} else {
		delete(out, "proxy-providers")
	}
	rewritten, err := yaml.Marshal(out)
	if err != nil {
		return err
	}
	next, err := config.UnmarshalRawConfig(rewritten)
	if err != nil {
		return fmt.Errorf("script config: %w", err)
	}
	*cfg = *next
	return nil
}

func cloneYaml(v any) any {
	if v == nil {
		return nil
	}
	blob, err := yaml.Marshal(v)
	if err != nil {
		return v
	}
	var out any
	if err = yaml.Unmarshal(blob, &out); err != nil {
		return v
	}
	return out
}

func normalizeYAML(v any) any {
	switch t := v.(type) {
	case map[string]any:
		for k, val := range t {
			t[k] = normalizeYAML(val)
		}
		return t
	case []any:
		for i := range t {
			t[i] = normalizeYAML(t[i])
		}
		return t
	case float64:
		if t == float64(int64(t)) {
			return int(t)
		}
		return t
	case int64:
		return int(t)
	default:
		return v
	}
}
