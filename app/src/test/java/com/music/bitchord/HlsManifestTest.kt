package com.music.bitchord

import com.music.bitchord.download.ManifestFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the HLS reader will and will not save whole.
 *
 * `#EXT-X-BYTERANGE` used to be dropped like any other `#` line, which left the
 * shared resource named once per range: the download saved it several times,
 * joined the copies and wrote a file far too large to play, reporting success.
 */
class HlsManifestTest {

    private val playlist = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:4.0,
        segment-1.m4s
        #EXTINF:4.0,
        segment-2.m4s
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun `a plain playlist yields its init segment and fragments`() {
        val (init, media) = ManifestFile.hlsSegments(playlist)
        assertEquals("init.mp4", init)
        assertEquals(listOf("segment-1.m4s", "segment-2.m4s"), media)
    }

    @Test
    fun `a byte-range playlist is refused rather than saved corrupted`() {
        val ranged = playlist.replace(
            "#EXTINF:4.0,\nsegment-1.m4s",
            "#EXTINF:4.0,\n#EXT-X-BYTERANGE:1000@0\nsegment-1.m4s",
        )
        assertEquals(
            "Byte-range HLS cannot be saved",
            runCatching { ManifestFile.hlsSegments(ranged) }.exceptionOrNull()?.message,
        )
    }
}
