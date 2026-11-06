package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prefetch gate itself: which sources may be resolved ahead of playback,
 * and the setting that makes addon sources opt in. The enum's own
 * [SourceKind.worthPrefetching] flags are covered by `SourcesTest`, and the
 * pinning mechanics by `StreamChoiceTest`.
 */
class AudioCachePrefetchTest {

    @Test
    fun `prefetching defaults to false`() {
        assertFalse(AppSettings.prefetchAddonSources.value)
    }

    @Test
    fun `addon prefetching follows the setting`() {
        val setting = AppSettings.prefetchAddonSources
        val original = setting.value
        try {
            setting.value = false
            assertFalse(SourceResolver.canPrefetch(SourceKind.ADDON))
            // Sources that are quick without asking are unaffected.
            assertTrue(SourceResolver.canPrefetch(SourceKind.JIOSAAVN))

            setting.value = true
            assertTrue(SourceResolver.canPrefetch(SourceKind.ADDON))
        } finally {
            setting.value = original
        }
    }

    @Test
    fun `cached youtube copy does not block eligible addon prefetch`() {
        assertTrue(AudioCache.shouldPrefetchSubstitute(substitutable = true, hasTarget = true, pinnedToYouTube = false))
    }

    @Test
    fun `substitute prefetch still needs target and no youtube pin`() {
        assertFalse(AudioCache.shouldPrefetchSubstitute(substitutable = true, hasTarget = false, pinnedToYouTube = false))
        assertFalse(AudioCache.shouldPrefetchSubstitute(substitutable = true, hasTarget = true, pinnedToYouTube = true))
        assertFalse(AudioCache.shouldPrefetchSubstitute(substitutable = false, hasTarget = true, pinnedToYouTube = false))
    }
}
