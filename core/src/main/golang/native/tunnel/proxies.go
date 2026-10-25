package tunnel

import (
	"sort"
	"strings"

	"github.com/dlclark/regexp2"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

type SortMode int

const (
	Default SortMode = iota
	Title
	Delay
)

type Proxy struct {
	Name     string `json:"name"`
	Title    string `json:"title"`
	Subtitle string `json:"subtitle"`
	Type     string `json:"type"`
	Delay    int    `json:"delay"`
	IsGroup  bool   `json:"isGroup"`
}

type ProxyGroup struct {
	Type    string   `json:"type"`
	Now     string   `json:"now"`
	Proxies []*Proxy `json:"proxies"`
}

type sortableProxyList struct {
	list []*Proxy
	less func(a, b *Proxy) bool
}

func (s *sortableProxyList) Len() int {
	return len(s.list)
}

func (s *sortableProxyList) Less(i, j int) bool {
	return s.less(s.list[i], s.list[j])
}

func (s *sortableProxyList) Swap(i, j int) {
	s.list[i], s.list[j] = s.list[j], s.list[i]
}

// GroupMeta is one entry of the running config's proxy-group set, reduced to
// the properties the proxy page needs to decide whether to show it.
type GroupMeta struct {
	Name   string
	Type   C.AdapterType
	Hidden bool
}

// FilterGroupNames keeps every group the config registers. Two rules used to
// make real groups vanish from the 代理 page:
//
//   - `hidden: true` is a ClashX-style hint to fold a group into its parent's
//     tab row; it is not a routing property, so it is reported through
//     GroupMeta.Hidden instead of dropping the group.
//   - the names were read from GLOBAL's member list. A config that ships its
//     own GLOBAL group (the built-in override script does) only lists a subset
//     there, so any group it omits - e.g. a subscription's 入口优选 - was never
//     offered to the UI even though the kernel had loaded it.
//
// The only filter left is the user's own: the 不可选择 menu toggle keeps
// manually selectable groups.
func FilterGroupNames(metas []GroupMeta, excludeNotSelectable bool) []string {
	result := make([]string, 0, len(metas))
	for _, m := range metas {
		if excludeNotSelectable && m.Type != C.Selector {
			continue
		}
		result = append(result, m.Name)
	}
	return result
}

// collectGroupMetas returns every ProxyGroup the kernel has loaded, keyed by
// name.
func collectGroupMetas(proxies map[string]C.Proxy) map[string]GroupMeta {
	metas := make(map[string]GroupMeta, len(proxies))
	for name, p := range proxies {
		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			continue
		}
		metas[name] = GroupMeta{
			Name:   name,
			Type:   p.Type(),
			Hidden: g.Hidden(),
		}
	}
	return metas
}

// orderGroupMetas puts the groups in config order: globalOrder first (that is
// where a config keeps its intended order), then every other group the kernel
// loaded, alphabetically, so nothing silently disappears.
func orderGroupMetas(globalOrder []string, registered map[string]GroupMeta) []GroupMeta {
	ordered := make([]GroupMeta, 0, len(registered))
	seen := make(map[string]bool, len(registered))

	for _, name := range globalOrder {
		m, ok := registered[name]
		if !ok || seen[m.Name] {
			continue
		}
		seen[m.Name] = true
		ordered = append(ordered, m)
	}

	rest := make([]string, 0, len(registered))
	for name := range registered {
		if name == "GLOBAL" || seen[name] {
			continue
		}
		rest = append(rest, name)
	}
	sort.Strings(rest)
	for _, name := range rest {
		ordered = append(ordered, registered[name])
	}

	return ordered
}

// globalMemberOrder returns the order a config lists its groups in, as far as
// GLOBAL exposes it. It can be a subset: a config may ship its own GLOBAL.
func globalMemberOrder(global outboundgroup.ProxyGroup) []string {
	if global == nil || len(global.Providers()) == 0 {
		return nil
	}
	members := global.Providers()[0].Proxies()
	names := make([]string, 0, len(members))
	for _, p := range members {
		names = append(names, p.Name())
	}
	return names
}

func QueryProxyGroupNames(excludeNotSelectable bool) []string {
	mode := tunnel.Mode()

	if mode == tunnel.Direct {
		return []string{}
	}

	proxies := tunnel.Proxies()
	registered := collectGroupMetas(proxies)

	var global outboundgroup.ProxyGroup
	if p, ok := proxies["GLOBAL"]; ok && p != nil {
		global, _ = p.Adapter().(outboundgroup.ProxyGroup)
	}

	result := make([]string, 0, len(registered)+1)
	if mode == tunnel.Global {
		result = append(result, "GLOBAL")
	}

	return append(result, FilterGroupNames(orderGroupMetas(globalMemberOrder(global), registered), excludeNotSelectable)...)
}

func QueryProxyGroup(name string, sortMode SortMode, uiSubtitlePattern *regexp2.Regexp) *ProxyGroup {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Query group `%s`: not found", name)

		return nil
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Query group `%s`: invalid type %s", name, p.Type().String())

		return nil
	}

	proxies := convertProxies(g.Proxies(), uiSubtitlePattern)
	// 	proxies := collectProviders(g.Providers(), uiSubtitlePattern)

	switch sortMode {
	case Title:
		wrapper := &sortableProxyList{
			list: proxies,
			less: func(a, b *Proxy) bool {
				return strings.Compare(a.Title, b.Title) < 0
			},
		}

		sort.Sort(wrapper)
	case Delay:
		wrapper := &sortableProxyList{
			list: proxies,
			less: func(a, b *Proxy) bool {
				return a.Delay < b.Delay
			},
		}

		sort.Sort(wrapper)
	case Default:
	default:
	}

	return &ProxyGroup{
		Type:    g.Type().String(),
		Now:     g.Now(),
		Proxies: proxies,
	}
}

func PatchSelector(selector, name string) bool {
	p := tunnel.Proxies()[selector]

	if p == nil {
		log.Warnln("Patch selector `%s`: not found", selector)

		return false
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return false
	}

	s, ok := g.(outboundgroup.SelectAble)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return false
	}

	if err := s.Set(name); err != nil {
		log.Warnln("Patch selector `%s`: %s", selector, err.Error())
	}

	log.Infoln("Patch selector %s -> %s", selector, name)

	closeConnByGroup(selector)

	return true
}

func convertProxies(proxies []C.Proxy, uiSubtitlePattern *regexp2.Regexp) []*Proxy {
	result := make([]*Proxy, 0, 128)

	for _, p := range proxies {
		name := p.Name()
		title := name
		subtitle := p.Type().String()

		if uiSubtitlePattern != nil {
			if _, ok := p.Adapter().(outboundgroup.ProxyGroup); !ok {
				runes := []rune(name)
				match, err := uiSubtitlePattern.FindRunesMatch(runes)
				if err == nil && match != nil {
					title = string(runes[:match.Index]) + string(runes[match.Index+match.Length:])
					subtitle = string(runes[match.Index : match.Index+match.Length])
				}
			}
		}
		testURL := "https://www.gstatic.com/generate_204"
		for k := range p.ExtraDelayHistories() {
			if len(k) > 0 {
				testURL = k
				break
			}
		}
		_, isGroup := p.Adapter().(outboundgroup.ProxyGroup)

		result = append(result, &Proxy{
			Name:     name,
			Title:    strings.TrimSpace(title),
			Subtitle: strings.TrimSpace(subtitle),
			Type:     p.Type().String(),
			Delay:    int(p.LastDelayForTestUrl(testURL)),
			IsGroup:  isGroup,
		})
	}
	return result
}

func collectProviders(providers []provider.ProxyProvider, uiSubtitlePattern *regexp2.Regexp) []*Proxy {
	result := make([]*Proxy, 0, 128)

	for _, p := range providers {
		for _, px := range p.Proxies() {
			name := px.Name()
			title := name
			subtitle := px.Type().String()

			if uiSubtitlePattern != nil {
				if _, ok := px.Adapter().(outboundgroup.ProxyGroup); !ok {
					runes := []rune(name)
					match, err := uiSubtitlePattern.FindRunesMatch(runes)
					if err == nil && match != nil {
						title = string(runes[:match.Index]) + string(runes[match.Index+match.Length:])
						subtitle = string(runes[match.Index : match.Index+match.Length])
					}
				}
			}

			testURL := "https://www.gstatic.com/generate_204"
			for k := range px.ExtraDelayHistories() {
				if len(k) > 0 {
					testURL = k
					break
				}
			}
			_, isGroup := px.Adapter().(outboundgroup.ProxyGroup)

			result = append(result, &Proxy{
				Name:     name,
				Title:    strings.TrimSpace(title),
				Subtitle: strings.TrimSpace(subtitle),
				Type:     px.Type().String(),
				Delay:    int(px.LastDelayForTestUrl(testURL)),
				IsGroup:  isGroup,
			})
		}
	}

	return result
}
