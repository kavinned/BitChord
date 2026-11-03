package com.music.bitchord

import com.music.bitchord.data.AppUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a downloaded APK may replace the running app. The decision is taken
 * on the certificate bytes rather than through PackageManager, which a JVM
 * test cannot stand up.
 */
class AppUpdateTrustTest {

    @Test
    fun `a certificate is compared by its sha-256, not by the object`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AppUpdateChecker.certDigests(listOf(ByteArray(0))).single(),
        )
        val certificate = ByteArray(32) { it.toByte() }
        assertEquals(
            AppUpdateChecker.certDigests(listOf(certificate)),
            AppUpdateChecker.certDigests(listOf(certificate.copyOf())),
        )
    }

    @Test
    fun `an update signed by an installed key is trusted`() {
        val installed = setOf("old-key", "new-key")
        assertTrue(AppUpdateChecker.signedByTrustedKey(installed, setOf("new-key")))
        // A rotation: the APK carries a key that is already in the history.
        assertTrue(AppUpdateChecker.signedByTrustedKey(installed, setOf("unrelated", "old-key")))
    }

    @Test
    fun `an update signed by an unknown key, or with none readable, is refused`() {
        assertFalse(AppUpdateChecker.signedByTrustedKey(setOf("old-key"), setOf("someone-else")))
        assertFalse(AppUpdateChecker.signedByTrustedKey(setOf("old-key"), emptySet()))
        assertFalse(AppUpdateChecker.signedByTrustedKey(emptySet(), setOf("old-key")))
    }
}
