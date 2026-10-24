package config

import (
	"encoding/json"
	"fmt"
	"os"
	P "path"
	"regexp"
	"strings"

	"github.com/dop251/goja"
	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

func patchScriptOverride(cfg *config.RawConfig, profileDir string) error {
	if _, err := os.Stat(P.Join(profileDir, "script.on")); err != nil {
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

func applyScriptText(cfg *config.RawConfig, src string) error {
	opts, _ := readScriptOptions()
	groups := splitScriptOptions(opts.Values)
	return applyScriptTextWithOptions(cfg, src, groups.rule)
}

func applyScriptTextWithOptions(cfg *config.RawConfig, src string, ruleOpts map[string]bool) error {
	src = strings.TrimSpace(src)
	if src == "" || !hasScriptMain(src) {
		return nil
	}
	return runScriptMain(cfg, src, ruleOpts)
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

var ruleOptionsDeclRe = regexp.MustCompile(`(?:const|let|var)\s+ruleOptionsEnable\s*=`)

// patchJSRuleOptionsInSource rewrites boolean literals inside the
// `ruleOptionsEnable = { ... }` object literal before the script is evaluated,
// ensuring top-level `const activeRuleOptions = ruleOptionsEnable` or spread
// copies immediately observe the user's switch states.
func patchJSRuleOptionsInSource(src string, ruleOpts map[string]bool) string {
	if len(ruleOpts) == 0 {
		return src
	}
	loc := ruleOptionsDeclRe.FindStringIndex(src)
	if loc == nil {
		return src
	}
	i := loc[1]
	for i < len(src) && (src[i] == ' ' || src[i] == '\t' || src[i] == '\r' || src[i] == '\n') {
		i++
	}
	if i >= len(src) || src[i] != '{' {
		return src
	}
	end := matchJSBrace(src, i)
	if end <= i {
		return src
	}
	body := src[i+1 : end]
	for k, v := range ruleOpts {
		if k == "" {
			continue
		}
		if k == "链式代理" && v {
			// Native chain proxy is handled by patchChain; avoid triggering
			// script errors when customizeProxies is empty.
			continue
		}
		valStr := "false"
		if v {
			valStr = "true"
		}
		escaped := regexp.QuoteMeta(k)
		re := regexp.MustCompile(`((?:["']` + escaped + `["']|` + escaped + `)\s*:\s*)(?:true|false)`)
		body = re.ReplaceAllString(body, "${1}"+valStr)
	}
	return src[:i+1] + body + src[end:]
}

func matchJSBrace(src string, open int) int {
	depth := 0
	for i := open; i < len(src); i++ {
		c := src[i]
		if c == '"' || c == '\'' || c == '`' {
			quote := c
			i++
			for i < len(src) {
				if src[i] == '\\' {
					i += 2
					continue
				}
				if src[i] == quote {
					break
				}
				i++
			}
			continue
		}
		if c == '/' && i+1 < len(src) && src[i+1] == '/' {
			for i < len(src) && src[i] != '\n' {
				i++
			}
			continue
		}
		if c == '/' && i+1 < len(src) && src[i+1] == '*' {
			i += 2
			for i+1 < len(src) && !(src[i] == '*' && src[i+1] == '/') {
				i++
			}
			i++
			continue
		}
		if c == '{' {
			depth++
		} else if c == '}' {
			depth--
			if depth == 0 {
				return i
			}
		}
	}
	return -1
}

func runScriptMain(cfg *config.RawConfig, src string, ruleOpts map[string]bool) error {
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

	patchedSrc := patchJSRuleOptionsInSource(src, ruleOpts)

	vm := goja.New()
	if _, err = vm.RunString(patchedSrc); err != nil {
		return fmt.Errorf("script: %w", err)
	}
	fn, ok := goja.AssertFunction(vm.Get("main"))
	if !ok {
		log.Warnln("script has no main(), skipped")
		return nil
	}
	ctxVal := injectScriptRuleOptions(vm, ruleOpts)
	var val goja.Value
	if goja.IsUndefined(ctxVal) {
		val, err = fn(goja.Undefined(), vm.ToValue(doc))
	} else {
		val, err = fn(goja.Undefined(), vm.ToValue(doc), ctxVal)
	}
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
	repairRawConfig(next)
	*cfg = *next
	return nil
}

func injectScriptRuleOptions(vm *goja.Runtime, ruleOpts map[string]bool) goja.Value {
	if len(ruleOpts) == 0 {
		return goja.Undefined()
	}
	rawJSON, err := json.Marshal(ruleOpts)
	if err != nil {
		return goja.Undefined()
	}
	_ = vm.Set("__angelaRuleOptionsJSON", string(rawJSON))
	res, err := vm.RunString(`
(function() {
	var src = {};
	try {
		src = JSON.parse(__angelaRuleOptionsJSON) || {};
	} catch (e) {
		src = {};
	}
	if (src['链式代理'] === true) {
		if (typeof customizeProxies === 'undefined' || !Array.isArray(customizeProxies) || customizeProxies.length === 0) {
			delete src['链式代理'];
		}
	}
	var filtered = {};
	if (typeof ruleOptionsEnable === 'object' && ruleOptionsEnable !== null) {
		for (var k in src) {
			if (Object.prototype.hasOwnProperty.call(src, k) && typeof src[k] === 'boolean') {
				if (Object.prototype.hasOwnProperty.call(ruleOptionsEnable, k)) {
					try { ruleOptionsEnable[k] = src[k]; } catch (e) {}
					filtered[k] = src[k];
				}
			}
		}
	} else {
		for (var k in src) {
			if (Object.prototype.hasOwnProperty.call(src, k) && typeof src[k] === 'boolean') {
				filtered[k] = src[k];
			}
		}
	}
	if (typeof activeRuleOptions === 'object' && activeRuleOptions !== null) {
		for (var k in filtered) {
			if (Object.prototype.hasOwnProperty.call(filtered, k)) {
				try { activeRuleOptions[k] = filtered[k]; } catch (e) {}
			}
		}
	}
	return { customOptions: filtered };
})()
`)
	if err != nil {
		log.Warnln("inject script rule options: %s", err.Error())
		return goja.Undefined()
	}
	return res
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
