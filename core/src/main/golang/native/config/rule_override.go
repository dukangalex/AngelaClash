package config

import (
	P "path"
	"strings"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

// patchRuleOverrides drops the rules the user switched off on the 规则开关 page.
//
// The set lives in script-options.json keyed by profile uuid, so:
//   - refreshing a subscription cannot resurrect a rule the user disabled, and
//   - flipping the override script on/off does not lose the choices.
//
// It runs after patchScriptDisplay (those switches may rename or reorder rules)
// and before patchChain, which keeps its own routing invariants last-word: if
// chain has to rewrite or append a rule, that still happens on top of the
// filtered list.
func patchRuleOverrides(cfg *config.RawConfig, profileDir string) error {
	disabled := disabledRulesFor(profileDir)
	if len(disabled) == 0 || len(cfg.Rule) == 0 {
		return nil
	}

	kept := dropMatching(cfg.Rule, func(rule string) bool {
		return disabled[strings.TrimSpace(rule)]
	})

	// A config with zero rules is not a valid runtime configuration. Refuse to
	// honour a selection that would remove everything instead of silently
	// starting a tunnel that rejects all traffic.
	if len(kept) == 0 {
		log.Warnln("rule overrides: all %d rules are disabled, keeping the original list", len(cfg.Rule))

		return nil
	}

	if removed := len(cfg.Rule) - len(kept); removed > 0 {
		log.Infoln("rule overrides: dropped %d disabled rule(s)", removed)
		cfg.Rule = kept
	}

	return nil
}

// disabledRulesFor returns the exact rule strings this profile has switched off.
// The profile directory is named after the profile uuid, which is also the key
// used by the app when it writes the file.
func disabledRulesFor(profileDir string) map[string]bool {
	if profileDir == "" {
		return nil
	}

	opts, ok := readScriptOptions()
	if !ok || len(opts.DisabledRules) == 0 {
		return nil
	}

	key := P.Base(strings.TrimSuffix(profileDir, "/"))
	rules, exists := opts.DisabledRules[key]
	if !exists {
		return nil
	}

	out := make(map[string]bool, len(rules))
	for _, rule := range rules {
		if trimmed := strings.TrimSpace(rule); trimmed != "" {
			out[trimmed] = true
		}
	}

	return out
}
