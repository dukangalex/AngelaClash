package com.github.kr328.clash

import org.junit.Assert.*
import org.junit.Test

class UpdateApkPolicyTest {
    private fun accepts(pkg: String = "app", version: Long = 11,
                        installed: Set<String> = setOf("old"), candidate: Set<String> = setOf("old"),
                        ancestors: Set<String> = emptySet()) = UpdateApkPolicy.accepts(
        "app", 10, installed, pkg, version, candidate, ancestors)

    @Test fun sameSignerNewerVersion() { assertTrue(accepts()) }
    @Test fun differentPackage() { assertFalse(accepts(pkg = "other")) }
    @Test fun downgrade() { assertFalse(accepts(version = 9)) }
    @Test fun reinstall() { assertFalse(accepts(version = 10)) }
    @Test fun versionCodeIsLong() { assertTrue(accepts(version = Int.MAX_VALUE.toLong() + 1)) }
    @Test fun missingInstalledSigner() { assertFalse(accepts(installed = emptySet())) }
    @Test fun missingCandidateSigner() { assertFalse(accepts(candidate = emptySet())) }
    @Test fun unrelatedKey() { assertFalse(accepts(candidate = setOf("other"))) }
    @Test fun forwardRotationWithInstalledData() {
        assertTrue(accepts(candidate = setOf("new"), ancestors = setOf("old")))
    }
    @Test fun commonHistoricalKeyDoesNotPermitSiblingRotation() {
        assertFalse(accepts(installed = setOf("current"), candidate = setOf("other"), ancestors = setOf("old")))
    }
    @Test fun oldKeyRollbackIsNotUpgrade() { assertFalse(accepts(installed = setOf("new"))) }
    @Test fun multipleSignerSetOrderIsIrrelevant() {
        assertTrue(accepts(installed = linkedSetOf("one", "two"), candidate = linkedSetOf("two", "one")))
    }
    @Test fun multipleSignerSubsetRejected() {
        assertFalse(accepts(installed = setOf("one", "two"), candidate = setOf("one"), ancestors = setOf("two")))
    }
    @Test fun addingSignerRejected() { assertFalse(accepts(candidate = setOf("old", "new"))) }
}
