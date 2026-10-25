package tunnel

import (
	"reflect"
	"testing"

	C "github.com/metacubex/mihomo/constant"
)

// A subscription can mark a policy group `hidden: true` while it is still a
// real, selectable group inside its parent (the 入口优选 pattern). ClashX-style
// clients fold such a group into the parent's tab row and drop the card; doing
// the same on the 代理 page made the group look like it had vanished.
func TestFilterGroupNamesKeepsHiddenGroups(t *testing.T) {
	metas := []GroupMeta{
		{Name: "默认代理", Type: C.Selector},
		{Name: "入口优选", Type: C.Selector, Hidden: true},
		{Name: "自动选择", Type: C.URLTest, Hidden: true},
		{Name: "负载均衡", Type: C.LoadBalance, Hidden: true},
		{Name: "故障转移", Type: C.Fallback},
	}

	got := FilterGroupNames(metas, false)
	want := []string{"默认代理", "入口优选", "自动选择", "负载均衡", "故障转移"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("all groups must be listed, got %v want %v", got, want)
	}

	// The menu toggle still works: only manually selectable groups.
	got = FilterGroupNames(metas, true)
	want = []string{"默认代理", "入口优选"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("excludeNotSelectable must keep selectors only, got %v want %v", got, want)
	}
}

// A config that ships its own GLOBAL group only lists a subset of its groups
// there. Groups missing from GLOBAL must still reach the UI, after the ones it
// does list.
func TestOrderGroupMetasAppendsGroupsMissingFromGlobal(t *testing.T) {
	registered := map[string]GroupMeta{
		"GLOBAL": {Name: "GLOBAL", Type: C.Selector},
		"默认代理":   {Name: "默认代理", Type: C.Selector},
		"漏网之鱼":   {Name: "漏网之鱼", Type: C.Selector},
		"出口优选":   {Name: "出口优选", Type: C.URLTest},
		"入口优选":   {Name: "入口优选", Type: C.Selector},
	}

	got := FilterGroupNames(orderGroupMetas([]string{"默认代理", "漏网之鱼"}, registered), false)
	want := []string{"默认代理", "漏网之鱼", "入口优选", "出口优选"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("groups missing from GLOBAL must still be listed, got %v want %v", got, want)
	}
	for _, name := range got {
		if name == "GLOBAL" {
			t.Fatalf("GLOBAL must not leak into the proxy list outside global mode: %v", got)
		}
	}

	// With no GLOBAL member list at all nothing is dropped either.
	got = FilterGroupNames(orderGroupMetas(nil, registered), false)
	if len(got) != 4 {
		t.Fatalf("every registered group must survive an empty GLOBAL, got %v", got)
	}
}
