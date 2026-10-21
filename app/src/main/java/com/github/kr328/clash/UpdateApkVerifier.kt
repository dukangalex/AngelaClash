package com.github.kr328.clash

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

internal object UpdateApkVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, apk: File, releaseVersion: String) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidate = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: error("Invalid APK manifest")
        val identity = UpdateApkSignature.verify(apk, Build.VERSION.SDK_INT)
        val installedSignatures = if (Build.VERSION.SDK_INT >= 28)
            installed.signingInfo?.apkContentsSigners else installed.signatures
        val installedSigners = installedSignatures.orEmpty()
            .map { UpdateApkSignature.fingerprint(it.toByteArray()) }.toSet()
        check(UpdateApkPolicy.accepts(
            context.packageName, versionCode(installed), installedSigners,
            candidate.packageName, versionCode(candidate), identity.signers,
            identity.rotationAncestorsWithInstalledData,
        )) { "APK is not a trusted upgrade of this app" }
        val expected = releaseVersion.removePrefix("v")
        check(candidate.versionName == expected || candidate.versionName == "$expected.Meta") {
            "APK does not match the selected release"
        }
        check(Build.VERSION.SDK_INT < 24 || candidate.applicationInfo?.let { Build.VERSION.SDK_INT >= it.minSdkVersion } == true) {
            "APK requires a newer Android version"
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28)
        info.longVersionCode else info.versionCode.toLong()
}
