# Updater regression tests

This change keeps the existing universal-APK preference, then selects a release APK using the device's supported ABI order. An incompatible release is rejected rather than downloading an arbitrary architecture. Download completion must have a successful DownloadManager status and a nonempty file before opening Android's installer. HTTP release responses are closed and disconnected on success and failure.

## Standard Android unit tests

With JDK 21 and the project's Android SDK dependencies configured:

```sh
./gradlew --no-daemon :app:testMetaDebugUnitTest :app:compileMetaDebugKotlin
```

The updater tests use synthetic asset names, temporary local files and a fake HTTP connection. They do not access subscriptions, start a proxy/VPN or download/install an APK.

## Standalone policy/transport tests

If the full Android build toolchain is unavailable, use a Kotlin 2.1.0 compiler, an Android SDK `android.jar`, JUnit 4.13.2 and Hamcrest Core 1.3 already installed locally:

```sh
KOTLINC=/path/to/kotlinc/bin/kotlinc \
ANDROID_JAR=/path/to/sdk/platforms/android-35/android.jar \
JUNIT_JAR=/path/to/junit-4.13.2.jar \
HAMCREST_JAR=/path/to/hamcrest-core-1.3.jar \
./scripts/test-updater-jvm.sh
```

This checks the production selection, download-result policy and HTTP cleanup helpers. It is not an Android instrumentation test and does not exercise the Activity, BroadcastReceiver, DownloadManager or system installer on a device.

## Device acceptance still needed

Use a disposable emulator and synthetic release/download fixtures, not a real subscription:

- Matching ARM and x86 releases select compatible assets; a release without a compatible APK reports failure.
- Failed and empty downloads never open the installer and show an update failure.
- A successfully downloaded test APK opens the system installer exactly once, including a very fast completion.
- Verify behavior across Activity destruction and recreation during a download; this patch does not add persistent download recovery.

No checksum/signature verification is added by these unit tests. A nonempty successful download only satisfies the updater's pre-install guard; Android still validates the APK at installation.
