package com.music.bitchord

import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.download.WebmTagger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * WebM tags are appended past the window the reader fetches, so the reader has
 * to look at the tail as well as the head.
 */
class EmbeddedLyricsTailTest {

    @Test
    fun `a tail is only read when the head did not already reach the end`() {
        assertEquals(2L, EmbeddedLyrics.tailOffset(10, 8))
        assertNull(EmbeddedLyrics.tailOffset(8, 8))
        assertNull(EmbeddedLyrics.tailOffset(0, 8))
    }

    @Test
    fun `webm lyrics appended past the head read are found in the tail`() {
        val lrc = "[00:01.00]a line"
        val headLimit = 8 * 1024 * 1024
        // An EBML header, then a Segment declared as running to the end of the
        // file, so the tagger appends its Tags after the audio.
        val header = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) +
            byteArrayOf(0x84.toByte()) + ByteArray(4)
        val unknownSize = byteArrayOf(0x01) + ByteArray(7) { 0xFF.toByte() }
        val audio = ByteArray(headLimit + 1024 * 1024)
        val original = header +
            byteArrayOf(0x18, 0x53.toByte(), 0x80.toByte(), 0x67) + unknownSize + audio
        val tagged = WebmTagger.tag(original, "t", "a", null, lrc, null, "image/jpeg")
        assertTrue(tagged.size > headLimit)

        // The front of the file, which is all the reader used to fetch, has no
        // tags in it — this is the bug the tail read exists for.
        assertNull(EmbeddedLyrics.fromBytes(tagged.copyOfRange(0, headLimit)))

        val file = File(packageDir(), "track.webm").apply { writeBytes(tagged) }
        assertEquals(lrc, EmbeddedLyrics.read(file))
    }

    private fun packageDir(): File =
        createTempDirectory("lyricstail").toFile().also(File::deleteOnExit)
}
