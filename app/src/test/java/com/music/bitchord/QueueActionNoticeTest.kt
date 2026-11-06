package com.music.bitchord

import com.music.bitchord.ui.components.shouldDismissQueueActionNotice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueActionNoticeTest {

    @Test
    fun `a horizontal swipe past threshold dismisses either way`() {
        assertTrue(shouldDismissQueueActionNotice(360f, 1_000f))
        assertTrue(shouldDismissQueueActionNotice(-360f, 1_000f))
    }

    @Test
    fun `a short swipe or zero width does not dismiss`() {
        assertFalse(shouldDismissQueueActionNotice(340f, 1_000f))
        assertFalse(shouldDismissQueueActionNotice(-340f, 1_000f))
        assertFalse(shouldDismissQueueActionNotice(1f, 0f))
    }
}
