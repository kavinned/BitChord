package com.music.bitchord.data.lyrics

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LyricsCacheTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("lyrics-cache-test").toFile()
        LyricsCache.cacheDir = dir
        LyricsCache.maxCacheBytes = LyricsCache.MAX_CACHE_BYTES
        runBlocking { LyricsCache.clear() }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { LyricsCache.clear() }
        LyricsCache.cacheDir = null
        LyricsCache.maxCacheBytes = LyricsCache.MAX_CACHE_BYTES
        dir.deleteRecursively()
    }

    private val all = LyricsSource.entries.toSet()
    private val order = LyricsSource.entries

    private fun plain(source: LyricsSource, text: String = "plain") =
        LyricsRepository.Result(source, listOf(LyricLine(timeMs = 0L, text = text)))

    private fun lineSynced(source: LyricsSource, text: String = "synced") =
        LyricsRepository.Result(source, listOf(LyricLine(timeMs = 1_000L, text = text)))

    private fun wordSynced(source: LyricsSource) = LyricsRepository.Result(
        source,
        listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "hello world",
                words = listOf(
                    LyricWord(
                        1_000L,
                        1_500L,
                        "hello",
                        listOf(LyricSyllable(1_000L, 1_200L, 0, 2), LyricSyllable(1_200L, 1_500L, 2, 5)),
                    ),
                    LyricWord(1_600L, 2_000L, "world"),
                ),
                sungUntilMs = 2_100L,
                background = LyricLine(
                    timeMs = 1_800L,
                    text = "ooh",
                    words = listOf(LyricWord(1_800L, 2_400L, "ooh")),
                    sungUntilMs = 2_400L,
                    alignment = LyricAlignment.End,
                ),
                alignment = LyricAlignment.End,
            ),
            LyricLine(timeMs = 3_000L, text = ""),
        ),
    )

    private fun get(
        id: String,
        allowed: Set<LyricsSource> = all,
        sourceOrder: List<LyricsSource> = order,
        prioritize: Boolean = false,
    ) = runBlocking { LyricsCache.get(id, allowed, sourceOrder, prioritize) }

    @Test
    fun `round trip keeps every field`() {
        val original = wordSynced(LyricsSource.BETTER_LYRICS)
        runBlocking { LyricsCache.put("v1", original) }
        assertEquals(original, get("v1"))
    }

    @Test
    fun `plain and line synced lyrics round trip`() {
        runBlocking {
            LyricsCache.put("p", plain(LyricsSource.LRCLIB))
            LyricsCache.put("l", lineSynced(LyricsSource.KUGOU))
        }
        assertEquals(plain(LyricsSource.LRCLIB), get("p"))
        assertEquals(lineSynced(LyricsSource.KUGOU), get("l"))
    }

    @Test
    fun `disk entry survives losing the memory tier`() {
        val original = wordSynced(LyricsSource.LYRICS_PLUS)
        runBlocking {
            LyricsCache.put("v1", original)
            LyricsCache.clearMemory()
        }
        assertEquals(original, get("v1"))
    }

    @Test
    fun `memory only when there is no cache directory`() {
        LyricsCache.cacheDir = null
        runBlocking { LyricsCache.put("v1", lineSynced(LyricsSource.LRCLIB)) }
        assertNotNull(get("v1"))
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `unknown video is a miss`() {
        assertNull(get("nothing"))
        assertNull(runBlocking { LyricsCache.getAny("nothing") })
    }

    @Test
    fun `get ignores sources that are not allowed`() {
        runBlocking { LyricsCache.put("v1", lineSynced(LyricsSource.LRCLIB)) }
        assertNull(get("v1", allowed = setOf(LyricsSource.KUGOU)))
        assertNull(get("v1", allowed = emptySet()))
    }

    @Test
    fun `get honours the source order`() {
        runBlocking {
            LyricsCache.put("v1", lineSynced(LyricsSource.LRCLIB, "lrclib"))
            LyricsCache.put("v1", lineSynced(LyricsSource.KUGOU, "kugou"))
        }
        val lrclibFirst = listOf(LyricsSource.LRCLIB, LyricsSource.KUGOU)
        val kugouFirst = listOf(LyricsSource.KUGOU, LyricsSource.LRCLIB)
        assertEquals(LyricsSource.LRCLIB, get("v1", sourceOrder = lrclibFirst)?.source)
        assertEquals(LyricsSource.KUGOU, get("v1", sourceOrder = kugouFirst)?.source)
    }

    @Test
    fun `word synced beats an earlier line synced source when syllable sync is preferred`() {
        runBlocking {
            LyricsCache.put("v1", lineSynced(LyricsSource.LRCLIB))
            LyricsCache.put("v1", wordSynced(LyricsSource.LYRICS_PLUS))
        }
        val lineFirst = listOf(LyricsSource.LRCLIB, LyricsSource.LYRICS_PLUS)
        assertEquals(LyricsSource.LYRICS_PLUS, get("v1", sourceOrder = lineFirst, prioritize = true)?.source)
        assertEquals(LyricsSource.LRCLIB, get("v1", sourceOrder = lineFirst, prioritize = false)?.source)
    }

    @Test
    fun `prioritizeSyllableSync only falls back to line synced`() {
        runBlocking {
            LyricsCache.put("v1", plain(LyricsSource.YOUTUBE_MUSIC))
            LyricsCache.put("v1", lineSynced(LyricsSource.LRCLIB))
            LyricsCache.put("v1", lineSynced(LyricsSource.KUGOU))
        }
        val sequence = listOf(LyricsSource.YOUTUBE_MUSIC, LyricsSource.KUGOU, LyricsSource.LRCLIB)
        // Plain first in order must not shadow a timed one.
        assertEquals(LyricsSource.KUGOU, get("v1", sourceOrder = sequence, prioritize = true)?.source)
        assertEquals(LyricsSource.KUGOU, get("v1", sourceOrder = sequence, prioritize = false)?.source)
    }

    @Test
    fun `plain is the answer when nothing is timed`() {
        runBlocking { LyricsCache.put("v1", plain(LyricsSource.YOUTUBE_MUSIC)) }
        assertEquals(LyricsSource.YOUTUBE_MUSIC, get("v1", prioritize = true)?.source)
    }

    @Test
    fun `getAny returns an entry get would refuse`() {
        runBlocking { LyricsCache.put("v1", lineSynced(LyricsSource.LRCLIB)) }
        assertNull(get("v1", allowed = setOf(LyricsSource.KUGOU)))
        assertEquals(LyricsSource.LRCLIB, runBlocking { LyricsCache.getAny("v1") }?.source)
    }

    @Test
    fun `put merges sources and replaces the same source`() {
        runBlocking {
            LyricsCache.put("v1", lineSynced(LyricsSource.LRCLIB, "old"))
            LyricsCache.put("v1", lineSynced(LyricsSource.KUGOU))
            LyricsCache.put("v1", lineSynced(LyricsSource.LRCLIB, "new"))
            LyricsCache.clearMemory()
        }
        assertEquals("new", get("v1", allowed = setOf(LyricsSource.LRCLIB))?.lines?.single()?.text)
        assertNotNull(get("v1", allowed = setOf(LyricsSource.KUGOU)))
    }

    @Test
    fun `empty lyrics are not stored`() {
        runBlocking { LyricsCache.put("v1", LyricsRepository.Result(LyricsSource.LRCLIB, emptyList())) }
        assertNull(get("v1"))
        assertEquals(0, files().size)
    }

    @Test
    fun `corrupt file is a miss and is removed`() {
        runBlocking {
            LyricsCache.put("v1", lineSynced(LyricsSource.LRCLIB))
            LyricsCache.clearMemory()
        }
        val file = files().single()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(get("v1"))
        assertFalse(file.exists())
    }

    @Test
    fun `truncated gzip is a miss`() {
        runBlocking {
            LyricsCache.put("v1", wordSynced(LyricsSource.LYRICS_PLUS))
            LyricsCache.clearMemory()
        }
        val file = files().single()
        file.writeBytes(file.readBytes().copyOf(12))
        assertNull(get("v1"))
    }

    @Test
    fun `clear removes memory and disk`() {
        runBlocking {
            LyricsCache.put("v1", lineSynced(LyricsSource.LRCLIB))
            LyricsCache.clear()
        }
        assertNull(get("v1"))
        assertEquals(0, files().size)
    }

    @Test
    fun `oldest files go first when over budget`() {
        runBlocking {
            LyricsCache.put("vid-a", lineSynced(LyricsSource.LRCLIB))
            files().single().setLastModified(System.currentTimeMillis() - 30_000)
            LyricsCache.put("vid-b", lineSynced(LyricsSource.LRCLIB))
        }
        val sizes = files().map { it.length() }
        LyricsCache.maxCacheBytes = sizes.max() * 2 + sizes.max() / 2
        val b = files().first { it.lastModified() > System.currentTimeMillis() - 20_000 }
        b.setLastModified(System.currentTimeMillis() - 20_000)
        runBlocking { LyricsCache.put("vid-c", lineSynced(LyricsSource.LRCLIB)) }
        assertEquals(2, files().size)
        runBlocking { LyricsCache.clearMemory() }
        assertNull(get("vid-a"))
        assertNotNull(get("vid-b"))
        assertNotNull(get("vid-c"))
    }

    @Test
    fun `no temporary files are left behind`() {
        runBlocking { LyricsCache.put("v1", wordSynced(LyricsSource.LYRICS_PLUS)) }
        assertTrue(File(dir, "lyrics_cache_v1").listFiles().orEmpty().none { it.extension == "tmp" })
    }

    private fun files(): List<File> =
        File(dir, "lyrics_cache_v1").listFiles { f -> f.extension == "gz" }?.toList().orEmpty()
}
