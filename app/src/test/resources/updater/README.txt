Updater signature test fixtures
===============================
These are small APK containers built from a synthetic manifest, NOT production
Angela Clash install packages. All three signer identities were generated locally
solely for these tests. No real project signing key is used or included.

unsigned.apk: aapt2-linked binary manifest in an otherwise minimal APK.
same-signer.apk: signed with disposable old test key, v1/v2/v3 enabled.
unrelated-signer.apk: equivalent package with a different disposable key.
rotated-signer.apk: old-to-new apksigner proof-of-rotation lineage, installed-data
  capability enabled; API 28 sees the new signer and API 27 the compatible old key.
tampered.apk: same-signer.apk with signed AndroidManifest.xml bytes altered.

The fixture keys and passwords are intentionally not part of the repository.
Tests derive identities from the verified fixtures, not from hardcoded names.
These fixtures test actual APK signature verification, not the Android installer,
PackageManager metadata parser, FileProvider handoff or Activity recreation.
