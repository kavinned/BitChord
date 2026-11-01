package com.music.bitchord.data.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canvas entries hold the provider's URL, and Apple's carries an expiring
 * token. A cached answer is only reusable while its URL can still be served.
 */
class CanvasRepositoryCacheTest {

    private val clip = CanvasArtwork(url = "https://cdn.example.com/canvas.mp4", title = "t", artist = "a")
    private fun entry(artwork: CanvasArtwork?, withAlbum: Boolean, at: Long) =
        CanvasRepository.Entry(artwork, withAlbum, at)

    @Test
    fun `a hit is reused until it is half an hour old`() {
        val hit = entry(clip, withAlbum = false, at = 1_000L)

        assertTrue(hit.reusable(withAlbum = true, now = 1_000L + CanvasRepository.CACHE_TTL_MS))
        assertFalse(hit.reusable(withAlbum = true, now = 1_000L + CanvasRepository.CACHE_TTL_MS + 1))
    }

    @Test
    fun `a miss expires on the same clock`() {
        val miss = entry(null, withAlbum = true, at = 1_000L)

        assertTrue(miss.reusable(withAlbum = true, now = 1_000L + CanvasRepository.CACHE_TTL_MS))
        assertFalse(miss.reusable(withAlbum = true, now = 1_000L + CanvasRepository.CACHE_TTL_MS + 1))
    }

    /** A miss reached before the album was known is worth asking again. */
    @Test
    fun `a blind miss stands only until an album is available`() {
        val miss = entry(null, withAlbum = false, at = 0L)

        assertTrue(miss.reusable(withAlbum = false, now = 0L))
        assertFalse(miss.reusable(withAlbum = true, now = 0L))
    }
}
