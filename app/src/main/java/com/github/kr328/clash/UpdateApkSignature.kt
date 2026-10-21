package com.github.kr328.clash

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

/** Full APK verification (not just PackageManager's archive metadata parser). */
internal object UpdateApkSignature {
    data class Identity(val signers: Set<String>, val rotationAncestorsWithInstalledData: Set<String>)

    fun verify(apk: File, platform: Int): Identity {
        val result = ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(platform)
            .setMaxCheckedPlatformVersion(platform)
            .build().verify()
        check(result.isVerified) { "Invalid APK signature" }
        val signers = result.signerCertificates.map { fingerprint(it.encoded) }.toSet()
        check(signers.isNotEmpty()) { "Unsigned APK" }
        val ancestors = if (platform >= 28 && signers.size == 1) {
            result.signingCertificateLineage?.let { lineage ->
                // Only trust a lineage ending at the verified active signer, with the correct capability.
                if (fingerprint(lineage.certificatesInLineage.last().encoded) in signers) {
                    lineage.certificatesInLineage.filter {
                        lineage.getSignerCapabilities(it).hasInstalledData()
                    }.map { fingerprint(it.encoded) }.toSet() - signers
                } else emptySet()
            } ?: emptySet()
        } else emptySet()
        return Identity(signers, ancestors)
    }

    fun fingerprint(encoded: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(encoded).joinToString("") { "%02x".format(it) }
}
