package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenShelfKeyTest {

    @Test
    fun aBrowseIdIdentifiesTheCard() {
        val item = ShelfItem("Album", "Artist", null, "video", "browse")
        assertEquals("browse", item.shelfKey())
    }

    @Test
    fun aTrackFallsBackToItsVideoId() {
        val item = ShelfItem("Song", "Artist", null, "video", null)
        assertEquals("video", item.shelfKey())
    }

    @Test
    fun aCardWithNeitherFallsBackToItsTitle() {
        val item = ShelfItem("Song", "Artist", null, null, null)
        assertEquals("Song", item.shelfKey())
    }
}
