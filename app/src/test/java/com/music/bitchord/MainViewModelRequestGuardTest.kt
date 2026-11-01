package com.music.bitchord

import com.music.bitchord.auth.GoogleAccountSession
import com.music.bitchord.auth.YouTubeProfile
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.ui.accountDetailsUpdate
import com.music.bitchord.ui.channelsForListener
import com.music.bitchord.ui.isStillOpenCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A response may only land for the state that asked for it.
 *
 * Account channels, fetched account details and mood/genre shelves all arrive
 * asynchronously; each must be rejected when the listener or category it was
 * requested under is no longer the one on screen.
 */
class MainViewModelRequestGuardTest {

    private val channel = AccountChannel(
        name = "Brand", subtitle = "@brand", thumbnailUrl = null,
        pageId = "113355", dataSyncId = "SESSION_B", activeOnWeb = false,
    )

    @Test fun `channels are accepted for the identity that asked`() {
        assertEquals(listOf(channel), channelsForListener("a:personal", "a:personal", listOf(channel)))
    }

    @Test fun `channels from a previous account are dropped after a switch`() {
        assertNull(channelsForListener("a:personal", "b:personal", listOf(channel)))
    }

    @Test fun `channels are dropped after a profile switch or sign-out`() {
        assertNull(channelsForListener("a:personal", "a:brand", listOf(channel)))
        assertNull(channelsForListener("a:personal", null, listOf(channel)))
    }

    private val stored = GoogleAccountSession(
        accountId = "a",
        cookie = "cookie",
        name = "Ada Lovelace",
        email = "ada@example.com",
        profiles = listOf(
            YouTubeProfile(
                profileId = "p", name = "Ada Lovelace",
                handle = "ada@example.com", avatar = "https://x/avatar",
            ),
        ),
        activeProfileId = "p",
    )

    @Test fun `a fetch that confirms the stored session changes nothing`() {
        assertEquals(
            stored,
            accountDetailsUpdate(stored, Account("Ada Lovelace", "ada@example.com", "https://x/avatar"), "p"),
        )
    }

    @Test fun `a renamed channel changes the stored session`() {
        assertNotEquals(
            stored,
            accountDetailsUpdate(stored, Account("Analytical Engine", "ada@example.com", "https://x/avatar"), "p"),
        )
    }

    private val chill = MoodGenre(title = "Chill", browseId = "FEmusic_moods_and_genres_chill", params = "params")

    @Test fun `a response for the category still open lands`() {
        assertTrue(isStillOpenCategory(chill, chill.copy(thumbnailUrl = "https://x/art")))
    }

    @Test fun `a slow response for a category already left is dropped`() {
        assertFalse(isStillOpenCategory(chill, MoodGenre("Chill", chill.browseId, "other")))
        assertFalse(isStillOpenCategory(chill, MoodGenre("Focus", "FEmusic_moods_and_genres_focus", chill.params)))
        assertFalse(isStillOpenCategory(chill, null))
    }
}
