package com.music.bitchord

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.removablePlaylistId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistCurrentTrackUxTest {
    private fun song(
        id: String = "v1",
        type: PlaybackSourceType? = PlaybackSourceType.BROWSE,
        sourceId: String? = "VLPL123",
        tier: QueueTier = QueueTier.CONTEXT,
    ) = Song(
        videoId = id,
        title = id,
        artist = "a",
        thumbnailUrl = null,
        queueTier = tier,
        playbackSourceType = type,
        playbackSourceId = sourceId,
    )

    @Test
    fun playerUsesPlaybackSourceForBrowseSong() {
        assertEquals("VLPL123", removablePlaylistId(song(), "VLOTHER", fromPlayer = true))
    }

    @Test
    fun playerNeverTargetsAutoplay() {
        assertNull(removablePlaylistId(song(tier = QueueTier.AUTOPLAY), "VLPL123", fromPlayer = true))
    }

    @Test
    fun playerIgnoresNonBrowseSources() {
        assertNull(removablePlaylistId(song(type = PlaybackSourceType.SEARCH), null, fromPlayer = true))
        assertNull(removablePlaylistId(song(type = PlaybackSourceType.HOME), null, fromPlayer = true))
        assertNull(removablePlaylistId(song(type = null), null, fromPlayer = true))
    }

    @Test
    fun pageMenuUsesDetailBrowseId() {
        assertEquals("VLPL999", removablePlaylistId(song(), "VLPL999", fromPlayer = false))
        assertNull(removablePlaylistId(song(), null, fromPlayer = false))
    }
}
