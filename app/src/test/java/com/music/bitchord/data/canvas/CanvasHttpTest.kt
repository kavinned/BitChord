package com.music.bitchord.data.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Canvas hosts are not ours, and one that drips bytes used to hold a lookup
 * for a whole read window. The client needs a ceiling on the whole call.
 */
class CanvasHttpTest {

    @Test
    fun `a canvas lookup has a hard call timeout`() {
        assertEquals(8_000, canvasClient.callTimeoutMillis)
    }
}
