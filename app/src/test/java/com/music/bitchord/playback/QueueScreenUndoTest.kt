package com.music.bitchord.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueScreenUndoTest {

    @After
    fun tearDown() {
        QueueScreenUndo.clear()
    }

    @Test
    fun `publish stores latest notice and clear resets it`() {
        val first = QueueUndoNotice(token = "t1", message = "Removed 1 song")
        val second = QueueUndoNotice(token = "t2", message = "Cleared queue")

        QueueScreenUndo.publish(first)
        assertEquals(first, QueueScreenUndo.notice.value)

        QueueScreenUndo.publish(second)
        assertEquals(second, QueueScreenUndo.notice.value)

        QueueScreenUndo.clear()
        assertNull(QueueScreenUndo.notice.value)
    }
}
