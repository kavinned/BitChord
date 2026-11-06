package com.music.bitchord.ui.replay

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayDatePickerTest {

    @Test
    fun `month wheel respects earliest and current retained months`() {
        val earliest = YearMonth.of(2024, 6)
        val latest = YearMonth.of(2026, 11)

        assertEquals(
            (6..12).map { YearMonth.of(2024, it) },
            replayMonthsInYear(2024, earliest, latest),
        )
        assertEquals(
            (1..11).map { YearMonth.of(2026, it) },
            replayMonthsInYear(2026, earliest, latest),
        )
    }

    @Test
    fun `middle years show every month`() {
        assertEquals(
            (1..12).map { YearMonth.of(2025, it) },
            replayMonthsInYear(2025, YearMonth.of(2024, 6), YearMonth.of(2026, 11)),
        )
    }
}
