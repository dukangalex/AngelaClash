package com.github.kr328.clash

/** Pure release-asset selection, shared by the updater and JVM regression tests. */
internal object UpdateReleaseAssets {
    data class Asset(val name: String, val url: String)

    fun select(assets: List<Asset>, supportedAbis: List<String>): Asset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        // Preserve the existing universal-first policy. Never fall back to an arbitrary ABI.
        apks.firstOrNull { hasToken(it.name, "universal") }?.let { return it }
        for (abi in supportedAbis) {
            if (abi !in knownAbis) continue
            apks.firstOrNull { hasToken(it.name, abi) }?.let { return it }
        }
        return null
    }

    private val knownAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    private fun hasToken(name: String, token: String): Boolean {
        // ABI names contain '-' and '_': only '-' or '.' outside the full token are delimiters.
        // In particular, x86 must not match x86_64.
        return Regex("(?:^|[-.])${Regex.escape(token)}(?:[-.]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(name.removeSuffix(".apk"))
    }
}
