package com.music.bitchord.ui.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the video engine learns from a seek, and which failures are worth
 * giving up on.
 *
 * The bugs this pins: a slow first prepare was measured as a seek and left
 * every later aim seconds ahead of the audio, and a momentary network drop
 * blacklisted a video for the whole session.
 */
class MusicVideoEngineTest {

    @Test
    fun `one slow landing cannot skew the aim`() {
        assertEquals(750L, nextSeekLead(0L, 60_000L))
        assertEquals(1_500L, nextSeekLead(1_500L, 60_000L))
    }

    @Test
    fun `an ordinary landing is averaged in`() {
        assertEquals(250L, nextSeekLead(250L, 250L))
        assertEquals(125L, nextSeekLead(250L, 0L))
    }

    @Test
    fun `decoder and container failures are terminal`() {
        assertTrue(isTerminalVideoError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(isTerminalVideoError(PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED))
        assertTrue(isTerminalVideoError(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(isTerminalVideoError(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES))
        assertTrue(isTerminalVideoError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertTrue(isTerminalVideoError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertTrue(isTerminalVideoError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
    }

    @Test
    fun `network and server failures are worth retrying`() {
        assertFalse(isTerminalVideoError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(isTerminalVideoError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertFalse(isTerminalVideoError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(isTerminalVideoError(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }
}
