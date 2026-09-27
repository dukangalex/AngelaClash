package config

import (
	"fmt"
	"os"
	"strings"

	"github.com/dop251/goja"
	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

func patchScriptOverride(cfg *config.RawConfig, _ string) error {
	if !scriptOverrideEnabled() {
		return nil
	}
	path := C.Path.Resolve("script.js")
	text, err := os.ReadFile(path)
	if err != nil || strings.TrimSpace(string(text)) == "" {
		return nil
	}
	if err = applyScriptText(cfg, string(text)); err != nil {
		return err
	}
	return nil
}

func scriptOverrideEnabled() bool {
	opts, ok := readScriptOptions()
	if !ok || opts.ScriptEnabled == nil {
		return true
	}
	return *opts.ScriptEnabled
}

func applyScriptText(cfg *config.RawConfig, src string) error {
	src = strings.TrimSpace(src)
	if src == "" || !hasScriptMain(src) {
		return nil
	}
	return runScriptMain(cfg, src)
}

func hasScriptMain(src string) bool {
	stripped := stripJSComments(src)
	return strings.Contains(stripped, "function main") ||
		strings.Contains(stripped, "main=function") ||
		strings.Contains(stripped, "main = function") ||
		strings.Contains(stripped, "main=async") ||
		strings.Contains(stripped, "main = async")
}

func stripJSComments(src string) string {
	var b strings.Builder
	b.Grow(len(src))
	i := 0
	for i < len(src) {
		if src[i] == '"' || src[i] == '\'' || src[i] == '`' {
			quote := src[i]
			b.WriteByte(src[i])
			i++
			for i < len(src) {
				if src[i] == '\\' && i+1 < len(src) {
					b.WriteByte(src[i])
					b.WriteByte(src[i+1])
					i += 2
					continue
				}
				b.WriteByte(src[i])
				if src[i] == quote {
					i++
					break
				}
				i++
			}
			continue
		}
		if i+1 < len(src) && src[i] == '/' && src[i+1] == '/' {
			for i < len(src) && src[i] != '\n' {
				i++
			}
			continue
		}
		if i+1 < len(src) && src[i] == '/' && src[i+1] == '*' {
			i += 2
			for i+1 < len(src) && !(src[i] == '*' && src[i+1] == '/') {
				i++
			}
			if i+1 < len(src) {
				i += 2
			}
			continue
		}
		b.WriteByte(src[i])
		i++
	}
	return b.String()
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
		log.Warnln("script has no main(), skipped")
		return nil
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
	// A script may rename nodes and add DIRECT proxies. Keep that list when it
	// returned one. An empty list means "do not touch nodes".
	if isEmptyList(out["proxies"]) {
		if hasProxies {
			out["proxies"] = proxies
		} else {
			delete(out, "proxies")
		}
	}
	if isEmptyMap(out["proxy-providers"]) {
		if hasProviders {
			out["proxy-providers"] = providers
		} else {
			delete(out, "proxy-providers")
		}
	}
	repairDoc(out)
	rewritten, err := yaml.Marshal(out)
	if err != nil {
		return err
	}
	next, err := config.UnmarshalRawConfig(rewritten)
	if err != nil && strings.Contains(err.Error(), "not found") {
		if fixed, ferr := repairConfigYAML(rewritten); ferr == nil {
			next, err = config.UnmarshalRawConfig(fixed)
		}
	}
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
