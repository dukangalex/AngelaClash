//go:build !android

package app

// NotifyDnsChanged is a no-op outside Android.
//
// The real implementation lives in dns.go and calls into the mihomo dns
// package, where UpdateSystemDNS/FlushCacheWithDefaultResolver only exist
// under the `android && cmfa` build tags. Keeping the split explicit means
// `go test ./native/config/` can run on a desktop toolchain in CI instead of
// failing to build, without adding anything to the mihomo submodule.
func NotifyDnsChanged(dnsList string) {
	_ = dnsList
}
