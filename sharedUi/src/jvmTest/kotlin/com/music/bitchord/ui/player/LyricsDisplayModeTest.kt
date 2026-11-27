package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsDisplayModeTest {
    private val ready = LyricsTranslationUiState.Ready(listOf(LyricLine(timeMs = 0L, text = "x")))

    @Test
    fun `parse maps known names`() {
        assertEquals(LyricsDisplayMode.Translated, parseLyricsDisplayMode("TRANSLATED"))
        assertEquals(LyricsDisplayMode.Romanized, parseLyricsDisplayMode("ROMANIZED"))
        assertEquals(LyricsDisplayMode.Original, parseLyricsDisplayMode("ORIGINAL"))
    }

    @Test
    fun `parse falls back to original`() {
        assertEquals(LyricsDisplayMode.Original, parseLyricsDisplayMode(null))
        assertEquals(LyricsDisplayMode.Original, parseLyricsDisplayMode("BOGUS"))
        assertEquals(LyricsDisplayMode.Original, parseLyricsDisplayMode(""))
    }

    @Test
    fun `same language shows originals`() {
        assertEquals(
            LyricsDisplayMode.Original,
            effectiveDisplayMode(LyricsDisplayMode.Translated, LyricsTranslationUiState.SameLanguage, LyricsTranslationUiState.Idle),
        )
        assertEquals(
            LyricsDisplayMode.Original,
            effectiveDisplayMode(LyricsDisplayMode.Romanized, LyricsTranslationUiState.Idle, LyricsTranslationUiState.SameLanguage),
        )
    }

    @Test
    fun `ready translation is shown`() {
        assertEquals(
            LyricsDisplayMode.Translated,
            effectiveDisplayMode(LyricsDisplayMode.Translated, ready, LyricsTranslationUiState.Idle),
        )
    }

    @Test
    fun `ready romanization is shown`() {
        assertEquals(
            LyricsDisplayMode.Romanized,
            effectiveDisplayMode(LyricsDisplayMode.Romanized, LyricsTranslationUiState.Idle, ready),
        )
    }

    @Test
    fun `idle loading and error fall back to originals`() {
        assertEquals(
            LyricsDisplayMode.Original,
            effectiveDisplayMode(LyricsDisplayMode.Romanized, LyricsTranslationUiState.Idle, LyricsTranslationUiState.Idle),
        )
        assertEquals(
            LyricsDisplayMode.Original,
            effectiveDisplayMode(LyricsDisplayMode.Translated, LyricsTranslationUiState.Loading, LyricsTranslationUiState.Idle),
        )
    }

    @Test
    fun `ready state of the other kind is ignored`() {
        assertEquals(
            LyricsDisplayMode.Original,
            effectiveDisplayMode(LyricsDisplayMode.Translated, LyricsTranslationUiState.Idle, ready),
        )
    }

    @Test
    fun `original desired never shows a version`() {
        assertEquals(
            LyricsDisplayMode.Original,
            effectiveDisplayMode(LyricsDisplayMode.Original, ready, ready),
        )
    }
}
