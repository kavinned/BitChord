package com.music.bitchord

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The total a ranged probe learns: Content-Range first, Content-Length otherwise. */
class ContentRangeTotalTest {

    @Test
    fun `a content range names the whole file`() {
        assertEquals(30_000_000L, AudioCache.contentRangeTotal("bytes 0-0/30000000", null))
    }

    @Test
    fun `an unknown range total falls back to content length`() {
        assertEquals(3_416_463L, AudioCache.contentRangeTotal("bytes 0-0/*", "3416463"))
    }

    @Test
    fun `no headers means no answer`() {
        assertNull(AudioCache.contentRangeTotal(null, null))
    }

    @Test
    fun `a zero length is not a total`() {
        assertNull(AudioCache.contentRangeTotal(null, "0"))
    }

    @Test
    fun `an error response names no total`() {
        assertNull(AudioCache.probeTotal(404, null, "204"))
        assertNull(AudioCache.probeTotal(500, "bytes 0-0/30000000", "30000000"))
    }

    @Test
    fun `a successful response names the total`() {
        assertEquals(30_000_000L, AudioCache.probeTotal(206, "bytes 0-0/30000000", null))
        assertEquals(3_416_463L, AudioCache.probeTotal(200, null, "3416463"))
    }
}
