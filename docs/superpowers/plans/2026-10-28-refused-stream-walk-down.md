# Walk Down on a Refused Stream — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a source's stream is *refused* — an HTTP status, not a dropped connection — the track moves to the next source in the listener's ranking instead of retrying the dead URL, or falling straight through to YouTube.

**Architecture:** Two halves, one idea.

1. **Recovery fails fast.** `PermanentAwareLoadErrorPolicy` currently only refuses `PermanentlyUnplayableException`, so Media3 spends its 3-try backoff on a URL that answered 502. A refused status becomes non-retryable, so `onPlayerError` fires on the first response.
2. **Refusal is per source, not per track.** `StreamChoice.refuseSubstitutes(videoId)` blocks every module for that track for 10 minutes — one dead Nebula URL also silences JioSaavn. Refusal becomes `(videoId, configId)` with evidence, so recovery forgets the dead source and re-resolves to the next one down the ranking, YouTube last.

**Tech Stack:** Kotlin, Media3 1.11.0 (`DefaultLoadErrorHandlingPolicy`, `HttpDataSource.InvalidResponseCodeException`), Coroutines, JUnit 4.

**Spec:** This conversation's diagnosis; no separate spec doc. Evidence is in "Context".

## Context (the bug being fixed)

Measured on a OnePlus CPH2645, `dev` at `091ff93a`:

```
23:35:58.012  warmed: 'Hot Girls Cold Vibe' from Nebula (Tidal - Torrents) at FLAC
23:36:03.676  TIMING track selected: 6_Nx1szSyeY (reason=2)
23:36:16.794  W/BitChord: nexus-008.indi.tb-cdn.pw refused the stream: Response code: 502
23:36:32.788  W/BitChord: nexus-008.indi.tb-cdn.pw refused the stream: Response code: 502
23:36:58.302  playback failed for 6_Nx1szSyeY at 0ms (ERROR_CODE_IO_BAD_HTTP_STATUS), attempt 1
23:36:58.329  ExoPlaybackException: Source error … InvalidResponseCodeException: Response code: 502
              at ChunkedDataSource.open → ResolvingDataSource → AudioCache$playbackFactory$1$1.open
23:36:58.5xx  6_Nx1szSyeY broke on its upgrade; no more swaps for it
23:36:59.0xx  6_Nx1szSyeY failed on a higher-quality source; falling back to YouTube audio
```

Fifty-five seconds of a track that never started, for a server that answered "no" on the first try in fourteen seconds.

Three separate causes, in the order they cost time:

1. **The retry.** `DefaultLoadErrorHandlingPolicy` retries any `IOException` three times. `HttpDataSource.InvalidResponseCodeException` *is* an `IOException`, and the app's policy only short-circuits `StreamResolver.PermanentlyUnplayableException`. A 502, a 403 and a 404 are all re-sent to the same dead URL, seconds apart, and cannot succeed: the URL is signed and fixed, the resolve is cached, and `StreamChoice` hands back the same pin every time.
2. **The refusal is track-wide.** `StreamChoice.substitutesRefused(videoId)` is a single flag per track, so `resolveWithModulePriority` skips the entire module race and goes to YouTube. JioSaavn was never asked, even though it ranks below Nebula and might have had the track.
3. **The walk is skipped for prefetched tracks.** A pinned track never enters `resolveWithModulePriority` at all — `StreamChoice.of(videoId)` answers the open directly — so even a per-source refusal would have nothing to walk from unless the pin is dropped first.

## Global Constraints

- Android app only (`app/`), plus `shared/` when a resolver signature lives there. Never `tv/`, `wearos/`, `web/`, `desktopApp/`, `desktopUi/`.
- JDK 17. Test: `.\gradlew.bat :app:testDevDebugUnitTest`; build: `.\gradlew.bat :app:assembleDevDebug`.
- Work on branch `feat/addon-prefetch`. Do not push; the user has not asked.
- Commit subjects short, one line, no body, no `Co-Authored-By`.
- No new dependencies. Unit tests are plain JUnit 4 against a stub `android.jar` (`isReturnDefaultValues = true`), so no `robolectric`; anything touching `android.net.Uri`, `SimpleCache` or `ExoPlayer` cannot run in a unit test.
- Existing behaviour that must not regress: a track already playing must not be re-resolved mid-song; a pinned prefetched FLAC that *works* must still be opened by `StreamChoice.of` without a race; `#alt:<configId>` cache keys stay as they are.
- Do not bump `CACHE_FORMAT`.

## Design

### What "refused" means

One predicate, in the playback layer, used by both halves:

```
statusIsWorthRetrying(code) = code == 408 || code == 429
```

`408` and `429` name their own remedy — the server did not answer in time, or asked for a slower caller — and the ordinary backoff is the right answer for both. Every other status is an answer about *this request*: an expired signature (`403`), a missing file (`404`, `410`), a fixed range (`416`), a dead edge (`5xx`). Asking again re-sends the same bytes to the same host.

A dropped connection, a timeout exception and a DNS failure keep the default retry. Only a status is evidence.

### Evidence, not a verdict

`StreamChoice` grows a per-source refusal alongside the existing track-wide one:

```
refusedSources: ConcurrentHashMap<String, Refusal>   // key: "$videoId@$configId"
class Refusal(val at: Long, val status: Int)

refuse(sourceId: String, videoId: String, status: Int)   // status > 0 = a server said no
refusedNow(sourceId: String?, videoId: String): Boolean
```

`refusedNow` accepts a null `sourceId` and then answers only from the track-wide map, so every existing call site keeps working through [refuseSubstitutes]. The per-source entry keeps the status because the two cases deserve different lifetimes:

| evidence | refusal lifetime | why |
|---|---|---|
| a status (`403`, `404`, `502`) | 10 min | the URL is dead; the same lookup mints the same URL |
| no status (the source went quiet after handing a URL over) | 2 min | a module having a bad minute clears up on its own |

The track-wide `refuseSubstitutes(videoId)` stays, unchanged, for the one case that genuinely has no source name: a die on an *upgraded* URI (`#hifi`), where `QualityUpgrade` made the choice and `StreamChoice` never saw it.

### Where the walk happens

Three entry points ask the resolver whose ranking matters, and all three learn the same argument — `skip: Set<String>` of config ids already refused for this track:

- `SourceResolver.prefetchSubstitute(target, skip)` — `quick.filterNot { it.configId in skip }`
- `SourceResolver.substituteForYouTube(target, skip)` — same filter before `bestAcross`
- `SourceResolver.resolve(configId, trackId, target, pickedMix, skip)` — the upgrade loop and the `fallbacks` list filter it too, so a source-backed row walks down as well

Exclusion is passed in rather than read from `StreamChoice` because `data/sources` does not import `playback` today and this change should not start that.

Recovery then does the walking by re-entering the ordinary path:

- **A prefetched pin died.** `PlaybackService.recoverFrom` already forgets the choice for a substituted stream; with a per-source refusal that forget now *narrows* rather than blocks. `StreamChoice.forget(videoId)` + per-source refusal → the next open reaches `resolveWithModulePriority`, which asks the modules below the dead one and YouTube in parallel.
- **`resolveWithModulePriority`** consults `StreamChoice.refusedNow(sourceId, videoId)` inside its `accept` lambda instead of refusing the whole track up front. A source that is refused is skipped; the race continues; YouTube is still the fallback when every module is out.

### What does *not* change

- No change to cache keys. A dead `#alt:<nebula>` entry is discarded by the existing `recoverFrom` discard; the next source writes under its own `#alt:<jiosaavn>` key.
- No change to which track is selected or skipped. `MAX_RECOVERIES` still bounds the recovery loop, so a track whose every source refuses still gives up and skips.
- No change to read-ahead's byte fetching. A prefetch that has not fetched yet cannot learn that its pin is dead — see "Out of scope".

## Tasks

### Task 1: A refused status is not retried

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` (`PermanentAwareLoadErrorPolicy` ~3657-3677)
- Test: create `app/src/test/java/com/music/bitchord/RefusedStatusTest.kt`

**Interfaces:**
- Produces: `internal fun statusIsWorthRetrying(responseCode: Int): Boolean`
- Consumes: `androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException` (field `responseCode: Int`, constructor is public with six arguments).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.music.bitchord

import com.music.bitchord.playback.statusIsWorthRetrying
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which HTTP refusals Media3 should spend its retry budget on. */
class RefusedStatusTest {

    @Test
    fun `a dead url is not asked again`() {
        assertFalse(statusIsWorthRetrying(403))
        assertFalse(statusIsWorthRetrying(404))
        assertFalse(statusIsWorthRetrying(410))
        assertFalse(statusIsWorthRetrying(500))
        assertFalse(statusIsWorthRetrying(502))
        assertFalse(statusIsWorthRetrying(503))
    }

    @Test
    fun `a timeout is worth another go`() {
        assertTrue(statusIsWorthRetrying(408))
    }

    @Test
    fun `a rate limit is worth another go`() {
        assertTrue(statusIsWorthRetrying(429))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "*RefusedStatusTest*"`
Expected: compile failure — unresolved reference `statusIsWorthRetrying`.

- [ ] **Step 3: Add the predicate**

At the top level of `PlaybackService.kt`, beside `permanentReason`'s neighbours (not inside the class):

```kotlin
/**
 * Whether a refused HTTP status leaves anything to gain by asking again.
 *
 * [408][...] and [429][...] name their own remedy — the server did not answer
 * in time, or asked for a slower caller — so the ordinary backoff stays. Every
 * other refusal is an answer about *this request*: a signature that expired, a
 * file that is not there, an edge that cannot serve the bytes. The resolve is
 * cached and the URL is fixed, so the next attempt re-sends the same request to
 * the same dead URL — measured at fifty-five seconds before a 502 reached the
 * recovery path that handled it in one.
 */
internal fun statusIsWorthRetrying(responseCode: Int): Boolean =
    responseCode == 408 || responseCode == 429
```

Add `import androidx.media3.datasource.HttpDataSource`.

- [ ] **Step 4: Teach the policy**

In `PermanentAwareLoadErrorPolicy.isPermanent`, after the `PermanentlyUnplayableException` branch:

```kotlin
                if (cause is HttpDataSource.InvalidResponseCodeException &&
                    !statusIsWorthRetrying(cause.responseCode)
                ) {
                    return true
                }
```

Update the class KDoc: it currently claims everything but a resolver verdict keeps the default behaviour. Say that a refused status is refused too, and why.

- [ ] **Step 5: Run the test, then the whole suite**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "*RefusedStatusTest*"`
Expected: 3 tests pass.

Run: `.\gradlew.bat :app:testDevDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/music/bitchord/playback/PlaybackService.kt app/src/test/java/com/music/bitchord/RefusedStatusTest.kt
git commit -m "fix(playback): stop retrying a refused stream"
```

---

### Task 2: Refuse a source, not a track

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/StreamChoice.kt`
- Test: create `app/src/test/java/com/music/bitchord/StreamChoiceRefusalTest.kt`

**Interfaces:**
- Produces: `fun StreamChoice.refuse(sourceId: String, videoId: String, status: Int)`
- Produces: `fun StreamChoice.refusedNow(sourceId: String?, videoId: String): Boolean`
- Produces: `fun StreamChoice.refusing(videoId: String): Set<String>` — every source refused for this track, for the resolver's `skip` argument.
- Keeps: `refuseSubstitutes(videoId)`, `substitutesRefused(videoId)`, `forget`, `remember`, `of`, `isSubstitute`.
- Consumes: nothing outside `StreamChoice`; the clock is `android.os.SystemClock.elapsedRealtime()`, which the stub jar returns as `0` and which the test therefore only uses for arithmetic.

- [ ] **Step 1: Write the failing test**

The maps are process state, so the test clears through the existing `forget`/new `clear` hook. Add an `internal fun clearForTest()` rather than reaching into the maps with reflection.

```kotlin
package com.music.bitchord

import com.music.bitchord.playback.StreamChoice
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Refusing one source must not silence the rest. */
class StreamChoiceRefusalTest {

    @Before fun setUp() = StreamChoice.clearForTest()
    @After fun tearDown() = StreamChoice.clearForTest()

    @Test
    fun `a refused source is skipped for that track only`() {
        StreamChoice.refuse("nebula", "abc", 502)

        assertTrue(StreamChoice.refusedNow("nebula", "abc"))
        assertFalse(StreamChoice.refusedNow("jiosaavn", "abc"))
        assertFalse(StreamChoice.refusedNow("nebula", "xyz"))
    }

    @Test
    fun `a track-wide refusal covers every source`() {
        StreamChoice.refuseSubstitutes("abc")

        assertTrue(StreamChoice.refusedNow("nebula", "abc"))
        assertTrue(StreamChoice.refusedNow(null, "abc"))
    }

    @Test
    fun `the refused set names only what is refused`() {
        StreamChoice.refuse("nebula", "abc", 403)
        StreamChoice.refuse("jiosaavn", "abc", 0)

        assertEquals(setOf("nebula", "jiosaavn"), StreamChoice.refusing("abc"))
        assertEquals(emptySet<String>(), StreamChoice.refusing("xyz"))
    }

    @Test
    fun `a status outlives a module that went quiet`() {
        // 10 min for a status, 2 min without one.
        assertEquals(600_000L, StreamChoice.refusalLifetimeMs(502))
        assertEquals(120_000L, StreamChoice.refusalLifetimeMs(0))
    }
}
```

(`assertEquals`/`assertFalse`/`assertTrue` imports as used.)

- [ ] **Step 2: Run it and watch it fail**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "*StreamChoiceRefusalTest*"`
Expected: compile failure — unresolved `refuse`, `refusedNow`, `refusing`, `refusalLifetimeMs`, `clearForTest`.

- [ ] **Step 3: Implement**

In `StreamChoice`, beside `refusedSubstitutes`:

```kotlin
    /**
     * Refusals that name the source that answered badly.
     *
     * The track-wide map above cannot express the distinction this exists for: a
     * module handing over a URL its CDN will not serve says nothing about the
     * module ranked below it, and treating the two the same is how a dead Nebula
     * stream took JioSaavn's 320kbps copy down with it. Keyed by track *and*
     * source so one track's dead URL never silences another track's source.
     */
    private class Refusal(val videoId: String, val sourceId: String, val at: Long, val status: Int)

    private val refusedSources = ConcurrentHashMap<String, Refusal>()

    /**
     * Records that [sourceId] refused to serve [videoId].
     *
     * @param status the HTTP status the server answered with, or `0` when there
     *   was no status to have — a source that handed over a URL and then went
     *   quiet. The status decides how long the refusal stands; see
     *   [refusalLifetimeMs].
     */
    fun refuse(sourceId: String, videoId: String, status: Int) {
        if (refusedSources.size >= MAX_REMEMBERED) refusedSources.clear()
        refusedSources[keyOf(sourceId, videoId)] =
            Refusal(videoId, sourceId, SystemClock.elapsedRealtime(), status)
    }

    /**
     * Whether [sourceId] may not serve [videoId] right now.
     *
     * A null [sourceId] asks the track-wide question, which is the one the
     * upgrade path has an answer for: a die on an upgraded URI names no source
     * [StreamChoice] ever saw.
     */
    fun refusedNow(sourceId: String?, videoId: String): Boolean {
        if (substitutesRefused(videoId)) return true
        if (sourceId == null) return false
        val key = keyOf(sourceId, videoId)
        val refusal = refusedSources[key] ?: return false
        if (SystemClock.elapsedRealtime() - refusal.at <= refusalLifetimeMs(refusal.status)) return true
        refusedSources.remove(key)
        return false
    }

    /** Every source refused for [videoId] right now, for a resolver to leave out. */
    fun refusing(videoId: String): Set<String> {
        val now = SystemClock.elapsedRealtime()
        refusedSources.entries.removeIf {
            it.value.videoId == videoId && now - it.value.at > refusalLifetimeMs(it.value.status)
        }
        return refusedSources.values.filter { it.videoId == videoId }.mapTo(mutableSetOf()) { it.sourceId }
    }

    /**
     * How long a refusal stands. A status is evidence that the URL is dead — the
     * second lookup mints the same URL — so it is held long enough to keep the
     * track playing on another source. A source that simply went quiet has said
     * nothing about itself, and a bad minute there clears up on its own.
     */
    internal fun refusalLifetimeMs(status: Int): Long =
        if (status > 0) REFUSAL_MS else SILENCE_MS

    private fun keyOf(sourceId: String, videoId: String) = "$videoId@$sourceId"

    internal fun clearForTest() {
        chosen.clear()
        refusedSubstitutes.clear()
        refusedSources.clear()
    }
```

Constants:

```kotlin
    private const val KEY_SEPARATOR = '@'
    /** A source that handed over a URL and then said nothing. */
    private const val SILENCE_MS = 2 * 60 * 1000L
```

`refuseSubstitutes` keeps its own lifetime (`REFUSAL_MS`, 10 min) — reuse that constant for the status case so the two long-lived refusals expire together.

- [ ] **Step 4: Run the test**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "*StreamChoiceRefusalTest*"`
Expected: 4 tests pass.

Note on the clock: the stub `android.jar` returns `0` for `SystemClock.elapsedRealtime()`, so every refusal is recorded at `0` and every age is `0` — which is why the expiry test asserts on `refusalLifetimeMs` alone and the rest assert on presence, not on expiry. The expiry branches are exercised by nothing; they are three lines and a map removal, and `refusedSubstitutes` has shipped with the identical shape for several releases.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/music/bitchord/playback/StreamChoice.kt app/src/test/java/com/music/bitchord/StreamChoiceRefusalTest.kt
git commit -m "fix(playback): remember which source refused a track"
```

---

### Task 3: The resolver walks past a refused source

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt` (`prefetchSubstitute` ~364, `substituteForYouTube` ~251, `resolve` ~162, `upgradeFor` ~431)
- Test: create `app/src/test/java/com/music/bitchord/SourceSkipTest.kt`

**Interfaces:**
- Produces: `internal fun SourceResolver.rankedFor(sources: List<MusicSource>, skip: Set<String>): List<MusicSource>` — pure, testable, just `filterNot { it.configId in skip }`.
- Changes: `prefetchSubstitute(target, skip: Set<String> = emptySet())`, `substituteForYouTube(target, skip: Set<String> = emptySet())`, `resolve(..., skip: Set<String> = emptySet())`, `upgradeFor(target, playing, servedBy, skip: Set<String> = emptySet())`.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.music.bitchord

import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** A refused source must be left out of the walk, and only that one. */
class SourceSkipTest {

    private val before = SourceRegistry.configs.value

    @After fun tearDown() { SourceRegistry.configs.value = before }

    @Test
    fun `only the named source is skipped`() {
        SourceRegistry.configs.value = listOf(
            SourceConfig(kind = SourceKind.ADDON, label = "nebula", baseUrl = "https://n.example.com"),
            SourceConfig(kind = SourceKind.ADDON, label = "other", baseUrl = "https://o.example.com"),
            SourceConfig(kind = SourceKind.JIOSAAVN),
            SourceConfig(kind = SourceKind.YOUTUBE),
        )
        val active = SourceRegistry.activeForPlayback()
        val nebula = active.first { it.kind == SourceKind.ADDON && it.displayName == "nebula" }

        val kept = SourceResolver.rankedFor(active, setOf(nebula.configId))

        // Compared as config ids: the built-in sources' labels are whatever
        // SourceKind says, and this test is not about their wording.
        assertEquals(
            active.filterNot { it.configId == nebula.configId }.map { it.configId },
            kept.map { it.configId },
        )
    }

    @Test
    fun `no skip leaves the ranking untouched`() {
        SourceRegistry.configs.value = listOf(
            SourceConfig(kind = SourceKind.JIOSAAVN),
            SourceConfig(kind = SourceKind.YOUTUBE),
        )
        val active = SourceRegistry.activeForPlayback()
        assertEquals(active, SourceResolver.rankedFor(active, emptySet()))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "*SourceSkipTest*"`
Expected: compile failure — unresolved `rankedFor`.

- [ ] **Step 3: Implement `rankedFor` and thread `skip`**

```kotlin
    /**
     * [sources] with anything refused for this track left out.
     *
     * The caller passes the ids rather than this class reading them, because
     * [StreamChoice] lives in the playback layer and `data/sources` does not
     * depend on it. Ranking stays exactly as it was for everything else.
     */
    internal fun rankedFor(sources: List<MusicSource>, skip: Set<String>): List<MusicSource> =
        if (skip.isEmpty()) sources else sources.filterNot { it.configId in skip }
```

Thread it through the four entry points:

- `prefetchSubstitute`: `val quick = rankedFor(rankedAbove(youtube.configId, active), skip).filter { canPrefetch(it.kind) }`
- `substituteForYouTube`: `rankedFor(rankedAbove(youtube.configId, active), skip)`
- `resolve`: `for (source in rankedFor(rankedAbove(configId, active), skip))` in the lossless-upgrade loop, and `val fallbacks = rankedFor(active.filterNot { … }, skip)`
- `upgradeFor`: `rankedFor(rankedAbove(youtube.configId, active).filterNot { it.configId == servedBy }, skip)`

Each parameter is `skip: Set<String> = emptySet()`, so every existing caller compiles unchanged.

- [ ] **Step 4: Run the test and the suite**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "*SourceSkipTest*"`
Expected: 2 tests pass.

Run: `.\gradlew.bat :app:testDevDebugUnitTest`
Expected: green.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt app/src/test/java/com/music/bitchord/SourceSkipTest.kt
git commit -m "feat(sources): leave a refused source out of the walk"
```

---

### Task 4: Recovery refuses the source that failed and walks on

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` (`recoverFrom` ~3711-3897, `fallbackFailedAlternativeToYouTube` ~3903-3948, `resolveWithModulePriority` ~5164-5303)
- Modify: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt` (`refusedStatusIn` helper)
- Test: create `app/src/test/java/com/music/bitchord/RefusedStatusInTest.kt`

**Interfaces:**
- Produces: `internal fun AudioCache.refusedStatusIn(error: Throwable?): Int?` — the first `InvalidResponseCodeException.responseCode` found within 8 causes, or null.
- Consumes: `StreamChoice.refuse`, `StreamChoice.refusing`, `StreamChoice.refusedNow`, `statusIsWorthRetrying`.

- [ ] **Step 1: Write the failing test**

`DataSpec` needs an `android.net.Uri`, which the stub jar cannot build. Take the status off the exception's *public field* instead of off the spec, so the helper is testable:

```kotlin
package com.music.bitchord

import androidx.media3.datasource.HttpDataSource
import com.music.bitchord.playback.refusedStatusIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** The HTTP status behind a playback failure, however deeply Media3 wrapped it. */
class RefusedStatusInTest {

    @Test
    fun `a status is found through the wrapping`() {
        val inner = HttpDataSource.InvalidResponseCodeException(
            /* responseCode = */ 502,
            /* responseMessage = */ "Bad Gateway",
            /* cause = */ null,
            /* headerFields = */ emptyMap(),
            /* dataSpec = */ null,
            /* responseBody = */ null,
        )
        assertEquals(502, refusedStatusIn(IOException("wrapped", inner)))
    }

    @Test
    fun `no status is null`() {
        assertNull(refusedStatusIn(IOException("connection reset")))
        assertNull(refusedStatusIn(null))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "*RefusedStatusInTest*"`
Expected: compile failure — unresolved `refusedStatusIn`.

- [ ] **Step 3: Implement the helper**

Top level in `AudioCache.kt` (it already imports Media3 datasource):

```kotlin
/**
 * The HTTP status behind a failure, however deeply Media3 wrapped it, or null
 * when the failure was not a refusal.
 *
 * Kept at the top level rather than in a class so the recovery path can use it
 * without a cache instance, and so it can be tested without an `ExoPlayer`.
 */
internal fun refusedStatusIn(error: Throwable?): Int? {
    var cause = error
    var depth = 0
    while (cause != null && depth++ < 8) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause?.takeIf { it !== cause }
    }
    return null
}
```

Add `import androidx.media3.datasource.HttpDataSource`.

- [ ] **Step 4: Refuse the source in `recoverFrom`**

The block that currently calls `StreamChoice.refuseSubstitutes(videoId)` becomes source-aware:

```kotlin
        uri?.getQueryParameter("v")
            ?.takeIf { diedOnModuleStream || StreamChoice.isSubstitute(it) }
            ?.let { videoId ->
                // Which source failed, if the choice names one. A die on an
                // upgraded URI has no source name — the upgrade was made by
                // [QualityUpgrade] and never went through [StreamChoice] — and
                // keeps the track-wide refusal.
                val failed = StreamChoice.of(videoId)?.sourceConfigId
                val status = refusedStatusIn(error) ?: 0
                if (failed != null) {
                    StreamChoice.refuse(failed, videoId, status)
                    TrackLog.w(
                        "BitChord",
                        "$videoId broke on $failed; the next source in the ranking serves it for now",
                        about = mediaId,
                    )
                } else {
                    StreamChoice.refuseSubstitutes(videoId)
                    TrackLog.w(
                        "BitChord",
                        "$videoId broke on a substituted stream; YouTube serves it for now",
                        about = mediaId,
                    )
                }
                QualityUpgrade.refuseUpgrades(videoId)
            }
```

`StreamChoice.forget(videoId)` and `AudioCache.discard(uri)` stay exactly where they are, a line below — the pin must go so the next open re-resolves, and the dead bytes must go so the next source does not inherit them.

- **`fallbackFailedAlternativeToYouTube`** stops being a one-way trip to YouTube for a *known* source:

```kotlin
        val failed = videoId?.let { StreamChoice.of(it)?.sourceConfigId }
        // A device with nothing ranked above YouTube has no walk to make: the
        // refusal would name the only source there is, and the track would then
        // fail to resolve at all. YouTube is the right answer there, as now.
        if (failed != null && SourceResolver.canSubstituteForYouTube()) {
            val status = refusedStatusIn(error) ?: 0
            videoId?.let { StreamChoice.refuse(failed, it, status) }
            TrackLog.w("BitChord", "$mediaId failed on $failed; trying the next source", about = mediaId)
            NerdStats.clearDeclared(mediaId)
            return false
        }
        … existing YouTube rebuild, unchanged …
```

Returning `false` hands the track back to `recoverFrom`, which discards the entry and the choice and prepares again — the ordinary path, now with that source refused, so `resolveWithModulePriority` walks to the next one.

- [ ] **Step 5: Make `resolveWithModulePriority` skip refused sources**

Replace the up-front whole-track bail with a skip set:

```kotlin
        val skip = StreamChoice.refusing(videoId)
        // Nothing left to race: every source ranked above YouTube is refused
        // for this track. Racing them again would only be told so again, and a
        // lookup that loses is handed to [QualityUpgrade] rather than dropped —
        // which would schedule a fourth walk to the same verdict.
        if (skip.isNotEmpty() && !SourceResolver.hasUnrefusedModule(skip)) {
            return Resolved.YouTube(StreamResolver.resolve(videoId))
        }
```

and on the module leg:

```kotlin
            withTimeoutOrNull(SUBSTITUTE_TIMEOUT_MS) {
                SourceResolver.substituteForYouTube(target, skip)
            }
```

`hasUnrefusedModule` joins `rankedFor` in `SourceResolver` (Task 3):

```kotlin
    /** Whether anything ranked above YouTube is still free to serve a track. */
    internal fun hasUnrefusedModule(skip: Set<String>): Boolean {
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return false
        return rankedFor(rankedAbove(youtube.configId, active), skip).isNotEmpty()
    }
```

- [ ] **Step 6: Run everything**

Run: `.\gradlew.bat :app:testDevDebugUnitTest`
Expected: green.

Run: `.\gradlew.bat :app:assembleDevDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```
git add app/src/main/java/com/music/bitchord/playback/PlaybackService.kt app/src/main/java/com/music/bitchord/playback/AudioCache.kt app/src/test/java/com/music/bitchord/RefusedStatusInTest.kt
git commit -m "fix(playback): walk down to the next source when one is refused"
```

---

### Task 5: Whole-branch verification

- [ ] **Step 1: Full suite and build**

Run: `.\gradlew.bat :app:testDevDebugUnitTest :app:assembleDevDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, no failing tests.

- [ ] **Step 2: Review the branch**

Diff `dev..feat/addon-prefetch`; check the four new tests fail without their implementation (revert each in a scratch copy if in doubt), and that no `tv/`, `wearos/`, `web/`, `desktopApp/` or `desktopUi/` file is touched.

- [ ] **Step 3: Report**

Report: commit hashes, suite result, build result, and the before/after timeline for `6_Nx1szSyeY` (first 502 at 23:36:16 → recovery instead of 23:36:58).

## Out of scope (deliberately)

- **Prefetch learning that its pin is dead.** Needs bytes actually fetched — the observed incident never got that far — and cancellation arrives through the same failure path, so a naive refusal would blacklist a module on every skip. Its own change, with its own tests.
- **`QualityUpgrade` mid-song refusal.** The second look would still race a source refused during the first. It already has `servedBy` exclusion and `refused` set; wiring `skip` through it is a follow-up.
- **A track that has never started counting its recoveries differently** when each failure is a different source. `MAX_RECOVERIES = 2` bounds it to three sources; a device with four is a theoretical loss.
- **`README`/docs.** No user-facing behaviour is documented there today.
