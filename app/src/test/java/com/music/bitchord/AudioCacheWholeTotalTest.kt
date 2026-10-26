package com.music.bitchord

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A whole-track pass may only use YouTube's length when the entry being
 * filled *is* YouTube's. For a substituted rendition that length is another
 * file's, and using it stops the pass early while reporting success.
 */
class AudioCacheWholeTotalTest {

    @Test
    fun `a substituted rendition with no length is not given YouTube's`() {
        assertNull(AudioCache.wholeTrackTotal(substituting = true, renditionLength = 0, youtubeLength = 3_900_000))
    }

    @Test
    fun `a substituted rendition uses its own length`() {
        assertEquals(
            40_000_000L,
            AudioCache.wholeTrackTotal(substituting = true, renditionLength = 40_000_000, youtubeLength = 3_900_000),
        )
    }

    @Test
    fun `a plain rendition falls back to YouTube`() {
        assertEquals(
            3_900_000L,
            AudioCache.wholeTrackTotal(substituting = false, renditionLength = 0, youtubeLength = 3_900_000),
        )
    }

    @Test
    fun `no length anywhere means no pass`() {
        assertNull(AudioCache.wholeTrackTotal(substituting = false, renditionLength = 0, youtubeLength = null))
    }
}
