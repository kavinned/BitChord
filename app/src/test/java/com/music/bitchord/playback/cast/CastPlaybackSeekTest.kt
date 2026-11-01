package com.music.bitchord.playback.cast

import androidx.media3.common.C
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a cast seek may stop being shown at the requested position.
 *
 * The receiver answers a seek with a status that still reports the old
 * position; treating that reply as settled is what made the bar snap back
 * before it jumped forward again.
 */
class CastPlaybackSeekTest {

    @Test
    fun `a reply from the old position does not settle the seek`() {
        assertFalse(CastPlayback.seekSettled(live = 200_000L, requested = 50_000L))
        assertFalse(CastPlayback.seekSettled(live = 50_000L, requested = 200_000L))
    }

    @Test
    fun `a reply at the requested position settles it, either direction`() {
        assertTrue(CastPlayback.seekSettled(live = 50_400L, requested = 50_000L))
        assertTrue(CastPlayback.seekSettled(live = 49_600L, requested = 50_000L))
        assertTrue(CastPlayback.seekSettled(live = 200_000L, requested = 200_000L))
    }

    @Test
    fun `an unknown receiver position never settles`() {
        assertFalse(CastPlayback.seekSettled(live = C.TIME_UNSET, requested = 50_000L))
    }
}
