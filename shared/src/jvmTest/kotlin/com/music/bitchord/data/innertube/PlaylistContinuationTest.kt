package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A playlist's second page as YouTube Music sends it now: rows appended by an
 * `onResponseReceivedActions` action rather than under `continuationContents`.
 * Shape trimmed from a live response for a 200-song playlist (#947).
 */
class PlaylistContinuationTest {

    private fun row(videoId: String, setVideoId: String) = """
        { "musicResponsiveListItemRenderer": {
          "playlistItemData": { "videoId": "$videoId", "playlistSetVideoId": "$setVideoId" },
          "flexColumns": [
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Song $videoId" } ] } } },
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Artist" } ] } } }
          ]
        } }"""

    private val appendedPage = Json.parseToJsonElement(
        """
        { "onResponseReceivedActions": [ { "appendContinuationItemsAction": { "continuationItems": [
          ${row("aaaaaaaaaaa", "SET1")},
          ${row("bbbbbbbbbbb", "SET2")},
          { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "PAGE3" } } } }
        ] } } ] }
        """,
    )

    @Test
    fun `an appended continuation page reads as the playlist's next rows`() {
        val page = assertNotNull(InnertubeParser.parsePlaylistShelf(appendedPage))
        assertEquals(listOf("SET1", "SET2"), page.songs.map { it.setVideoId })
        assertEquals("PAGE3", page.continuation)
    }

    @Test
    fun `multiple appendContinuationItemsAction blocks are all aggregated`() {
        val multiAction = Json.parseToJsonElement(
            """
            { "onResponseReceivedActions": [
              { "appendContinuationItemsAction": { "continuationItems": [
                ${row("aaaaaaaaaaa", "SET1")}
              ] } },
              { "appendContinuationItemsAction": { "continuationItems": [
                ${row("bbbbbbbbbbb", "SET2")},
                { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "PAGE_NEXT" } } } }
              ] } }
            ] }
            """,
        )
        val page = assertNotNull(InnertubeParser.parsePlaylistShelf(multiAction))
        assertEquals(listOf("SET1", "SET2"), page.songs.map { it.setVideoId })
        assertEquals("PAGE_NEXT", page.continuation)
    }

    @Test
    fun `duplicate videoId entries with distinct setVideoId are preserved`() {
        val dupPage = Json.parseToJsonElement(
            """
            { "onResponseReceivedActions": [ { "appendContinuationItemsAction": { "continuationItems": [
              ${row("samevideo01", "SET1")},
              ${row("samevideo01", "SET2")}
            ] } } ] }
            """,
        )
        val page = assertNotNull(InnertubeParser.parsePlaylistShelf(dupPage))
        assertEquals(listOf("SET1", "SET2"), page.songs.map { it.setVideoId })
        assertEquals(listOf("samevideo01", "samevideo01"), page.songs.map { it.videoId })
    }

    @Test
    fun `trailing continuationItemRenderer wins over an earlier one in playlist scope`() {
        val twoTokens = Json.parseToJsonElement(
            """
            { "onResponseReceivedActions": [ { "appendContinuationItemsAction": { "continuationItems": [
              { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "STALE_HEAD" } } } },
              ${row("aaaaaaaaaaa", "SET1")},
              { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "TAIL_NEXT" } } } }
            ] } } ] }
            """,
        )
        val page = assertNotNull(InnertubeParser.parsePlaylistShelf(twoTokens))
        assertEquals("TAIL_NEXT", page.continuation)
    }
}
