package com.github.kr328.clash

/** Pure release-asset selection, shared by the updater and JVM regression tests. */
internal object UpdateReleaseAssets {
    data class Asset(val name: String, val url: String)

    fun select(assets: List<Asset>, supportedAbis: List<String>): Asset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        // Prefer the APK for this device's ABI: the universal build is ~2.7x
        // larger (138 MB vs 51 MB) and every update would download it.
        for (abi in supportedAbis) {
            if (abi !in knownAbis) continue
            apks.firstOrNull { hasToken(it.name, abi) }?.let { return it }
        }
        // Fall back to the universal build, never to an arbitrary other ABI.
        return apks.firstOrNull { hasToken(it.name, "universal") }
    }

    private val knownAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    private fun hasToken(name: String, token: String): Boolean {
        // ABI names contain '-' and '_': only '-' or '.' outside the full token are delimiters.
        // In particular, x86 must not match x86_64.
        return Regex("(?:^|[-.])${Regex.escape(token)}(?:[-.]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(name.removeSuffix(".apk"))
    }
}
