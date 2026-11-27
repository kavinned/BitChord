package com.music.bitchord

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.ui.components.diffPlaylistSelection
import com.music.bitchord.ui.removablePlaylistId
import com.music.bitchord.ui.screens.locatePlayingSongIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistCurrentTrackUxTest {
    private fun song(
        id: String = "v1",
        type: PlaybackSourceType? = PlaybackSourceType.BROWSE,
        sourceId: String? = "VLPL123",
        tier: QueueTier = QueueTier.CONTEXT,
        setVideoId: String? = null,
    ) = Song(
        videoId = id,
        title = id,
        artist = "a",
        thumbnailUrl = null,
        setVideoId = setVideoId,
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

    @Test
    fun locatePlayingSongIndexAccountsForHeaderItems() {
        val matches = listOf(
            IndexedValue(0, song(id = "v1")),
            IndexedValue(1, song(id = "v2")),
            IndexedValue(2, song(id = "v3")),
        )
        assertEquals(2, locatePlayingSongIndex(matches, song(id = "v2"), headerItemCount = 1))
        assertEquals(3, locatePlayingSongIndex(matches, song(id = "v2"), headerItemCount = 2))
        assertEquals(4, locatePlayingSongIndex(matches, song(id = "v2"), headerItemCount = 3))
    }

    @Test
    fun locatePlayingSongIndexPrefersSetVideoIdForDuplicates() {
        val matches = listOf(
            IndexedValue(0, song(id = "v1", setVideoId = "slot1")),
            IndexedValue(1, song(id = "v2", setVideoId = "slot2")),
            IndexedValue(2, song(id = "v1", setVideoId = "slot3")),
        )
        assertEquals(
            3,
            locatePlayingSongIndex(matches, song(id = "v1", setVideoId = "slot3"), headerItemCount = 1),
        )
    }

    @Test
    fun locatePlayingSongIndexReturnsNullWhenAbsentOrNoCurrent() {
        val matches = listOf(IndexedValue(0, song(id = "v1")))
        assertNull(locatePlayingSongIndex(matches, song(id = "v9"), headerItemCount = 1))
        assertNull(locatePlayingSongIndex(matches, null, headerItemCount = 1))
        assertNull(locatePlayingSongIndex(emptyList(), song(id = "v1"), headerItemCount = 1))
    }

    @Test
    fun diffPlaylistSelectionComputesAddedAndRemoved() {
        val playlists = listOf(
            UserPlaylist("p1", "One", "1 song", null),
            UserPlaylist("p2", "Two", "2 songs", null),
            UserPlaylist("p3", "Three", "3 songs", null),
        )
        val (added, removed) = diffPlaylistSelection(
            playlists = playlists,
            initialIds = setOf("p1", "p2"),
            finalIds = setOf("p2", "p3"),
        )
        assertEquals(listOf("p3"), added.map { it.playlistId })
        assertEquals(listOf("p1"), removed.map { it.playlistId })
    }

    @Test
    fun diffPlaylistSelectionIsEmptyWhenUnchanged() {
        val playlists = listOf(
            UserPlaylist("p1", "One", "1 song", null),
            UserPlaylist("p2", "Two", "2 songs", null),
        )
        val (added, removed) = diffPlaylistSelection(
            playlists = playlists,
            initialIds = setOf("p1"),
            finalIds = setOf("p1"),
        )
        assertEquals(emptyList<UserPlaylist>(), added)
        assertEquals(emptyList<UserPlaylist>(), removed)
    }
}
