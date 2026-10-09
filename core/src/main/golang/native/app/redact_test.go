package app

import "testing"

func TestRedactURLForLog(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{
			"https://sub.example.com/api/v1/sub?token=secret123",
			"https://sub.example.com/api/v1/sub",
		},
		{
			"https://sub.example.com/api?token=a&flag=1#frag",
			"https://sub.example.com/api",
		},
		{
			"https://user:pass@sub.example.com/api?token=a",
			"https://sub.example.com/api",
		},
		{
			"content://com.example.provider/config",
			"content://com.example.provider/config",
		},
		{
			"https://sub.example.com/api",
			"https://sub.example.com/api",
		},
		{"", ""},
		{"://bad", "[unparseable url]"},
	}
	for _, c := range cases {
		if got := RedactURLForLog(c.in); got != c.want {
			t.Errorf("RedactURLForLog(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}
