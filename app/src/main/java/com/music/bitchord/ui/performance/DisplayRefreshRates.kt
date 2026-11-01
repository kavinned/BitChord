package com.music.bitchord.ui.performance

import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MIN_PERFORMANCE_REFRESH_RATE = 50
private const val MAX_REASONABLE_REFRESH_RATE = 240
private const val DEFAULT_REFRESH_RATE = 60

/**
 * What the panel reports, clamped to the range the preference store keeps.
 * A panel faster than [MAX_REASONABLE_REFRESH_RATE] must not resolve to a rate
 * the store would rewrite, or opening Settings would reset it every visit.
 */
internal fun fallbackRefreshRate(reported: Float?): Int =
    reported
        ?.roundToInt()
        ?.coerceIn(MIN_PERFORMANCE_REFRESH_RATE, MAX_REASONABLE_REFRESH_RATE)
        ?: DEFAULT_REFRESH_RATE

/** Refresh rates the current display can actually select, normalized for UI labels. */
fun Display?.supportedPerformanceRefreshRates(): List<Int> {
    val fallback = fallbackRefreshRate(this?.refreshRate)
    return this?.supportedModes
        ?.asSequence()
        ?.map { it.refreshRate.roundToInt() }
        ?.filter { it in MIN_PERFORMANCE_REFRESH_RATE..MAX_REASONABLE_REFRESH_RATE }
        ?.distinct()
        ?.sorted()
        ?.toList()
        ?.ifEmpty { listOf(fallback) }
        ?: listOf(fallback)
}

/** Maps a saved preference to a real mode, including preferences restored on another device. */
fun Display?.resolvePerformanceRefreshRate(preferred: Int): Int =
    supportedPerformanceRefreshRates().minBy { abs(it - preferred) }
