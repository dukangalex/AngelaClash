package config

import (
	"strings"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

// groupRulePass is the target a rule falls back to when the policy group it
// pointed at has been switched off.
//
// Deleting the rule would change routing: traffic that used to land in the
// disabled group would fall through to whatever comes next. PASS keeps the
// rule in place and simply lets matching continue, which is the smallest
// semantic change we can make.
const groupRulePass = "PASS"

// patchDisabledGroups removes the policy groups the user switched off.
//
// Two things happen, in this order:
//
//  1. Groups lose the disabled members in their `proxies` list. A group that
//     ends up empty and has no dynamic source (use / include-all…) is disabled
//     too, and the scan repeats until nothing changes — that is how switching
//     off a leaf takes its now-empty parents with it.
//  2. Rules that target a disabled group are rewritten to PASS.
//
// Rules are rewritten, never dropped, so the relative order and the rest of
// the config stay untouched.
func patchDisabledGroups(cfg *config.RawConfig, profileDir string) error {
	disabled := disabledGroupsFor(profileDir)
	if len(disabled) == 0 {
		return nil
	}

	before := len(cfg.ProxyGroup)
	cfg.ProxyGroup = disableGroups(cfg.ProxyGroup, disabled)

	rewritten := 0
	for i, rule := range cfg.Rule {
		name, target, ok := splitRuleTarget(rule)
		if !ok || !disabled[target] {
			continue
		}
		cfg.Rule[i] = name + "," + groupRulePass
		rewritten++
	}

	log.Infoln(
		"disabled groups: %d group(s) removed (%d -> %d), %d rule(s) -> %s",
		len(disabled), before, len(cfg.ProxyGroup), rewritten, groupRulePass,
	)

	return nil
}

func disabledGroupsFor(profileDir string) map[string]bool {
	if profileDir == "" {
		return nil
	}

	opts, ok := readScriptOptions()
	if !ok || len(opts.DisabledGroups) == 0 {
		return nil
	}

	names, exists := opts.DisabledGroups[profileKey(profileDir)]
	if !exists {
		return nil
	}

	out := make(map[string]bool, len(names))
	for _, name := range names {
		if trimmed := strings.TrimSpace(name); trimmed != "" {
			out[trimmed] = true
		}
	}

	return out
}

// disableGroups mirrors BettBox's cascade: strip disabled members, then disable
// any group left with nothing to choose from, and repeat until stable.
func disableGroups(groups []map[string]any, disabled map[string]bool) []map[string]any {
	for changed := true; changed; {
		changed = false

		for _, group := range groups {
			name, ok := groupNameOf(group)
			if !ok || disabled[name] {
				continue
			}

			proxies, ok := group["proxies"].([]any)
			if !ok || len(proxies) == 0 {
				continue
			}

			kept := make([]any, 0, len(proxies))
			for _, proxy := range proxies {
				value, isName := proxy.(string)
				if isName && disabled[value] {
					continue
				}
				kept = append(kept, proxy)
			}
			if len(kept) == len(proxies) {
				continue
			}

			group["proxies"] = kept
			if len(kept) == 0 && !hasGroupSource(group) {
				disabled[name] = true
				changed = true
			}
		}
	}

	out := make([]map[string]any, 0, len(groups))
	for _, group := range groups {
		name, ok := groupNameOf(group)
		if ok && disabled[name] {
			continue
		}
		out = append(out, group)
	}

	return out
}

func groupNameOf(group map[string]any) (string, bool) {
	name, ok := group["name"].(string)
	if !ok || name == "" {
		return "", false
	}

	return name, true
}

// hasGroupSource reports whether the group builds its members dynamically, so an
// empty static list does not mean it is useless.
func hasGroupSource(group map[string]any) bool {
	if use, ok := group["use"].([]any); ok && len(use) > 0 {
		return true
	}
	for _, key := range []string{"include-all", "include-all-proxies", "include-all-providers"} {
		if value, ok := group[key].(bool); ok && value {
			return true
		}
	}

	return false
}

// splitRuleTarget pulls the target out of a rule line.
//
// Rule syntax is `<TYPE>,<payload>,<target>[,<params>…]`, except MATCH/FINAL
// which are `<TYPE>,<target>`. We only rewrite when the target is unambiguous.
func splitRuleTarget(rule string) (head, target string, ok bool) {
	trimmed := strings.TrimSpace(rule)
	if trimmed == "" {
		return "", "", false
	}

	parts := strings.Split(trimmed, ",")
	switch strings.ToUpper(strings.TrimSpace(parts[0])) {
	case "MATCH", "FINAL":
		if len(parts) != 2 {
			return "", "", false
		}

		return strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1]), true
	}

	if len(parts) < 3 {
		return "", "", false
	}

	target = strings.TrimSpace(parts[len(parts)-1])
	// A trailing param such as no-resolve belongs to the rule, not the target.
	switch strings.ToLower(target) {
	case "no-resolve", "src":
		if len(parts) < 4 {
			return "", "", false
		}
		target = strings.TrimSpace(parts[len(parts)-2])
		head = strings.Join(parts[:len(parts)-2], ",")
	default:
		head = strings.Join(parts[:len(parts)-1], ",")
	}
	if target == "" {
		return "", "", false
	}

	return head, target, true
}
