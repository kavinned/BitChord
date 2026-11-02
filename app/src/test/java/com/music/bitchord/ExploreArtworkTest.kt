package com.music.bitchord

import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.ui.applyArtwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * How a resolved Explore thumbnail is put back into the grid.
 *
 * The point of the identity checks here is not tidiness: every emission of the
 * Explore state recomposes the whole grid, so a section that gained nothing has
 * to come back as the same instance for Compose to skip it — and a pass that
 * changed nothing at all has to come back as the same list for the state flow to
 * suppress the emission outright.
 */
class ExploreArtworkTest {

    private fun item(id: String, thumb: String? = null, params: String? = "p") =
        MoodGenre(title = id, browseId = id, params = params, thumbnailUrl = thumb)

    private val sections = listOf(
        MoodGenreSection("one", listOf(item("a"), item("b"))),
        MoodGenreSection("two", listOf(item("c"))),
    )

    @Test
    fun `artwork lands on the category it was resolved for`() {
        val updated = applyArtwork(sections, mapOf("b|p" to "http://x/b"))
        assertNull(updated[0].items[0].thumbnailUrl)
        assertEquals("http://x/b", updated[0].items[1].thumbnailUrl)
        assertNull(updated[1].items[0].thumbnailUrl)
    }

    @Test
    fun `a section that gained nothing is the same instance`() {
        val updated = applyArtwork(sections, mapOf("a|p" to "http://x/a"))
        assertSame(sections[0].items[1], updated[0].items[1])
        assertSame(sections[1], updated[1])
    }

    @Test
    fun `nothing to apply returns the list it was given`() {
        assertSame(sections, applyArtwork(sections, emptyMap()))
    }

    @Test
    fun `the url an item already carries is not a change`() {
        val already = listOf(MoodGenreSection("one", listOf(item("a", "http://x/a"))))
        assertSame(already, applyArtwork(already, mapOf("a|p" to "http://x/a")))
    }

    @Test
    fun `a category with no params is keyed by that`() {
        val none = listOf(MoodGenreSection("one", listOf(item("a", params = null))))
        val updated = applyArtwork(none, mapOf("a|null" to "http://x/a"))
        assertEquals("http://x/a", updated[0].items[0].thumbnailUrl)
    }
}
