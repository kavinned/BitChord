package com.music.bitchord.data.stats

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayPeriodTest {

    private val today = LocalDate.of(2026, 11, 7)
    private val selectedMonth = YearMonth.of(2024, 2)

    @Test
    fun `month covers only selected month`() {
        assertTrue(ReplayPeriod.THIS_MONTH.covers(selectedMonth, today, selectedMonth))
        assertFalse(ReplayPeriod.THIS_MONTH.covers(YearMonth.of(2024, 1), today, selectedMonth))
    }

    @Test
    fun `year covers all months of selected year only`() {
        assertTrue(ReplayPeriod.THIS_YEAR.covers(YearMonth.of(2024, 1), today, selectedMonth))
        assertTrue(ReplayPeriod.THIS_YEAR.covers(YearMonth.of(2024, 12), today, selectedMonth))
        assertFalse(ReplayPeriod.THIS_YEAR.covers(YearMonth.of(2023, 12), today, selectedMonth))
    }

    @Test
    fun `month and year labels follow selected date`() {
        assertEquals("February 2024", ReplayPeriod.THIS_MONTH.label(today, selectedMonth))
        assertEquals("2024", ReplayPeriod.THIS_YEAR.label(today, selectedMonth))
    }
}
