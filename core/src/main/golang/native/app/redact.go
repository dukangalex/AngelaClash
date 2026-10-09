package app

import (
	neturl "net/url"
	"strings"
)

// RedactURLForLog strips query parameters, fragments and user info from a URL
// before it is written to logs. Subscription and provider URLs frequently carry
// tokens in the query string, and logs can be exported from the app, so the raw
// URL must never reach them.
func RedactURLForLog(raw string) string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return ""
	}
	u, err := neturl.Parse(trimmed)
	if err != nil {
		return "[unparseable url]"
	}
	u.RawQuery = ""
	u.ForceQuery = false
	u.Fragment = ""
	u.RawFragment = ""
	u.User = nil
	return u.String()
}
