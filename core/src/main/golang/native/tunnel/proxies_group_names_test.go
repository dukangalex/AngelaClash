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
		{Name: "默认代理", Type: C.Selector, ProxyGroupType: true},
		{Name: "入口优选", Type: C.Selector, Hidden: true, ProxyGroupType: true},
		{Name: "自动选择", Type: C.URLTest, Hidden: true, ProxyGroupType: true},
		{Name: "负载均衡", Type: C.LoadBalance, Hidden: true, ProxyGroupType: true},
		{Name: "故障转移", Type: C.Fallback, ProxyGroupType: true},
		{Name: "hk-01", Type: C.Shadowsocks, ProxyGroupType: false},
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
