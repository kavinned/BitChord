package com.music.bitchord

import com.music.bitchord.playback.StreamChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Refusing one source must not silence the rest.
 *
 * The bug this pins: a pinned Nebula URL answering 502 refused the *track*, so
 * `resolveWithModulePriority` skipped the whole module race and JioSaavn — ranked
 * below it and possibly holding the song — was never asked.
 */
class StreamChoiceRefusalTest {

    @Before
    fun setUp() = StreamChoice.clearForTest()

    @After
    fun tearDown() = StreamChoice.clearForTest()

    @Test
    fun `a refused source is skipped for that track only`() {
        StreamChoice.refuse("nebula", "abc", 502)

        assertTrue(StreamChoice.refusedNow("nebula", "abc"))
        assertFalse(StreamChoice.refusedNow("jiosaavn", "abc"))
        assertFalse(StreamChoice.refusedNow("nebula", "xyz"))
    }

    @Test
    fun `a track-wide refusal covers every source`() {
        StreamChoice.refuseSubstitutes("abc")

        assertTrue(StreamChoice.refusedNow("nebula", "abc"))
        assertTrue(StreamChoice.refusedNow(null, "abc"))
    }

    @Test
    fun `the refused set names only what is refused for this track`() {
        StreamChoice.refuse("nebula", "abc", 403)
        StreamChoice.refuse("jiosaavn", "abc", 0)
        StreamChoice.refuse("nebula", "xyz", 403)

        assertEquals(setOf("nebula", "jiosaavn"), StreamChoice.refusing("abc"))
        assertEquals(emptySet<String>(), StreamChoice.refusing("other"))
    }

    @Test
    fun `a status outlives a source that merely went quiet`() {
        assertEquals(10 * 60 * 1000L, StreamChoice.refusalLifetimeMs(502))
        assertEquals(2 * 60 * 1000L, StreamChoice.refusalLifetimeMs(0))
    }
}
