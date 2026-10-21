# Updater APK verification

Before invoking the installer the updater now copies the completed download to
an app-private snapshot. It verifies the snapshot cryptographically using apksig
for the running Android API level, and requires:

- the installed application's package name;
- a strictly higher long version code and the selected release version name;
- the same current signing identity, or a verified single-signer forward rotation
  whose proof-of-rotation grants installed-data capability to the current key;
- an APK compatible with the Android API level.

Sharing an old ancestor does not authorize sibling key rotations. Downgrades,
changed multi-signer sets, unsigned, modified or truncated APKs are rejected.
The FileProvider exposes only the app-private updates directory, never the
original external download. Failed verification deletes the staged file. Successful
handoffs keep it readable by the installer; old snapshots are pruned after 7 days
on a later update attempt. Android's own installation checks still apply.

The device's installed app identity is the trust anchor. This permits legitimate
Android signing rotation rather than hardcoding a perpetual certificate. It does
not supply server-authenticated release checksums or protect a compromised current
signing key. apksig is pinned to 8.8.0; newer signing schemes will need a deliberate
library update and real-device validation.

## Automated checks

`./gradlew :app:compileMetaDebugKotlin :app:testMetaDebugUnitTest :app:mergeExtDexMetaDebug`
compiles Android Kotlin, runs the standard unit suite and tests dependency dexing.
`scripts/test-updater-jvm.sh` is the standalone equivalent for the pure policy,
transport and real signed-fixture checks, with tool paths supplied via environment.
There are 47 tests in total, including 24 new signature/policy regressions.

The fixture signature tests exercise API 21, 27, 28 and 35 verification semantics,
unsigned/tampered/truncated archives, unrelated keys, and verified forward rotation.
The policy tests cover package mismatch, downgrades/reinstalls, long version codes,
missing signers, unrelated keys, sibling/rollback rotations, and multi-signer sets.
PackageManager metadata reads, installer launch, FileProvider permissions and UI
behavior are compiled but not instrumented on an Android device in this batch.

The receiver is now released when its Activity is destroyed. Reconnecting a
DownloadManager task across Activity or process recreation is still not implemented;
a download may complete without automatically opening the installer. No real VPN,
subscription or system proxy was used to validate this patch.
