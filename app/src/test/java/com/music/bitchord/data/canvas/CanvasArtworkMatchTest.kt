package com.music.bitchord.data.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The match check the repository runs on every provider's answer.
 *
 * B-13: the Pathfinder search has no documented field for the track's own
 * name or artists, so it reports nulls. [CanvasArtwork.matches] reads a null
 * as "could not prove it" and skips that part of the check — the fix had the
 * response echo the request instead, which made the check compare the
 * question with itself and pass every time. REST still fills the fields in,
 * and those are still checked.
 */
class CanvasArtworkMatchTest {

    private fun clip(title: String? = null, artist: String? = null, album: String? = null) =
        CanvasArtwork(
            url = "https://cdn.example.com/canvas.mp4",
            title = title,
            artist = artist,
            album = album,
        )

    @Test
    fun `a hit that could not prove its metadata is let through`() {
        assertTrue(clip().matches("Song", "Artist", "Album"))
    }

    @Test
    fun `a hit naming another artist is refused`() {
        assertFalse(clip(title = "Song", artist = "Other Artist").matches("Song", "Artist", null))
    }
}
