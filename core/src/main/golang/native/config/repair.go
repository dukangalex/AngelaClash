package config

import (
	"github.com/metacubex/mihomo/common/yaml"
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

func repairDoc(doc map[string]any) {
	if doc == nil {
		return
	}
	names := map[string]bool{
		"DIRECT": true, "REJECT": true, "REJECT-DROP": true,
		"PASS": true, "COMPATIBLE": true, "GLOBAL": true,
	}
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
		filtered := make([]any, 0)
		if list, ok := asSlice(m["proxies"]); ok {
			for _, proxy := range list {
				name, _ := proxy.(string)
				if name != "" && names[name] {
					filtered = append(filtered, name)
				}
			}
		}
		useCount := 0
		if list, ok := asSlice(m["use"]); ok {
			useCount = len(list)
		}
		if len(filtered) == 0 && useCount == 0 {
			m["include-all"] = true
			filtered = []any{"DIRECT"}
		}
		if _, exists := m["proxies"]; exists || len(filtered) > 0 {
			m["proxies"] = filtered
		}
	}
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
