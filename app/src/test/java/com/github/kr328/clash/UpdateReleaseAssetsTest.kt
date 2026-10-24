package com.github.kr328.clash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateReleaseAssetsTest {
    private fun asset(abi: String) = UpdateReleaseAssets.Asset(
        "Angela-Clash-1.1.4-meta-$abi-release.apk", "https://example.invalid/$abi.apk"
    )
    private val arm64 = asset("arm64-v8a")
    private val arm32 = asset("armeabi-v7a")
    private val x86 = asset("x86")
    private val x64 = asset("x86_64")
    private val universal = asset("universal")

    @Test fun deviceAbiBeatsTheUniversalBuild() {
        assertEquals(arm64, UpdateReleaseAssets.select(listOf(universal, arm64), listOf("arm64-v8a")))
    }
    @Test fun universalIsTheOfflineFallback() {
        assertEquals(universal, UpdateReleaseAssets.select(listOf(universal, x86), listOf("arm64-v8a")))
    }
    @Test fun selectsCompatibleApkInsteadOfFirstAsset() {
        assertEquals(arm64, UpdateReleaseAssets.select(listOf(x86, arm64), listOf("arm64-v8a", "armeabi-v7a")))
    }
    @Test fun usesDeviceAbiPreferenceNotReleaseOrder() {
        assertEquals(arm64, UpdateReleaseAssets.select(listOf(arm32, arm64), listOf("arm64-v8a", "armeabi-v7a")))
    }
    @Test fun canUseCompatibleSecondaryAbi() {
        assertEquals(arm32, UpdateReleaseAssets.select(listOf(x86, arm32), listOf("arm64-v8a", "armeabi-v7a")))
    }
    @Test fun x86DoesNotMatchX86_64() {
        assertNull(UpdateReleaseAssets.select(listOf(x64), listOf("x86")))
    }
    @Test fun x86_64IsSelectedWhenAvailable() {
        assertEquals(x64, UpdateReleaseAssets.select(listOf(x86, x64), listOf("x86_64", "x86")))
    }
    @Test fun incompatibleReleaseDoesNotOfferAnInstaller() {
        assertNull(UpdateReleaseAssets.select(listOf(x86, x64), listOf("arm64-v8a", "armeabi-v7a")))
    }
    @Test fun ignoresNonApkFilesAndPartialTokens() {
        val metadata = UpdateReleaseAssets.Asset("universal.json", "metadata")
        val misleading = UpdateReleaseAssets.Asset("notuniversal.apk", "misleading")
        assertNull(UpdateReleaseAssets.select(listOf(metadata, misleading), emptyList()))
    }
    @Test fun unknownAbisAreNotGuessed() {
        assertNull(UpdateReleaseAssets.select(listOf(asset("mips")), listOf("mips")))
    }
    @Test fun emptyReleaseIsRejected() {
        assertNull(UpdateReleaseAssets.select(emptyList(), listOf("arm64-v8a")))
    }
    @Test fun selectionRetainsMatchingUrlAndName() {
        val result = UpdateReleaseAssets.select(listOf(x86, arm64), listOf("arm64-v8a"))!!
        assertEquals(arm64.url, result.url)
        assertEquals(arm64.name, result.name)
    }
    @Test fun matchesUppercaseApkAndAbiTokens() {
        val upper = UpdateReleaseAssets.Asset("Angela-META-ARM64-V8A-RELEASE.APK", "upper")
        assertEquals(upper, UpdateReleaseAssets.select(listOf(upper), listOf("arm64-v8a")))
    }
}
