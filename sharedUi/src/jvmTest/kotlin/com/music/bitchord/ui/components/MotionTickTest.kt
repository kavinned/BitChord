package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTickTest {

    private fun delayAt(nanos: Long) = nextMotionTickDelayMs(nowNanos = nanos)

    @Test
    fun aLoopSittingExactlyOnATickWaitsAFullGapRatherThanSpinning() {
        // 25ms, expressed in nanos.
        assertEquals(MOTION_FRAME_GAP_MS, delayAt(25_000_000L))
        assertEquals(MOTION_FRAME_GAP_MS, delayAt(50_000_000L))
    }

    @Test
    fun aLoopBetweenTicksWaitsTheRestOfTheGap() {
        assertEquals(25L, delayAt(0L))
        assertEquals(16L, delayAt(9_000_000L))
        assertEquals(24L, delayAt(1_000_000L))
    }

    @Test
    fun everyLoopSharingTheClockLandsOnTheSameTick() {
        // Two surfaces composed 7ms apart end up on the same instant: each waits
        // to the next multiple of the gap, not its own gap from where it started.
        val first = 100_000_000L
        val second = first + 7_000_000L

        val firstLands = first / 1_000_000 + delayAt(first)
        val secondLands = second / 1_000_000 + delayAt(second)

        assertEquals(125L, firstLands)
        assertEquals(125L, secondLands)
    }
}
