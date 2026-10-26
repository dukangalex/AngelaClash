package config

import (
	P "path"
	"strings"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

// RuleOverride is one profile's rule draft.
//
//   - ModeAdded:    the draft is prepended to the rules the profile ships with.
//   - ModeOverride: the draft replaces them outright.
//
// The draft is a plain rule list, never a diff against the profile: matching a
// rule by its exact text breaks the moment a subscription gains a trailing
// comment or quotes a value, and it cannot express "add a rule" or "reorder".
type RuleOverride struct {
	Enabled bool     `json:"enabled"`
	Mode    string   `json:"mode"`
	Rules   []string `json:"rules"`
}

const (
	ModeAdded    = "added"
	ModeOverride = "override"
)

func (o RuleOverride) enabled() bool {
	for _, rule := range o.Rules {
		if strings.TrimSpace(rule) != "" {
			return o.Enabled
		}
	}

	return false
}

// patchRuleOverride applies the profile's rule draft to the running config.
//
// It runs after patchScriptDisplay (system switches may reorder rules) and
// before patchChain, which keeps its own routing invariants last-word: if chain
// has to append a hop rule, that still lands on top of the draft.
func patchRuleOverride(cfg *config.RawConfig, profileDir string) error {
	ov, ok := ruleOverrideFor(profileDir)
	if !ok || !ov.enabled() {
		return nil
	}

	draft := make([]string, 0, len(ov.Rules))
	for _, rule := range ov.Rules {
		if trimmed := strings.TrimSpace(rule); trimmed != "" {
			draft = append(draft, trimmed)
		}
	}
	if len(draft) == 0 {
		return nil
	}

	var merged []string
	if ov.Mode == ModeOverride {
		merged = draft
	} else {
		merged = make([]string, 0, len(draft)+len(cfg.Rule))
		merged = append(merged, draft...)
		merged = append(merged, cfg.Rule...)
	}

	// A rule list without a catch-all silently drops everything that does not
	// match, so the tunnel would reject traffic the user never asked about.
	// Keep the profile's own MATCH in override mode instead of trusting the draft.
	if ov.Mode == ModeOverride && !hasCatchAll(merged) {
		if fallback := catchAllOf(cfg.Rule); fallback != "" {
			merged = append(merged, fallback)
			log.Warnln("rule override: draft has no MATCH, keeping the profile's own %q", fallback)
		}
	}

	if len(merged) == 0 {
		log.Warnln("rule override: empty result, keeping the original rules")

		return nil
	}

	log.Infoln("rule override (%s): %d profile rule(s) -> %d", ov.Mode, len(cfg.Rule), len(merged))
	cfg.Rule = merged

	return nil
}

// profileKey maps a profile directory to the key the app writes. The directory
// is named after the profile uuid, with or without a trailing separator.
func profileKey(profileDir string) string {
	return P.Base(strings.TrimSuffix(profileDir, "/"))
}

// ruleOverrideFor returns the draft that belongs to this profile directory. The
// directory is named after the profile uuid, which is also the key the app uses.
func ruleOverrideFor(profileDir string) (RuleOverride, bool) {
	if profileDir == "" {
		return RuleOverride{}, false
	}

	opts, ok := readScriptOptions()
	if !ok || len(opts.RuleOverrides) == 0 {
		return RuleOverride{}, false
	}

	ov, exists := opts.RuleOverrides[profileKey(profileDir)]

	return ov, exists
}

func hasCatchAll(rules []string) bool {
	return catchAllOf(rules) != ""
}

// catchAllOf returns the first MATCH/FINAL rule in the list, or "".
func catchAllOf(rules []string) string {
	for _, rule := range rules {
		if isCatchAll(rule) {
			return rule
		}
	}

	return ""
}

func isCatchAll(rule string) bool {
	head, _, _ := strings.Cut(rule, ",")
	switch strings.ToUpper(strings.TrimSpace(head)) {
	case "MATCH", "FINAL":
		return true
	}

	return false
}
