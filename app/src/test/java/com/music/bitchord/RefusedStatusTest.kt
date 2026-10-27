package com.music.bitchord

import com.music.bitchord.playback.statusIsWorthRetrying
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which HTTP refusals Media3 should spend its retry budget on.
 *
 * The bug this pins: a pinned Nebula FLAC whose CDN answered 502 was retried at
 * the default backoff, and the app only reached its recovery path after
 * fifty-five seconds of silence.
 */
class RefusedStatusTest {

    @Test
    fun `a dead url is not asked again`() {
        assertFalse(statusIsWorthRetrying(403))
        assertFalse(statusIsWorthRetrying(404))
        assertFalse(statusIsWorthRetrying(410))
        assertFalse(statusIsWorthRetrying(500))
        assertFalse(statusIsWorthRetrying(502))
        assertFalse(statusIsWorthRetrying(503))
    }

    @Test
    fun `a timeout is worth another go`() {
        assertTrue(statusIsWorthRetrying(408))
    }

    @Test
    fun `a rate limit is worth another go`() {
        assertTrue(statusIsWorthRetrying(429))
    }
}
