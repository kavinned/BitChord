package com.music.bitchord.ui.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayRefreshRatesTest {

    /**
     * The fallback has to stay inside the range AppSettings keeps, or a panel
     * faster than 240 Hz resolves to a value the store rewrites on every visit.
     */
    @Test
    fun fallbackIsClampedToTheStoredRange() {
        assertEquals(240, fallbackRefreshRate(360f))
        assertEquals(50, fallbackRefreshRate(30f))
        assertEquals(120, fallbackRefreshRate(120f))
        assertEquals(60, fallbackRefreshRate(null))
    }
}
