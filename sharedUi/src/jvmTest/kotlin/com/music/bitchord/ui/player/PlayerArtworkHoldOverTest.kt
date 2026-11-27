package com.music.bitchord.ui.player

import coil3.PlatformContext
import coil3.memory.MemoryCache
import com.music.bitchord.data.model.PLAYER_ART_MEMORY_CACHE_KEY_EXTRA
import com.music.bitchord.data.model.PLAYER_ART_PX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerArtworkHoldOverTest {
    private val previous = MemoryCache.Key("content://media/external/audio/albumart/1")

    @Test
    fun theNextCoverLoadsOverTheLastOne() {
        val art = PlayerArtwork("content://media/external/audio/albumart/2", PlatformContext.INSTANCE, previous)
        assertEquals(previous, art.request.placeholderMemoryCacheKey)
    }

    @Test
    fun playerArtworkUsesAResolutionSpecificMemoryCacheIdentity() {
        val art = PlayerArtwork("content://media/external/audio/albumart/2", PlatformContext.INSTANCE)
        assertEquals(PLAYER_ART_PX.toString(), art.request.memoryCacheKeyExtras[PLAYER_ART_MEMORY_CACHE_KEY_EXTRA])
        // The AutoMix prepared cover builds on the same request, so the player reads its decode.
        val prepared = playerCoverRequest(PlatformContext.INSTANCE, art.url).build()
        assertEquals(prepared.memoryCacheKeyExtras, art.request.memoryCacheKeyExtras)
    }

    @Test
    fun aCoverSkippedBeforeItLoadedPassesTheLastOneOn() {
        val skipped = PlayerArtwork("content://media/external/audio/albumart/2", PlatformContext.INSTANCE, previous)
        assertEquals(previous, skipped.lastCover)
    }

    @Test
    fun theFirstCoverHasNothingToStandOn() {
        val art = PlayerArtwork("content://media/external/audio/albumart/1", PlatformContext.INSTANCE)
        assertNull(art.request.placeholderMemoryCacheKey)
        assertNull(art.lastCover)
    }

    private val remote = "https://lh3.googleusercontent.com/cover"

    @Test
    fun localUrlsAreNotRetryable() {
        assertFalse(isRetryableArtworkUrl("content://media/external/audio/albumart/1"))
        assertFalse(isRetryableArtworkUrl("file:///sdcard/a.jpg"))
        assertFalse(isRetryableArtworkUrl(null))
        assertFalse(isRetryableArtworkUrl(" "))
        assertTrue(isRetryableArtworkUrl(remote))
    }

    @Test
    fun localCoverSettlesEmptyOnFirstError() {
        val art = PlayerArtwork("content://media/external/audio/albumart/2", PlatformContext.INSTANCE, previous)
        assertFalse(art.settledEmpty)
        art.failed = true
        assertTrue(art.settledEmpty)
        assertNull(art.lastCover)
    }

    @Test
    fun remoteCoverKeepsHoldOverWhileRetriesRemain() {
        val art = PlayerArtwork(remote, PlatformContext.INSTANCE, previous)
        art.failed = true
        assertFalse(art.settledEmpty)
        assertEquals(previous, art.lastCover)
        repeat(ART_RETRIES) { art.retry(); art.failed = true }
        assertTrue(art.settledEmpty)
        assertNull(art.lastCover)
    }

    @Test
    fun noUrlIsSettledEmpty() {
        assertTrue(PlayerArtwork(null, PlatformContext.INSTANCE, previous).settledEmpty)
    }

    @Test
    fun holdOverCountsAsVisibleWhileLoadingAndNotAfterError() {
        val art = PlayerArtwork("content://media/external/audio/albumart/2", PlatformContext.INSTANCE, previous)
        assertTrue(art.hasVisibleCover)
        art.failed = true
        assertFalse(art.hasVisibleCover)
    }

    @Test
    fun noHoldOverMeansNoVisibleCoverUntilLoaded() {
        assertFalse(PlayerArtwork(remote, PlatformContext.INSTANCE).hasVisibleCover)
    }
}
