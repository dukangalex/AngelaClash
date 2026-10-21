# Release certificate gate toolchain

The release gate must cryptographically verify each APK, require exactly one signer,
and compare its certificate SHA-256 fingerprint with SIGNING.md before upload.

The original gate selected the newest installed build-tools and parsed the legacy
`Signer #1 certificate SHA-256 digest:` line. Android build-tools 37.0.0 emits
`V2 Signer: certificate SHA-256 digest:` for the same APK. The original extractor
returns an empty string and rejects a correctly signed APK. This was reproduced
offline with the already-published v1.1.4 arm64 APK: build-tools 35.0.0 passes;
37.0.0 verifies the APK successfully but the old shell gate rejects it. Both tools
report the same fingerprint `82d0db007c5eafc37e126b4fdc16660d7cf2434c7a15e610f4b43fd6426943dc`.

Pin the verification CLI to build-tools 35.0.0, installing it explicitly if missing,
rather than weakening or guessing the certificate parser. Keep the exact signer
count and fingerprint checks. No production key or certificate is changed.
Tool upgrades must update and test the parser contract deliberately.

Run `python3 scripts/test-release-signing-gate.py` for offline shell orchestration
regressions. It extracts the actual workflow step and uses fake CLI output; those
unit checks are not APK cryptographic tests. The accompanying evidence bundle
also runs the unchanged and fixed gate against real APKs with tools 35 and 37.

The failed v1.1.5 run did not upload APK artifacts or publish a release. Its log
only reports the generic certificate mismatch, not the extracted digest or selected
CLI version. The reproduced parser bug therefore does not establish the actual
new APK certificate, nor prove that the remote job selected build-tools 37. Never
infer a changed signing key from that message. After reviewing this repair,
a future authorized build must still verify every new APK before publication.

## Complete release identity gate

Before upload/publication the release workflow also runs the 47 updater unit tests
and a full MetaRelease build, then requires exactly the universal plus four named
ABI APKs. `scripts/verify-release-apks.py` checks package `io.chainbox.clash`, the
requested release version/code, declared and actual native ABI sets, and each
native core. `scripts/test-release-apk-metadata.py` provides 13 fail-closed regression
tests. This supplements, never replaces, the existing production certificate gate.
It does not establish device runtime or installer acceptance.
