# Source-Aware Prefetch Cache Keys — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a prefetched next track play instantly at the quality that was prefetched, and make Stats for Nerds say the truth about it.

**Architecture:** The on-disk cache key for a substituted rendition currently names only `videoId#alt`, so YouTube bytes written by ordinary playback and a pinned Nebula FLAC share one entry. Key substituted renditions by the pinned source (`videoId#alt:<configId>`), and learn the pinned stream's real total from its server before the whole-track pass so `complete` means the FLAC is complete.

**Tech Stack:** Kotlin, Media3 `SimpleCache`/`CacheDataSource`/`CacheKeyFactory`, Coroutines, OkHttp (`com.music.bitchord.data.Http`), JUnit 4.

**Spec:** This conversation's diagnosis — no separate spec doc. Evidence and root cause are in "Context" below.

## Context (the bug being fixed)

Measured on a OnePlus CPH2645, `dev` at `aee768f4`:

- Manual skip to a cached track: `TIMING first audio: 130ms`, `'Eye of the Tiger' is already playing audio/flac from cache` — genuinely instant.
- Auto-advance to a "complete" prefetched track: `first audio 174ms`, then `looking again for a better copy`, `auditioning upgraded …#hifi`, `upgraded to FLAC at 6645ms` — it played a YouTube copy and upgraded seconds later.
- The on-device cache DB (`databases/exoplayer_internal.db`) shows both prefetched entries, `b72gdhV_rXM#alt-noatmos` and `XzNWRmqibNE#alt-noatmos`, with `exo_redir` pointing at `googlevideo…itag=251` (YouTube Opus) and a recorded total of 3,416,463 bytes. The user sees `complete` for ~3 MB while their FLAC copies are 20–40 MB.
- Log: `warmed: 'Billie Jean' from Nebula … at FLAC`, then `read-ahead fetching Kr4EQDVETuA#alt-noatmos`, then `stopped short of caching Kr4EQDVETuA in full`.

Root cause: `AudioCache.phoneKey` keys every substitute as `videoId#alt[-noatmos]`, chosen from the global `canSubstituteForYouTube()` capability rather than from what actually resolves. Playback that loses the substitution race writes YouTube bytes into that entry; a later prefetch pins a FLAC and either collides with it or reports it as complete. Keying by the pinned source separates the recordings.

Second defect, same class: a whole-track pass sizes itself from the entry's recorded length, and the head pass opens the entry with a fixed 1 MB range — a length recorded from that open describes the range, not the file, so a prefix can read as `complete` and the pass can stop early.

## Global Constraints

- Android app only (`app/`). Do not touch `tv/`, `wearos/`, `web/`, `desktopApp/`, `desktopUi/`.
- JDK 17. Test: `./gradlew.bat :app:testDevDebugUnitTest`; build: `./gradlew.bat :app:assembleDevDebug`.
- No new dependencies. Use the existing shared OkHttp client `com.music.bitchord.data.Http.client`.
- Work on branch `dev` (currently `aee768f4`). Do not push; the user has not asked.
- Commit subjects are short, one line, no bodies, no `Co-Authored-By`.
- No device is attached; verification is unit tests plus the debug build.
- Existing key prefixes must keep working: `keysOf`, `renditionKeysFor`, `cacheKeyBaseOf`, `noteSongs` all match `"$base#"` prefixes, so a new key must still start with `videoId#`.
- Old `#alt` / `#alt-noatmos` entries from released builds stay where they are and must remain readable for cold opens and offline playback. Do not bump `CACHE_FORMAT`.

## Review Focus

Inputs and states the tests cannot reach, most likely first:

1. A pin expires between prefetch and playback (15 min TTL) → the key falls back to `#alt`; playback must still work and Stats must stop claiming the prefetched copy. No clock seam exists; checked by reading `StreamChoice.of` usage, not by a test.
2. A release-build `#alt` entry exists while a pin now names Nebula → online playback must not fall back to the old YouTube copy; `completeSibling` must keep returning null online when the preferred key is not the plain id.
3. Offline after a process restart: no pin, preferred `#alt` empty, a complete `#alt:<configId>` entry on disk → playback must find it via the sibling search.
4. Dolby Atmos flips between prefetch and playback → `-noatmos` suffix differs, a different key is used, bytes are never mixed.
5. The source server answers no usable length (`Content-Range: …/*` and no `Content-Length`) → prefetch reports partial, never complete; playback streams the remainder.

## Out of scope (deliberately)

- `prefetchQueue` cancelling a warm when the radio queue grows, and the 8 s grace restarting: real but a separate latency issue.
- `NextPrefetch.describe()` formatting: unchanged.
- Any change to `OriginalVersion`, `#hifi` upgrade keys, or `QualityUpgrade`.

---

### Task 1: Key a substituted cache entry by the source serving it

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt` (`videoCacheKey` ~439-448, `phoneKey` ~462-511, `completeSibling` ~541-551)
- Test: `app/src/test/java/com/music/bitchord/AudioCacheReadAheadKeyTest.kt`

**Interfaces:**
- Produces: `internal fun AudioCache.videoCacheKey(videoId: String, rendition: String?, substituting: Boolean, atmosSuffix: String = atmosKeySuffix(), sourceId: String? = null): String`
- Keys produced: `videoId` (YouTube, no substitution possible), `videoId#alt[-noatmos]` (substituting, no pinned source), `videoId#alt:<sourceConfigId>[-noatmos]` (pinned substitute), `videoId#<rendition>` (upgrade marker).
- Consumes: `StreamChoice.of(videoId)?.sourceConfigId` (already in the same package).

- [ ] **Step 1: Write the failing tests**

Append to `AudioCacheReadAheadKeyTest`:

```kotlin
    @Test
    fun `a pinned substitute keys on the source that serves it`() {
        assertEquals(
            "abc#alt:nebula",
            AudioCache.videoCacheKey("abc", rendition = null, substituting = true, atmosSuffix = "", sourceId = "nebula"),
        )
    }

    @Test
    fun `a source key keeps the atmos split`() {
        assertEquals(
            "abc#alt:nebula-noatmos",
            AudioCache.videoCacheKey(
                "abc", rendition = null, substituting = true, atmosSuffix = "-noatmos", sourceId = "nebula",
            ),
        )
    }

    @Test
    fun `a youtube pin has no source and keeps the plain alt bucket`() {
        assertEquals(
            "abc#alt",
            AudioCache.videoCacheKey("abc", rendition = null, substituting = true, atmosSuffix = "", sourceId = null),
        )
    }

    @Test
    fun `a rendition marker still outranks the source`() {
        assertEquals(
            "abc#hifi",
            AudioCache.videoCacheKey("abc", rendition = "hifi", substituting = true, atmosSuffix = "", sourceId = "nebula"),
        )
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.AudioCacheReadAheadKeyTest"`
Expected: compile failure — `videoCacheKey` has no parameter `sourceId`.

- [ ] **Step 3: Add the parameter and the branch**

In `videoCacheKey`, append `sourceId: String? = null` after `atmosSuffix`, and add the branch between `rendition` and `substituting`:

```kotlin
        rendition != null -> "$videoId#$rendition"
        sourceId != null -> "$videoId#alt:$sourceId$atmosSuffix"
        substituting -> "$videoId#alt$atmosSuffix"
        else -> videoId
```

- [ ] **Step 4: Pass the pin from `phoneKey`**

Replace the `val key = videoCacheKey(...)` line with:

```kotlin
                val key = videoCacheKey(
                    videoId,
                    rendition,
                    SourceResolver.canSubstituteForYouTube(),
                    sourceId = StreamChoice.of(videoId)?.sourceConfigId,
                )
```

- [ ] **Step 5: Teach `completeSibling` about source keys**

Replace the body after the `getCachedSpans(preferred)` guard with:

```kotlin
        // Any other rendition of the same recording. [renditionKeysFor] matches
        // every `videoId#…` key, so a source-keyed substitute entry is found
        // here too — needed offline, where a restart has no pin and the
        // preferred key is the plain id or `#alt`.
        val offline = isOffline()
        if (!offline && preferred != videoId) return null
        return renditionKeysFor(videoId)
            .filter { it != preferred }
            .firstOrNull { key ->
                isComplete(key) && (offline || isKeptOrigin(key))
            }
```

- [ ] **Step 6: Run the test class**

Run: `./gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.AudioCacheReadAheadKeyTest"`
Expected: 7 tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/music/bitchord/playback/AudioCache.kt app/src/test/java/com/music/bitchord/AudioCacheReadAheadKeyTest.kt
git commit -m "fix(playback): key a substituted cache entry by its source"
```

---

### Task 2: Size a prefetched rendition from its server, not from the range

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt` (`prefetchQueue` byte launch ~1346-1352, new `streamLength`/`contentRangeTotal`, `recordContentLength` ~1819-1828)
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` (delete `learnStreamLength` ~4602-4617, call site ~4709)
- Test: Create `app/src/test/java/com/music/bitchord/ContentRangeTotalTest.kt`

**Interfaces:**
- Produces: `internal fun AudioCache.contentRangeTotal(contentRange: String?, contentLength: String?): Long?`
- Produces: `internal suspend fun AudioCache.streamLength(stream: SourceStream): Long?`
- Consumes: `com.music.bitchord.data.Http.client`, `SourceStream.headers`, `AudioCache.cacheKeyOf(uri)`, `AudioCache.recordLength(key, total)` (private, same object).

- [ ] **Step 1: Write the failing test**

Create `ContentRangeTotalTest`:

```kotlin
package com.music.bitchord

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The total a ranged probe learns: Content-Range first, Content-Length otherwise. */
class ContentRangeTotalTest {

    @Test
    fun `a content range names the whole file`() {
        assertEquals(30_000_000L, AudioCache.contentRangeTotal("bytes 0-0/30000000", null))
    }

    @Test
    fun `an unknown range total falls back to content length`() {
        assertEquals(3_416_463L, AudioCache.contentRangeTotal("bytes 0-0/*", "3416463"))
    }

    @Test
    fun `no headers means no answer`() {
        assertNull(AudioCache.contentRangeTotal(null, null))
    }

    @Test
    fun `a zero length is not a total`() {
        assertNull(AudioCache.contentRangeTotal(null, "0"))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.ContentRangeTotalTest"`
Expected: compile failure — `contentRangeTotal` does not exist.

- [ ] **Step 3: Implement the pure parser and the probe**

In `AudioCache`, next to `wholeTrackTotal`:

```kotlin
    /**
     * The total a ranged probe learned: `bytes 0-0/30000000` names the file,
     * `bytes 0-0/*` does not and leaves [contentLength] to answer.
     */
    internal fun contentRangeTotal(contentRange: String?, contentLength: String?): Long? =
        (contentRange?.substringAfterLast('/')?.toLongOrNull()
            ?: contentLength?.toLongOrNull())?.takeIf { it > 0 }
```

Then, near `fetchWhole`:

```kotlin
    /**
     * How many bytes [stream] holds, straight from its server.
     *
     * One ranged request for a single byte: a `206` answers with
     * `Content-Range: bytes 0-0/<total>`. Asked before a read-ahead pass
     * because a ranged open of the entry records the *range* as its length —
     * see [recordLength] — and a pass sized from that stops at the prefix and
     * calls it complete.
     */
    internal suspend fun streamLength(stream: SourceStream): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder()
                .url(stream.url)
                .header("Range", "bytes=0-0")
                .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
                .build()
            Http.client.newCall(request).execute().use { response ->
                contentRangeTotal(response.header("Content-Range"), response.header("Content-Length"))
            }
        }.getOrNull()
    }
```

Add imports `com.music.bitchord.data.Http` and `com.music.bitchord.data.sources.SourceStream`.

- [ ] **Step 4: Make the recorded length grow-only and reusable**

Replace `recordContentLength` with:

```kotlin
    /**
     * Records [total] as [key]'s size, and only ever grows it.
     *
     * A ranged open of an entry records its *range* as the content length, so
     * the first number written for a prefetched substitute can be 1 MB where
     * the file is 30. Growing replaces that with the server's answer; never
     * shrinking keeps a smaller, contradictory answer from trimming spans that
     * are on disk.
     */
    private fun recordLength(key: String, total: Long) {
        if (total <= 0) return
        val current = runCatching {
            ContentMetadata.getContentLength(cache.getContentMetadata(key))
        }.getOrDefault(0L)
        if (total <= current) return
        runCatching {
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().apply { ContentMetadataMutations.setContentLength(this, total) },
            )
        }.onFailure { TrackLog.d(TAG, "could not record length for $key: ${it.message}") }
    }
```

Keep `recordContentLength(videoId, total)` as a one-line delegate: `recordLength(videoId, total)`.

- [ ] **Step 5: Learn the total before the prefetch pass**

In `prefetchQueue`, inside `launch(TrackLog.about(next))` and before `fetch(next, 0, PRELOAD_BYTES)`:

```kotlin
                        // The pinned copy's own size, before anything reads
                        // it: the head pass below opens the entry with a fixed
                        // range, and a length recorded by that open is the
                        // range's. Without the server's number the whole pass
                        // either cannot start or stops at the prefix.
                        if (warmed != null) {
                            val uri = Uri.parse("bitchord://watch?v=$next")
                            StreamChoice.of(next)?.let { pinned ->
                                runCatching { streamLength(pinned) }.getOrNull()
                                    ?.let { total -> recordLength(cacheKeyOf(uri), total) }
                            }
                        }
```

- [ ] **Step 6: Reuse it from the playback service**

Delete `PlaybackService.learnStreamLength` and replace its call at ~4709 with `AudioCache.streamLength(stream)`.

- [ ] **Step 7: Run the tests**

Run: `./gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.ContentRangeTotalTest" --tests "com.music.bitchord.AudioCacheWholeTotalTest"`
Expected: 8 tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/music/bitchord/playback/AudioCache.kt app/src/main/java/com/music/bitchord/playback/PlaybackService.kt app/src/test/java/com/music/bitchord/ContentRangeTotalTest.kt
git commit -m "fix(playback): size a prefetched rendition from its server"
```

---

### Task 3: Whole-suite verification and debug build

- [ ] **Step 1: Run the full app suite and the debug build**

Run: `./gradlew.bat :app:testDevDebugUnitTest :app:assembleDevDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, no failing tests.

- [ ] **Step 2: Report**

Report: the two commit hashes, the suite/build result, and the four states now distinguished by the key (`videoId`, `#alt`, `#alt:<source>`, `#hifi`). No device verification was possible.
