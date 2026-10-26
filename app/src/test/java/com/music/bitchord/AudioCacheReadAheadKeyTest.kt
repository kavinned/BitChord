package com.music.bitchord

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-ahead must check and write the same cache entry.
 *
 * The bug this pins: [AudioCache] wrote a substituted track's bytes under
 * `id#alt` and then asked the cache whether `id` was filled, so full-track
 * prefetch spent one probe per attempt and never converged.
 */
class AudioCacheReadAheadKeyTest {

    @Test
    fun `a substituted watch track keys on its alt rendition`() {
        assertTrue(
            AudioCache.videoCacheKey("abc", rendition = null, substituting = true, atmosSuffix = "")
                .startsWith("abc#alt"),
        )
    }

    @Test
    fun `a plain watch track keeps the video id`() {
        assertEquals("abc", AudioCache.videoCacheKey("abc", rendition = null, substituting = false, atmosSuffix = ""))
    }

    @Test
    fun `an upgraded rendition keeps its own key`() {
        assertEquals("abc#hifi", AudioCache.videoCacheKey("abc", rendition = "hifi", substituting = true, atmosSuffix = ""))
    }
}
