package com.music.bitchord

import com.music.bitchord.data.boundedString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ceiling the lyric, canvas and scrape helpers read through.
 *
 * A provider answering with something enormous used to be read into memory
 * whole; the OutOfMemoryError that followed was an `Error`, so the surrounding
 * `runCatching` swallowed it and the lookup just looked empty.
 */
class BoundedBodyTest {

    private val text = "text/plain".toMediaType()

    @Test
    fun `a body within the cap comes back whole`() {
        val body = "x".repeat(1024).toResponseBody(text)
        assertEquals(1024, body.boundedString(maxBytes = 2048).orEmpty().length)
    }

    @Test
    fun `a body past the cap is refused, not truncated`() {
        val body = "x".repeat(4096).toResponseBody(text)
        assertNull(body.boundedString(maxBytes = 2048))
    }
}
