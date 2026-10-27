package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A refused source must be left out of the walk, and only that one. */
class SourceSkipTest {

    /** Enough of a source to be filtered by id; nothing here asks it anything. */
    private class Fake(override val configId: String, override val kind: SourceKind) : MusicSource {
        override val displayName: String get() = configId
        override suspend fun health(): SourceHealth = SourceHealth.Ok()
        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> = emptyList()
        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? = null
    }

    private val ranked = listOf(
        Fake("nebula", SourceKind.ADDON),
        Fake("other", SourceKind.ADDON),
        Fake("jiosaavn", SourceKind.JIOSAAVN),
        Fake("youtube", SourceKind.YOUTUBE),
    )

    @Test
    fun `only the named source is skipped`() {
        val kept = SourceResolver.rankedFor(ranked, setOf("nebula"))

        assertEquals(listOf("other", "jiosaavn", "youtube"), kept.map { it.configId })
    }

    @Test
    fun `no skip leaves the ranking untouched`() {
        assertEquals(ranked, SourceResolver.rankedFor(ranked, emptySet()))
    }

    @Test
    fun `skipping every module leaves only youtube`() {
        val kept = SourceResolver.rankedFor(ranked, setOf("nebula", "other", "jiosaavn"))

        assertEquals(listOf("youtube"), kept.map { it.configId })
    }
}
