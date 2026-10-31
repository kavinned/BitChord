package com.music.bitchord.data

import com.music.bitchord.data.model.LikeStatus
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LikeStateTest {

    @AfterTest
    fun clear() = LikeState.clear()

    /**
     * Two screens rate at once: the notification and the player. The write has
     * to be a compare-and-set, or one of them reads the old map and overwrites
     * the other's entry with it.
     */
    @Test
    fun `ratings from many threads all survive`() {
        val writers = 8
        val perWriter = 100
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)
        repeat(writers) { w ->
            thread {
                start.await()
                repeat(perWriter) { i -> LikeState.set("id-${w * perWriter + i}", LikeStatus.LIKE) }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        assertEquals(writers * perWriter, LikeState.overrides.value.size)
    }
}
