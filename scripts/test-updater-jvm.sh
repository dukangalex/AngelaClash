#!/usr/bin/env bash
# Run the updater's pure policy/transport tests without the Android Gradle/native toolchain.
# Android APIs used by these tests are compile-time DownloadManager constants only.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${KOTLINC:?Path to Kotlin 2.1.0 kotlinc}"
: "${ANDROID_JAR:?Path to Android SDK platform android.jar}"
: "${JUNIT_JAR:?Path to JUnit 4.13.2 jar}"
: "${HAMCREST_JAR:?Path to Hamcrest Core 1.3 jar}"
KOTLIN_LIB="$(dirname "$KOTLINC")/../lib"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
CLASSPATH="$ANDROID_JAR:$JUNIT_JAR:$HAMCREST_JAR"
"$KOTLINC" app/src/main/java/com/github/kr328/clash/UpdateRelease{Assets,Request}.kt \
    app/src/main/java/com/github/kr328/clash/UpdateDownloadResult.kt \
    app/src/test/java/com/github/kr328/clash/Update*Test.kt \
    -cp "$CLASSPATH" -d "$OUT/tests.jar"
java -cp "$OUT/tests.jar:$KOTLIN_LIB/kotlin-stdlib.jar:$CLASSPATH" org.junit.runner.JUnitCore \
    com.github.kr328.clash.UpdateReleaseAssetsTest \
    com.github.kr328.clash.UpdateDownloadResultTest \
    com.github.kr328.clash.UpdateReleaseRequestTest
