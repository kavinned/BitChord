package com.music.bitchord

import com.music.bitchord.data.YtMusicRepository.SongPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.PlaylistSync
import com.music.bitchord.download.SavedCollection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What decides which tracks a playlist check fetches. All of it fails quietly: a
 * listing cut short is a song that never arrives, and a wrong answer to "is this
 * new" brings back a song the listener deleted.
 */
class PlaylistSyncTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)

    private fun songs(vararg ids: String) = ids.map(::song)

    private fun page(vararg ids: String, next: String? = null, suggested: List<Song> = emptyList()) =
        Result.success(SongPage(songs(*ids), next, suggested))

    private fun record(id: String = "VLPL1", vararg ids: String, playlist: Boolean = true) =
        SavedCollection(id = id, title = id, playlist = playlist, videoIds = ids.toList())

    private fun ids(list: List<Song>) = list.map { it.videoId }

    @Test
    fun `a listing follows every page to the end`() = runTest {
        val pages = mapOf("t2" to page("c", "d", next = "t3"), "t3" to page("e"))
        val listing = PlaylistSync.readWholePlaylist(
            first = { page("a", "b", next = "t2") },
            more = { pages.getValue(it) },
        )
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(listing.songs))
        assertTrue(listing.complete)
    }

    @Test
    fun `a page that fails keeps what came before and says it is incomplete`() = runTest {
        val listing = PlaylistSync.readWholePlaylist(
            first = { page("a", next = "t2") },
            more = { Result.failure(IllegalStateException("offline")) },
        )
        assertEquals(listOf("a"), ids(listing.songs))
        assertFalse(listing.complete)
    }

    @Test
    fun `a failed first page is incomplete, not an empty playlist`() = runTest {
        val listing = PlaylistSync.readWholePlaylist(
            first = { Result.failure(IllegalStateException("offline")) },
            more = { error("nothing to continue") },
        )
        assertTrue(listing.songs.isEmpty())
        assertFalse(listing.complete)
    }

    @Test
    fun `a feed that loops back on itself stops and says it did not finish`() = runTest {
        var requests = 0
        val listing = PlaylistSync.readWholePlaylist(
            first = { page("a", next = "t") },
            more = {
                requests++
                page("a", next = "t")
            },
        )
        // One request: the second page added nothing and handed back the token
        // that had just been followed, which is a loop rather than an end.
        assertEquals(1, requests)
        assertEquals(listOf("a"), ids(listing.songs))
        assertFalse(listing.complete)
    }

    @Test
    fun `a duplicate page is followed to the tracks behind it`() = runTest {
        // YouTube can repeat a page with a fresh token still attached. The tail
        // behind it is real, so the walk follows rather than trusting the repeat
        // as the end of the playlist — which would hide it on every later check too.
        val pages = mapOf(
            "t2" to page("a", next = "t3"),
            "t3" to page("a", "c"),
        )
        val listing = PlaylistSync.readWholePlaylist(
            first = { page("a", next = "t2") },
            more = { pages.getValue(it) },
        )
        assertEquals(listOf("a", "c"), ids(listing.songs))
        assertTrue(listing.complete)
    }

    @Test
    fun `a run of pages that add nothing ends the walk as incomplete`() = runTest {
        // A feed that keeps handing out fresh tokens while repeating itself would
        // otherwise be walked for as long as the app is open.
        var requests = 0
        val listing = PlaylistSync.readWholePlaylist(
            first = { page("a", next = "t0") },
            more = {
                requests++
                page("a", next = "t$requests")
            },
        )
        assertEquals(2, requests)
        assertEquals(listOf("a"), ids(listing.songs))
        assertFalse(listing.complete)
    }

    @Test
    fun `a check that read every playlist is not due again for six hours`() {
        val now = 1_000_000_000L
        assertEquals(now, PlaylistSync.lastCheckStamp(whole = true, now = now))
    }

    @Test
    fun `a check that could not read everything comes due in five minutes`() {
        // One playlist deleted or made private online must not turn every
        // foreground into a full re-read of every downloaded playlist.
        val now = 1_000_000_000L
        val sixHours = 6L * 60 * 60 * 1000
        val fiveMinutes = 5L * 60 * 1000
        val stamp = PlaylistSync.lastCheckStamp(whole = false, now = now)
        assertTrue("a partial check is not due yet", now - stamp < sixHours)
        assertTrue("a partial check is due within five minutes", now + fiveMinutes - stamp >= sixHours)
    }

    @Test
    fun `suggested tracks are not part of the playlist`() = runTest {
        val listing = PlaylistSync.readWholePlaylist(
            first = { page("a", next = "t2", suggested = songs("x")) },
            more = { page("b", suggested = songs("y")) },
        )
        assertEquals(listOf("a", "b"), ids(listing.songs))
    }

    @Test
    fun `only tracks the record has never named are new`() {
        // "b" was downloaded and later deleted, so it is named but not on disk.
        val fresh = PlaylistSync.newTracks(songs("a", "b", "c"), record("VLPL1", "a", "b"))
        assertEquals(listOf("c"), ids(fresh))
    }

    @Test
    fun `a track that failed is left out of the record so it is tried again`() {
        val listing = songs("a", "b", "c")
        val order = PlaylistSync.listingToRecord(listing, record("VLPL1", "a"), handled = setOf("c"))
        assertEquals(listOf("a", "c"), ids(order))
    }

    @Test
    fun `only a YouTube playlist is checked, under the id YouTube answers to`() {
        assertEquals("VLPL1", PlaylistSync.syncableBrowseId(record("VLPL1")))
        assertEquals("VLPL1", PlaylistSync.syncableBrowseId(record("PL1")))
        assertEquals("VLOLAK5UY1", PlaylistSync.syncableBrowseId(record("OLAK5UY1")))
        assertNull(PlaylistSync.syncableBrowseId(record("MPREb_1", playlist = false)))
        assertNull(PlaylistSync.syncableBrowseId(record("VLOLAK1", playlist = false)))
        assertNull(PlaylistSync.syncableBrowseId(record("local:playlist:sp_local_1")))
        assertNull(PlaylistSync.syncableBrowseId(record("spotify:playlist:1")))
    }

    @Test
    fun `a generated page is never checked, so its record cannot grow forever`() {
        // Mixes, the liked page and uploads are rebuilt by YouTube as you listen:
        // every check would find a fresh tail and download it again.
        assertNull(PlaylistSync.syncableBrowseId(record("RDEM1")))
        assertNull(PlaylistSync.syncableBrowseId(record("RDCLAK1")))
        assertNull(PlaylistSync.syncableBrowseId(record("VLLM1")))
        assertNull(PlaylistSync.syncableBrowseId(record("LMsong1")))
        assertNull(PlaylistSync.syncableBrowseId(record("LLchannel1")))
        assertNull(PlaylistSync.syncableBrowseId(record("UUupload1")))
    }

    @Test
    fun `deleting a release keeps the tracks another release lists`() {
        val collections = mapOf(
            "one" to record("one", "a", "b", "c"),
            "two" to record("two", "b"),
        )
        val saved = mapOf("a" to "file:a", "b" to "file:b", "c" to "file:c")
        assertEquals(listOf("a", "c"), Downloads.tracksOnlyIn("one", collections, saved))
    }

    @Test
    fun `one file under two ids counts as shared`() {
        // "video" was swapped for "track" on the way down: both name one file.
        val collections = mapOf(
            "one" to record("one", "video", "a"),
            "two" to record("two", "track"),
        )
        val saved = mapOf("video" to "file:x", "track" to "file:x", "a" to "file:a")
        assertEquals(listOf("a"), Downloads.tracksOnlyIn("one", collections, saved))
    }

    @Test
    fun `a small batch is fetched without asking`() {
        assertEquals(
            PlaylistSync.AskOutcome.Fetch,
            PlaylistSync.askOutcome(PlaylistSync.ASK_ABOVE, approved = false, canNotify = true),
        )
    }

    @Test
    fun `a large batch asks through notifications when they can be posted`() {
        assertEquals(
            PlaylistSync.AskOutcome.Ask,
            PlaylistSync.askOutcome(PlaylistSync.ASK_ABOVE + 1, approved = false, canNotify = true),
        )
    }

    @Test
    fun `a large batch with notifications off is recorded, not dropped`() {
        // The ask used to be posted and lost, leaving the playlist stuck forever.
        assertEquals(
            PlaylistSync.AskOutcome.RecordOnly,
            PlaylistSync.askOutcome(PlaylistSync.ASK_ABOVE + 1, approved = false, canNotify = false),
        )
    }

    @Test
    fun `an approved batch is fetched however large`() {
        assertEquals(
            PlaylistSync.AskOutcome.Fetch,
            PlaylistSync.askOutcome(PlaylistSync.ASK_ABOVE * 100, approved = true, canNotify = false),
        )
    }
}
