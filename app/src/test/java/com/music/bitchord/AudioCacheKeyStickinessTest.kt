package com.music.bitchord

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A play's cache entry may not change under it.
 *
 * The first open fixes its key before the resolver has picked a source, so a
 * cold play starts on the plain `#alt` entry and only learns the pinned source
 * afterwards. Switching to the source-keyed entry on the next open would split
 * one file across two entries and leave the first one permanently partial.
 */
class AudioCacheKeyStickinessTest {

    private fun key(sourceId: String?, served: Map<String, String>) = AudioCache.cacheKeyFor(
        videoId = "abc",
        rendition = null,
        substituting = true,
        atmosSuffix = "",
        sourceId = sourceId,
        served = served,
    )

    @Test
    fun `a cold play keeps the entry its first open chose`() {
        assertEquals("abc#alt", key("nebula", mapOf("abc#alt" to "nebula")))
    }

    @Test
    fun `a prefetched entry wins once it has been opened`() {
        assertEquals(
            "abc#alt:nebula",
            key("nebula", mapOf("abc#alt" to "nebula", "abc#alt:nebula" to "nebula")),
        )
    }

    @Test
    fun `a stale entry from another source does not win`() {
        assertEquals("abc#alt:nebula", key("nebula", mapOf("abc#alt" to "")))
    }

    @Test
    fun `no pin keeps the plain key`() {
        assertEquals("abc#alt", key(null, mapOf("abc#alt:nebula" to "nebula")))
    }
}
