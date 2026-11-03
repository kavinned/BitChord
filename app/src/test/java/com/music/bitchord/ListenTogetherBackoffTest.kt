package com.music.bitchord

import com.music.bitchord.data.listentogether.ListenTogether
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherBackoffTest {
    @Test
    fun `a socket that drops straight away keeps the grown backoff`() {
        assertFalse(ListenTogether.shouldResetBackoff(0))
        assertFalse(ListenTogether.shouldResetBackoff(1_000))
        assertFalse(ListenTogether.shouldResetBackoff(29_999))
    }

    @Test
    fun `a socket that held for thirty seconds starts the backoff over`() {
        assertTrue(ListenTogether.shouldResetBackoff(30_000))
        assertTrue(ListenTogether.shouldResetBackoff(30_001))
        assertTrue(ListenTogether.shouldResetBackoff(10 * 60_000))
    }
}
