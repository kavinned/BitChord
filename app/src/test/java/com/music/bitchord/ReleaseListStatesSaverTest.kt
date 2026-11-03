package com.music.bitchord

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Release pages' scroll offsets have to survive the activity being rebuilt
 * (theme, locale or font-scale change) — see [releaseListStatesSaver].
 */
class ReleaseListStatesSaverTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test
    fun restoresEveryPagesScrollOffset() {
        val states = mutableMapOf(
            "album:one" to LazyListState(12, 34),
            "playlist:two" to LazyListState(56, 78),
        )

        val saved = with(releaseListStatesSaver) { scope.save(states) }
        val restored = releaseListStatesSaver.restore(saved!!)!!

        assertEquals(2, restored.size)
        assertEquals(12, restored.getValue("album:one").firstVisibleItemIndex)
        assertEquals(34, restored.getValue("album:one").firstVisibleItemScrollOffset)
        assertEquals(56, restored.getValue("playlist:two").firstVisibleItemIndex)
        assertEquals(78, restored.getValue("playlist:two").firstVisibleItemScrollOffset)
    }
}
