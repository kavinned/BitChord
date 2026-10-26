package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Prefetch asks sources one at a time, in the user's order, and stops where
 * the answer is good enough. The race this replaces let JioSaavn's fast 320
 * beat an addon's FLAC, which is what made a prefetched next track still
 * arrive "upgrading".
 */
class PrefetchOrderTest {

    private class FakeSource(override val displayName: String) : MusicSource {
        override val configId = displayName
        override val kind = SourceKind.ADDON

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> = emptyList()

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? = null
    }

    private fun source(name: String) = FakeSource(name)

    private fun stream(source: MusicSource, format: StreamFormat?) =
        format?.let { SourceStream(url = "https://${source.displayName}/x", format = it) }

    private fun walk(
        sources: List<FakeSource>,
        formats: Map<String, StreamFormat?>,
        asked: MutableList<String>,
    ) = runBlocking {
        SourceResolver.prefetchWalk(sources) { source ->
            asked += source.displayName
            stream(source, formats[source.displayName])
        }
    }

    @Test
    fun `a lossless answer stops the walk`() {
        val a = source("Nebula")
        val b = source("Second")
        val asked = mutableListOf<String>()

        val picked = walk(listOf(a, b), mapOf("Nebula" to StreamFormat("flac")), asked)

        assertEquals("Nebula", picked?.first?.displayName)
        assertEquals(listOf("Nebula"), asked)
    }

    @Test
    fun `a 320 stops the walk after a weaker lossy`() {
        val weak = source("Weak")
        val strong = source("Strong")
        val later = source("Later")
        val asked = mutableListOf<String>()

        val picked = walk(
            listOf(weak, strong, later),
            mapOf("Weak" to StreamFormat("mp3", kbps = 128), "Strong" to StreamFormat("mp4", kbps = 320)),
            asked,
        )

        assertEquals("Strong", picked?.first?.displayName)
        assertEquals(listOf("Weak", "Strong"), asked)
    }

    @Test
    fun `a miss keeps the best lossy seen`() {
        val weak = source("Weak")
        val better = source("Better")
        val missing = source("Missing")
        val asked = mutableListOf<String>()

        val picked = walk(
            listOf(weak, better, missing),
            mapOf("Weak" to StreamFormat("mp3", kbps = 128), "Better" to StreamFormat("mp4", kbps = 256)),
            asked,
        )

        assertEquals("Better", picked?.first?.displayName)
        assertEquals(listOf("Weak", "Better", "Missing"), asked)
    }

    @Test
    fun `a miss before a 320 takes the 320`() {
        val missing = source("Missing")
        val strong = source("Strong")
        val asked = mutableListOf<String>()

        val picked = walk(
            listOf(missing, strong),
            mapOf("Strong" to StreamFormat("mp4", kbps = 320)),
            asked,
        )

        assertEquals("Strong", picked?.first?.displayName)
        assertEquals(listOf("Missing", "Strong"), asked)
    }

    @Test
    fun `a 320 outranks a later lossless`() {
        val first = source("Nebula")
        val later = source("Later")
        val asked = mutableListOf<String>()

        val picked = walk(
            listOf(first, later),
            mapOf("Nebula" to StreamFormat("mp4", kbps = 320), "Later" to StreamFormat("flac")),
            asked,
        )

        assertEquals("Nebula", picked?.first?.displayName)
        assertEquals(listOf("Nebula"), asked)
    }

    @Test
    fun `no answer from anyone returns nothing`() {
        val asked = mutableListOf<String>()

        val picked = walk(listOf(source("A"), source("B")), emptyMap(), asked)

        assertNull(picked)
        assertEquals(listOf("A", "B"), asked)
    }
}
