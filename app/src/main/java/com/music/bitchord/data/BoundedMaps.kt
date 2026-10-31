package com.music.bitchord.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a growing in-memory cache under [limit] by dropping entries until it
 * fits.
 *
 * These caches all rebuild a dropped entry from disk or from the network, so
 * which one goes does not matter — only that the map stops growing with
 * everything ever played or scrolled past.
 */
internal fun <K, V> ConcurrentHashMap<K, V>.trimToLimit(limit: Int) {
    while (size > limit) {
        val victim = keys.firstOrNull() ?: return
        remove(victim)
    }
}

/** The same for one of [ConcurrentHashMap.newKeySet]'s sets. */
internal fun <K> MutableSet<K>.trimToLimit(limit: Int) {
    while (size > limit) {
        val victim = firstOrNull() ?: return
        remove(victim)
    }
}
