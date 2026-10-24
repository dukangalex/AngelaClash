package config

import (
	"strings"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
)

// repairConfigYAML drops proxy-group members that are not real proxies or
// groups, so a subscription can be imported instead of failing closed.
func repairConfigYAML(buf []byte) ([]byte, error) {
	var doc map[string]any
	if err := yaml.Unmarshal(buf, &doc); err != nil {
		return nil, err
	}
	repairDoc(doc)
	return yaml.Marshal(doc)
}

func builtinOutbounds() map[string]bool {
	return map[string]bool{
		"DIRECT": true, "REJECT": true, "REJECT-DROP": true,
		"PASS": true, "COMPATIBLE": true, "GLOBAL": true,
	}
}

func repairDoc(doc map[string]any) {
	if doc == nil {
		return
	}
	names := builtinOutbounds()
	if list, ok := asSlice(doc["proxies"]); ok {
		for _, item := range list {
			m, ok := asMap(item)
			if !ok {
				continue
			}
			if name, _ := m["name"].(string); name != "" {
				names[name] = true
			}
		}
	}
	groups, ok := asSlice(doc["proxy-groups"])
	if !ok {
		return
	}
	for _, item := range groups {
		m, ok := asMap(item)
		if !ok {
			continue
		}
		if name, _ := m["name"].(string); name != "" {
			names[name] = true
		}
	}
	for _, item := range groups {
		m, ok := asMap(item)
		if !ok {
			continue
		}
		repairGroupMap(m, names)
	}
}

// repairRawConfig ensures all proxy-groups and rules in a RawConfig only
// reference existing proxies/groups after script and display-option patches.
func repairRawConfig(cfg *config.RawConfig) {
	if cfg == nil {
		return
	}
	names := builtinOutbounds()
	for _, m := range cfg.Proxy {
		if name, _ := m["name"].(string); name != "" {
			names[name] = true
		}
	}
	for _, m := range cfg.ProxyGroup {
		if name, _ := m["name"].(string); name != "" {
			names[name] = true
		}
	}
	for _, m := range cfg.ProxyGroup {
		repairGroupMap(m, names)
	}
	fallback := fallbackGroup(cfg, nil)
	if len(cfg.Rule) > 0 {
		repairedRules := make([]string, 0, len(cfg.Rule))
		for _, rule := range cfg.Rule {
			repairedRules = append(repairedRules, retargetMissingRule(rule, names, fallback))
		}
		cfg.Rule = repairedRules
	}
}

func repairGroupMap(m map[string]any, names map[string]bool) {
	groupName, _ := m["name"].(string)
	filtered := make([]any, 0)
	validSet := map[string]bool{}
	if list, ok := asSlice(m["proxies"]); ok {
		for _, proxy := range list {
			name, _ := proxy.(string)
			if name != "" && name != groupName && names[name] && !validSet[name] {
				filtered = append(filtered, name)
				validSet[name] = true
			}
		}
	}
	useCount := 0
	if list, ok := asSlice(m["use"]); ok {
		useCount = len(list)
	}
	includeAll, _ := m["include-all"].(bool)
	if len(filtered) == 0 && useCount == 0 && !includeAll {
		if groupName != "默认代理" && names["默认代理"] {
			filtered = []any{"默认代理"}
			validSet["默认代理"] = true
		} else {
			m["include-all"] = true
			filtered = []any{"DIRECT"}
			validSet["DIRECT"] = true
		}
	}
	if defSel, _ := m["default-selected"].(string); defSel != "" {
		if !validSet[defSel] && !names[defSel] {
			delete(m, "default-selected")
		}
	}
	if _, exists := m["proxies"]; exists || len(filtered) > 0 {
		m["proxies"] = filtered
	}
}

func retargetMissingRule(rule string, names map[string]bool, fallback string) string {
	trimmed := strings.TrimSpace(rule)
	if trimmed == "" || fallback == "" {
		return rule
	}
	upper := strings.ToUpper(trimmed)
	if strings.HasPrefix(upper, "MATCH,") {
		target := strings.TrimSpace(trimmed[len("MATCH,"):])
		if target != "" && !names[target] {
			return "MATCH," + fallback
		}
		return rule
	}
	if strings.HasPrefix(upper, "AND,") || strings.HasPrefix(upper, "OR,") || strings.HasPrefix(upper, "NOT,") {
		lastComma := strings.LastIndex(trimmed, ",")
		if lastComma > 0 {
			target := strings.TrimSpace(trimmed[lastComma+1:])
			if target != "" && !names[target] {
				return trimmed[:lastComma+1] + fallback
			}
		}
		return rule
	}
	parts := strings.Split(trimmed, ",")
	if len(parts) >= 3 {
		target := strings.TrimSpace(parts[2])
		if target != "" && !names[target] {
			parts[2] = fallback
			return strings.Join(parts, ",")
		}
	}
	return rule
}

func asSlice(v any) ([]any, bool) {
	switch t := v.(type) {
	case []any:
		return t, true
	case []string:
		out := make([]any, len(t))
		for i, item := range t {
			out[i] = item
		}
		return out, true
	default:
		return nil, false
	}
}

func asMap(v any) (map[string]any, bool) {
	m, ok := v.(map[string]any)
	return m, ok
}

func isEmptyList(v any) bool {
	if v == nil {
		return true
	}
	list, ok := asSlice(v)
	return !ok || len(list) == 0
}

func isEmptyMap(v any) bool {
	if v == nil {
		return true
	}
	m, ok := asMap(v)
	return !ok || len(m) == 0
}

