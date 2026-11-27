package com.music.bitchord.data.lyrics

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What [LyricsRepository] found, kept between lookups and between runs.
 *
 * Two tiers, the same shape as [LyricsTranslation]'s: a small in-memory LRU in
 * front of gzipped JSON files under [cacheDir]. One file per video id holds
 * every source that has answered for it, so a manual provider switch adds to
 * the entry rather than replacing it. Lossless — word and syllable timing,
 * background vocals, alignment and the source all survive — which is why this
 * is not an LRC file.
 *
 * Without a [cacheDir] it is memory only. A file that will not decode is
 * deleted and read as a miss; the cache can never be the reason lyrics fail.
 */
internal object LyricsCache {
    private const val CACHE_VERSION = 1
    private const val CACHE_DIRECTORY = "lyrics_cache_v1"
    private const val MAX_MEMORY_ENTRIES = 64
    const val MAX_CACHE_BYTES = 16L * 1024L * 1024L

    /** The disk budget; a variable only so a test can make eviction cheap to reach. */
    @Volatile
    var maxCacheBytes = MAX_CACHE_BYTES

    /** Where entries live between runs. Null keeps them in memory only. */
    @Volatile
    var cacheDir: File? = null

    private val json = Json { ignoreUnknownKeys = true }

    // Guards [memory] and every file operation.
    private val mutex = Mutex()
    private val memory = object : LinkedHashMap<String, CachedTrackLyrics>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CachedTrackLyrics>) =
            size > MAX_MEMORY_ENTRIES
    }

    /**
     * The best cached answer for [videoId] among [allowedSources], ranked the
     * way [LyricsRepository.pick] ranks a live race: word-synced first, then
     * line-synced (the first by [order], or only as a fallback when
     * [prioritizeSyllableSync]), then plain.
     */
    suspend fun get(
        videoId: String,
        allowedSources: Set<LyricsSource>,
        order: List<LyricsSource>,
        prioritizeSyllableSync: Boolean,
    ): LyricsRepository.Result? {
        val entry = load(videoId) ?: return null
        val cached = entry.results()
        val sequence = (order + LyricsSource.offered)
            .distinct()
            .filter { it in allowedSources && !it.hidden }
        var lineSynced: LyricsRepository.Result? = null
        var plain: LyricsRepository.Result? = null
        for (source in sequence) {
            val found = cached[source] ?: continue
            if (found.lines.any { it.isWordSynced }) return found
            if (found.lines.any { it.timeMs > 0 }) {
                if (!prioritizeSyllableSync) return found
                if (lineSynced == null) lineSynced = found
            } else if (plain == null) {
                plain = found
            }
        }
        return lineSynced ?: plain
    }

    /** Whatever is cached for [videoId], whichever source it came from — the offline fallback. */
    suspend fun getAny(videoId: String): LyricsRepository.Result? {
        val cached = load(videoId)?.results() ?: return null
        return cached.values.maxByOrNull { result ->
            when {
                result.lines.any { it.isWordSynced } -> 2
                result.lines.any { it.timeMs > 0 } -> 1
                else -> 0
            }
        }
    }

    /** Adds [result] to what is kept for [videoId]. Empty lyrics are never stored. */
    suspend fun put(videoId: String, result: LyricsRepository.Result) {
        if (videoId.isEmpty() || result.lines.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val existing = memory[videoId] ?: readFile(videoId)
                val merged = CachedTrackLyrics(
                    version = CACHE_VERSION,
                    sources = existing?.sources.orEmpty() + (
                        result.source.name to CachedSourceLyrics(
                            lines = result.lines.map(CachedLyricLine::of),
                            cachedAtMs = System.currentTimeMillis(),
                        )
                        ),
                )
                memory[videoId] = merged
                writeFile(videoId, merged)
            }
        }
    }

    /** Forgets everything, in memory and on disk. */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                memory.clear()
                cacheDir?.let { File(it, CACHE_DIRECTORY).deleteRecursively() }
            }
        }
    }

    /** Drops the memory tier only, as a process restart would. */
    internal suspend fun clearMemory() {
        mutex.withLock { memory.clear() }
    }

    private suspend fun load(videoId: String): CachedTrackLyrics? {
        if (videoId.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val hit = memory[videoId] ?: readFile(videoId)?.also { memory[videoId] = it }
                if (hit != null) fileFor(videoId)?.takeIf { it.isFile }?.setLastModified(System.currentTimeMillis())
                hit
            }
        }
    }

    private fun fileFor(videoId: String): File? {
        val root = cacheDir ?: return null
        val name = MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(root, CACHE_DIRECTORY), "$name.json.gz")
    }

    private fun readFile(videoId: String): CachedTrackLyrics? {
        val file = fileFor(videoId)?.takeIf { it.isFile } ?: return null
        val value = runCatching {
            FileInputStream(file).use { input ->
                GZIPInputStream(input).bufferedReader().use {
                    json.decodeFromString<CachedTrackLyrics>(it.readText())
                }
            }
        }.getOrNull()
        if (value == null || value.version != CACHE_VERSION) {
            file.delete()
            return null
        }
        return value
    }

    private fun writeFile(videoId: String, value: CachedTrackLyrics) {
        val destination = fileFor(videoId) ?: return
        val directory = destination.parentFile ?: return
        if (!directory.exists() && !directory.mkdirs()) return
        val temporary = File(directory, "${destination.name.removeSuffix(".json.gz")}.tmp")
        runCatching {
            FileOutputStream(temporary).use { output ->
                GZIPOutputStream(output).bufferedWriter().use {
                    it.write(json.encodeToString(value))
                }
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            trim(directory)
        }.onFailure { temporary.delete() }
    }

    /** Oldest-touched files go first once the directory is over budget. */
    private fun trim(directory: File) {
        val files = directory.listFiles { file -> file.extension == "gz" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var kept = 0L
        files.forEach { file ->
            kept += file.length()
            if (kept > maxCacheBytes) file.delete()
        }
    }

    private fun CachedTrackLyrics.results(): Map<LyricsSource, LyricsRepository.Result> =
        sources.mapNotNull { (name, value) ->
            val source = LyricsSource.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            val lines = value.lines.map(CachedLyricLine::toLine)
            if (lines.isEmpty()) null else source to LyricsRepository.Result(source, lines)
        }.toMap()

    @Serializable
    internal data class CachedTrackLyrics(
        val version: Int = CACHE_VERSION,
        val sources: Map<String, CachedSourceLyrics> = emptyMap(),
    )

    @Serializable
    internal data class CachedSourceLyrics(
        val lines: List<CachedLyricLine>,
        val cachedAtMs: Long = 0L,
    )

    @Serializable
    internal data class CachedLyricLine(
        val timeMs: Long,
        val text: String,
        val words: List<CachedLyricWord> = emptyList(),
        val sungUntilMs: Long? = null,
        val background: CachedLyricLine? = null,
        val alignment: String = LyricAlignment.Start.name,
    ) {
        fun toLine(): LyricLine = LyricLine(
            timeMs = timeMs,
            text = text,
            words = words.map(CachedLyricWord::toWord),
            sungUntilMs = sungUntilMs,
            background = background?.toLine(),
            alignment = LyricAlignment.entries.firstOrNull { it.name == alignment } ?: LyricAlignment.Start,
        )

        companion object {
            fun of(line: LyricLine): CachedLyricLine = CachedLyricLine(
                timeMs = line.timeMs,
                text = line.text,
                words = line.words.map(CachedLyricWord::of),
                sungUntilMs = line.sungUntilMs,
                background = line.background?.let(::of),
                alignment = line.alignment.name,
            )
        }
    }

    @Serializable
    internal data class CachedLyricWord(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val syllables: List<CachedLyricSyllable> = emptyList(),
    ) {
        fun toWord() = LyricWord(startMs, endMs, text, syllables.map { LyricSyllable(it.startMs, it.endMs, it.startChar, it.endChar) })

        companion object {
            fun of(word: LyricWord) = CachedLyricWord(
                startMs = word.startMs,
                endMs = word.endMs,
                text = word.text,
                syllables = word.syllables.map { CachedLyricSyllable(it.startMs, it.endMs, it.startChar, it.endChar) },
            )
        }
    }

    @Serializable
    internal data class CachedLyricSyllable(
        val startMs: Long,
        val endMs: Long,
        val startChar: Int,
        val endChar: Int,
    )
}
