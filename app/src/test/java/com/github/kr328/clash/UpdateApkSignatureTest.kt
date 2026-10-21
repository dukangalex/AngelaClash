package com.github.kr328.clash

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Small APKs signed only with disposable fixture identities, never the project's real key. */
class UpdateApkSignatureTest {
    private fun <T> withApk(name: String, block: (File) -> T): T {
        val apk = File.createTempFile("updater-test-", ".apk")
        try {
            javaClass.getResourceAsStream("/updater/$name.apk")!!.use { input ->
                apk.outputStream().use { input.copyTo(it) }
            }
            return block(apk)
        } finally { apk.delete() }
    }
    private fun identity(name: String, platform: Int = 35) = withApk(name) {
        UpdateApkSignature.verify(it, platform)
    }
    private fun rejected(name: String, platform: Int = 35) {
        var rejected = false
        try { identity(name, platform) } catch (_: Exception) { rejected = true }
        assertTrue("$name on API $platform must be rejected", rejected)
    }
    @Test fun validV1OnApi21() { assertEquals(1, identity("same-signer", 21).signers.size) }
    @Test fun validV2OnApi27() { assertEquals(1, identity("same-signer", 27).signers.size) }
    @Test fun validOnApi35() { assertEquals(1, identity("same-signer").signers.size) }
    @Test fun unsignedRejected() { rejected("unsigned") }
    @Test fun tamperingRejected() { rejected("tampered") }
    @Test fun tamperedV1RejectedOnApi21() { rejected("tampered", 21) }
    @Test fun truncatedRejected() {
        withApk("same-signer") { apk ->
            java.io.RandomAccessFile(apk, "rw").use { it.setLength(128) }
            var rejected = false
            try { UpdateApkSignature.verify(apk, 35) } catch (_: Exception) { rejected = true }
            assertTrue(rejected)
        }
    }
    @Test fun unrelatedValidSignatureIsNotTrusted() {
        val installed = identity("same-signer")
        val wrong = identity("unrelated-signer")
        assertFalse(UpdateApkPolicy.accepts("app", 1, installed.signers, "app", 2, wrong.signers))
    }
    @Test fun verifiedForwardRotationOnApi28() {
        val installed = identity("same-signer", 28)
        val rotated = identity("rotated-signer", 28)
        assertNotEquals(installed.signers, rotated.signers)
        assertTrue(UpdateApkPolicy.accepts("app", 1, installed.signers, "app", 2,
            rotated.signers, rotated.rotationAncestorsWithInstalledData))
    }
    @Test fun oldAndroidUsesLegacySignerNotRotation() {
        val installed = identity("same-signer", 27)
        val rotated = identity("rotated-signer", 27)
        assertEquals(installed.signers, rotated.signers)
        assertTrue(rotated.rotationAncestorsWithInstalledData.isEmpty())
    }
}
