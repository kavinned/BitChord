package com.music.bitchord

import com.music.bitchord.ui.components.clampTopFadeAlpha
import org.junit.Assert.assertEquals
import org.junit.Test

class TopFadeBlurAlphaTest {
    @Test
    fun inRangeValuesPassThrough() {
        assertEquals(0f, clampTopFadeAlpha(0f), 0f)
        assertEquals(0.4f, clampTopFadeAlpha(0.4f), 0f)
        assertEquals(1f, clampTopFadeAlpha(1f), 0f)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        assertEquals(0f, clampTopFadeAlpha(-0.5f), 0f)
        assertEquals(1f, clampTopFadeAlpha(2f), 0f)
    }

    @Test
    fun nanReadsAsHidden() {
        assertEquals(0f, clampTopFadeAlpha(Float.NaN), 0f)
    }
}
