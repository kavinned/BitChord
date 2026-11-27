package com.music.bitchord

import com.music.bitchord.ui.retryingOrNull
import com.music.bitchord.ui.shouldAdvanceEmptyPage
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page's fill-in rides out a dropped continuation request (#1178). */
class FillInRetryTest {

    @Test
    fun `a page that fails twice is fetched on the third try`() = runTest {
        var tries = 0
        val page = retryingOrNull(longArrayOf(2_000L, 5_000L, 15_000L)) { if (++tries < 3) null else "page" }
        assertEquals("page", page)
        assertEquals(3, tries)
        assertEquals(7_000L, currentTime)
    }

    @Test
    fun `a page that never comes gives up after the last wait`() = runTest {
        var tries = 0
        assertNull(retryingOrNull(longArrayOf(2_000L, 5_000L)) { tries++; null })
        assertEquals(3, tries)
    }

    @Test
    fun `a page that comes at once is not waited on`() = runTest {
        assertEquals("page", retryingOrNull(longArrayOf(2_000L)) { "page" })
        assertEquals(0L, currentTime)
    }

    @Test
    fun `empty pages advance up to the limit only when the token moves on`() {
        assertTrue(shouldAdvanceEmptyPage("TOK1", "TOK2", emptyPages = 1, maxEmptyPages = 2))
        assertTrue(shouldAdvanceEmptyPage("TOK2", "TOK3", emptyPages = 2, maxEmptyPages = 2))
        assertFalse(shouldAdvanceEmptyPage("TOK3", "TOK4", emptyPages = 3, maxEmptyPages = 2))
        assertFalse(shouldAdvanceEmptyPage("TOK1", "TOK1", emptyPages = 1, maxEmptyPages = 2))
        assertFalse(shouldAdvanceEmptyPage("TOK1", null, emptyPages = 1, maxEmptyPages = 2))
    }
}
