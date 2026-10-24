package config

import (
	"strings"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
)

func init() {
	chainRepairConfigYAML = repairConfigYAML
}

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
		"PASS": true, "PASS-RULE": true, "COMPATIBLE": true,
	}
}

func repairDoc(doc map[string]any) {
	if doc == nil {
		return
	}
	leafNames := builtinOutbounds()
	names := builtinOutbounds()
	var allNodeNames []any
	if list, ok := asSlice(doc["proxies"]); ok {
		for _, item := range list {
			m, ok := asMap(item)
			if !ok {
				continue
			}
			if name, _ := m["name"].(string); name != "" {
				names[name] = true
				leafNames[name] = true
				allNodeNames = append(allNodeNames, name)
			}
		}
	}
	var providerNames map[string]bool
	if pm, ok := asMap(doc["proxy-providers"]); ok {
		providerNames = map[string]bool{}
		for k := range pm {
			providerNames[k] = true
		}
	}
	groups, ok := asSlice(doc["proxy-groups"])
	if !ok {
		return
	}
	var groupMaps []map[string]any
	for _, item := range groups {
		m, ok := asMap(item)
		if !ok {
			continue
		}
		if name, _ := m["name"].(string); name != "" {
			names[name] = true
			groupMaps = append(groupMaps, m)
		}
	}
	for _, m := range groupMaps {
		repairGroupMap(m, names, leafNames, providerNames, allNodeNames)
	}
	breakGroupCycles(groupMaps, allNodeNames)
}

// repairRawConfig ensures all proxy-groups and rules in a RawConfig only
// reference existing proxies/groups after script and display-option patches.
func repairRawConfig(cfg *config.RawConfig) {
	if cfg == nil {
		return
	}
	leafNames := builtinOutbounds()
	names := builtinOutbounds()
	var allNodeNames []any
	for _, m := range cfg.Proxy {
		if name, _ := m["name"].(string); name != "" {
			names[name] = true
			leafNames[name] = true
			allNodeNames = append(allNodeNames, name)
		}
	}
	providerNames := map[string]bool{}
	for k := range cfg.ProxyProvider {
		providerNames[k] = true
	}
	for _, m := range cfg.ProxyGroup {
		if name, _ := m["name"].(string); name != "" {
			names[name] = true
		}
	}
	for _, m := range cfg.ProxyGroup {
		repairGroupMap(m, names, leafNames, providerNames, allNodeNames)
	}
	breakGroupCycles(cfg.ProxyGroup, allNodeNames)
	for _, p := range cfg.Proxy {
		if dp, _ := p["dialer-proxy"].(string); dp != "" && !names[dp] {
			delete(p, "dialer-proxy")
		}
	}
	ruleNames := map[string]bool{"GLOBAL": true}
	for k, v := range names {
		ruleNames[k] = v
	}
	fallback := fallbackGroup(cfg, nil)
	if len(cfg.Rule) > 0 {
		repairedRules := make([]string, 0, len(cfg.Rule))
		for _, rule := range cfg.Rule {
			fixed, keep := retargetMissingRule(rule, ruleNames, cfg.RuleProvider, fallback)
			if keep {
				repairedRules = append(repairedRules, fixed)
			}
		}
		cfg.Rule = repairedRules
	}
}

func repairGroupMap(
	m map[string]any,
	names map[string]bool,
	leafNames map[string]bool,
	providerNames map[string]bool,
	allNodeNames []any,
) {
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
		validUse := make([]any, 0, len(list))
		for _, item := range list {
			uName, _ := item.(string)
			if uName != "" && (providerNames == nil || providerNames[uName]) {
				validUse = append(validUse, uName)
			}
		}
		useCount = len(validUse)
		if useCount > 0 {
			m["use"] = validUse
		} else {
			delete(m, "use")
		}
	}
	includeAll, _ := m["include-all"].(bool)
	includeAllProxies, _ := m["include-all-proxies"].(bool)
	includeAllProviders, _ := m["include-all-providers"].(bool)
	if len(filtered) == 0 && useCount == 0 && !includeAll && !includeAllProxies && !includeAllProviders {
		if len(allNodeNames) > 0 {
			for _, n := range allNodeNames {
				if s, _ := n.(string); s != "" && !validSet[s] {
					filtered = append(filtered, s)
					validSet[s] = true
				}
			}
		}
		if len(filtered) == 0 {
			filtered = []any{"DIRECT"}
			validSet["DIRECT"] = true
		}
	}
	if ef, _ := m["empty-fallback"].(string); ef != "" && !leafNames[ef] {
		delete(m, "empty-fallback")
	}
	if defSel, _ := m["default-selected"].(string); defSel != "" {
		if !validSet[defSel] && !includeAll && !includeAllProxies {
			delete(m, "default-selected")
		}
	}
	if _, exists := m["proxies"]; exists || len(filtered) > 0 {
		m["proxies"] = filtered
	}
}

// breakGroupCycles removes back-edges between proxy-groups so proxyGroupsDagSort
// can never fail with a cycle error.
func breakGroupCycles(groups []map[string]any, allNodeNames []any) {
	groupByName := make(map[string]map[string]any, len(groups))
	for _, g := range groups {
		if name, _ := g["name"].(string); name != "" {
			groupByName[name] = g
		}
	}
	state := make(map[string]int, len(groups)) // 0=unvisited, 1=visiting, 2=visited
	var dfs func(name string)
	dfs = func(name string) {
		state[name] = 1
		g := groupByName[name]
		if g != nil {
			if list, ok := asSlice(g["proxies"]); ok {
				cleaned := make([]any, 0, len(list))
				changed := false
				for _, item := range list {
					child, _ := item.(string)
					if _, isGroup := groupByName[child]; isGroup {
						if state[child] == 1 {
							changed = true
							continue
						}
						if state[child] == 0 {
							dfs(child)
						}
					}
					cleaned = append(cleaned, item)
				}
				if changed {
					if len(cleaned) == 0 {
						if len(allNodeNames) > 0 {
							cleaned = append(cleaned, allNodeNames...)
						} else {
							cleaned = []any{"DIRECT"}
						}
					}
					g["proxies"] = cleaned
				}
			}
		}
		state[name] = 2
	}
	for _, g := range groups {
		if name, _ := g["name"].(string); name != "" && state[name] == 0 {
			dfs(name)
		}
	}
}

func retargetMissingRule(
	rule string,
	names map[string]bool,
	ruleProviders map[string]map[string]any,
	fallback string,
) (string, bool) {
	trimmed := strings.TrimSpace(rule)
	if trimmed == "" || fallback == "" {
		return rule, true
	}
	upper := strings.ToUpper(trimmed)
	if strings.HasPrefix(upper, "RULE-SET,") {
		parts := strings.Split(trimmed, ",")
		if len(parts) >= 3 {
			providerName := strings.TrimSpace(parts[1])
			if providerName != "" && (ruleProviders == nil || ruleProviders[providerName] == nil) {
				return "", false
			}
		}
	}
	if strings.HasPrefix(upper, "SUB-RULE,") {
		return rule, true
	}
	if strings.HasPrefix(upper, "MATCH,") {
		target := strings.TrimSpace(trimmed[len("MATCH,"):])
		if target != "" && !names[target] {
			return "MATCH," + fallback, true
		}
		return rule, true
	}
	if strings.HasPrefix(upper, "AND,") || strings.HasPrefix(upper, "OR,") || strings.HasPrefix(upper, "NOT,") {
		lastComma := strings.LastIndex(trimmed, ",")
		if lastComma > 0 {
			target := strings.TrimSpace(trimmed[lastComma+1:])
			if target != "" && !names[target] {
				return trimmed[:lastComma+1] + fallback, true
			}
		}
		return rule, true
	}
	parts := strings.Split(trimmed, ",")
	if len(parts) >= 3 {
		target := strings.TrimSpace(parts[2])
		if target != "" && !names[target] {
			parts[2] = fallback
			return strings.Join(parts, ","), true
		}
	}
	return rule, true
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

