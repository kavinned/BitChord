# Source Alignment Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every finding from the two-axis review of `c2a86c24^..1e0f60a9`: tests that prove nothing, a swallowed `CancellationException`, a decode that outlives its timeout, a cache key that never repeats, a 3 s stall on the swap path, a clock-drift figure mislabelled as a waveform residual, hardcoded UI strings in the alignment line, and duplicated smart-layer helpers.

**Architecture:** Extract the decisions the tests were re-implementing into real production functions (`StreamAudioAligner.seekPositionMs`, `AppSettings.sourceAlignmentEnabled`, `StreamAudioAligner.acceptedOffset`, `awaitAlignmentOrZero`, `StandbySyncLead`) so the tests call the code under test. Then fix the runtime defects in place, rename the mislabelled telemetry, move the two hardcoded labels into string resources without touching any locale catalogue, and fold the duplicated envelope/`safeClose`/data-source helpers back into `VersionAudioAligner`.

**Tech Stack:** Kotlin, Media3 ExoPlayer, Android MediaExtractor/MediaCodec, Coroutines (`Dispatchers.IO`/`Default`, `withTimeoutOrNull`), Jetpack Compose + Compose Multiplatform resources (`sharedUi`), JUnit 4, `kotlinx-coroutines-test:1.10.2`.

**Spec:** `docs/superpowers/plans/2026-10-20-source-waveform-alignment.md`, `docs/superpowers/plans/2026-10-20-stats-for-nerds-alignment.md`, and the review findings this plan answers.

## Required Skills from `.claude/skills`
- [x] `writing-plans`: Plan structure, TDD task granularity, and clear verification steps.
- [ ] `subagent-driven-development` (or `executing-plans`): Task-by-task execution loop, TDD discipline, and one atomic commit per task.
- [ ] `kotlin-patterns`: Idiomatic Kotlin, null safety, object singletons, computed properties, domain boundaries, zero bloat.
- [ ] `kotlin-coroutines-flows`: Structured concurrency, `CancellationException` propagation, `withTimeoutOrNull`, aborting long decodes on cancellation, `Deferred` awaiting under a deadline.
- [ ] `android-jetpack-compose`: String-resource-backed UI in `PlayerControls.kt` and `AudioPipelineDialog.kt`, `stringResource` formatting args, single-line truncation.
- [ ] `mobile-developer`: Android audio subsystems, MediaCodec/MediaExtractor decode loops, Media3 ExoPlayer swapping, sink playhead granularity, hardware verification.
- [ ] `code-reviewer`: Pre-commit quality audit on every task — resource leaks (`safeClose`), GPLv3 compliance, telemetry language, diff minimality.

## Global Constraints

- Android mobile app only (`app/`, `shared/`, `sharedUi/`). Never touch `tv/`, `wearos/`, `desktopApp/`, `web/`.
- Strictly GPLv3 compatible. No new dependencies — `kotlinx-coroutines-test` is already declared at `app/build.gradle.kts:428`.
- Prefix every shell command with `rtk`.
- Surgical diffs: touch only the lines each task names. No reformatting, no comment rewrites outside the quoted hunks.
- One atomic commit per task; commit messages exactly as written in each task.
- **This plan touches no `values-*/strings.xml` locale catalogue.** `LocaleStringsTest` only enforces parity for translatable keys, so every Android key added here is `translatable="false"`, and the Compose keys go into the base `values/` file only (no parity test covers `composeResources`). Translating them is a separate follow-up.
- The UTF-8 BOMs that `c2a86c24` wrote into 18 locale files stay as they are: stripping them would change line 1 of 18 files for no behavioural gain, and no task here edits those files. Do not add a new one.
- `residual` appears in neither spec — the Task 7 rename is a naming call, not spec conformance. Do not "restore" it to match a document.
- Envelope constants `HOP_MS = 2.0` / `WIN_MS = 10.0` are **deliberate** (confirmed by the maintainer). Never "restore" them to 40 ms / 10 ms.
- The standby-deck sync in `1e0f60a9` is **in scope and independent** of `feat/seamless-source-switch-only`. Never remove it; never add a `sourceCrossfadeEnabled` toggle.

## Review Focus

1. **Alignment offset of exactly 0 ms.** The listener must see no "Aligned" badge at all, not "+0 ms" — in both `SleeveNerdStats` and the Audio pipeline dialog. Test: Task 6.
2. **Negative offset near the start of a track.** The seek must clamp to 0 and still swap, never seek negative. Test: Task 1.
3. **An alignment job still running when the swap is ready.** The swap must land unaligned within the wait budget instead of stalling the player; the job keeps running so its result is cached. Test: Task 4.
4. **A cancelled analysis.** Cancellation must propagate (so the timeout path and the caller's cancellation both work) rather than be reported as "0 ms, confidence fine". No unit test is possible without an Android `Context`/`MediaExtractor`; this line is verified by inspection at Task 2 and by the timeout log appearing in device logs.
5. **Source alignment switched off, or its parent off.** The seek must be the raw position and no offset may be recorded. Test: Task 1.

---

### Task 1: Make the seek math and the gate real

**Skills:** `kotlin-patterns`, `code-reviewer`

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` (beside `smartSourceAlignment`, ~line 403)
- Modify: `app/src/main/java/com/music/bitchord/playback/smart/StreamAudioAligner.kt`
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:2087,2103,2152,2169,2193,4558,4569,4639,4649,4797`
- Test: `app/src/test/java/com/music/bitchord/playback/StreamAlignmentUpgradeTest.kt`
- Test: `app/src/test/java/com/music/bitchord/SourceAlignmentSettingsTest.kt`

**Interfaces:**
- Produces: `AppSettings.sourceAlignmentEnabled: Boolean` (val, computed).
- Produces: `StreamAudioAligner.seekPositionMs(currentPositionMs: Long, offsetMs: Long): Long`.

- [ ] **Step 1: Write the failing tests**

In `StreamAlignmentUpgradeTest.kt`, delete the private `calculateSeekPosition` and call `StreamAudioAligner.seekPositionMs` in the four seek tests, dropping their `smartSourceAlignment`/`smartVersionAlignment` parameters (the gate is no longer part of the seek). Delete `alignment disabled when smartSourceAlignment is false` and `alignment disabled when smartVersionAlignment parent is false` — that contract moves to `AppSettings.sourceAlignmentEnabled`, asserted in `SourceAlignmentSettingsTest`. Leave `standby sync lead converges` untouched; Task 5 replaces it.

```kotlin
@Test
fun `negative seek near track start clamps to 0ms without stalling`() {
    assertEquals(0L, StreamAudioAligner.seekPositionMs(currentPositionMs = 100L, offsetMs = -500L))
}
```

Add the gate assertion to the existing `alignment disabled when smartSourceAlignment is false`-style coverage in `SourceAlignmentSettingsTest.kt`. That file's proxy currently returns `null` for every reader other than `getBoolean`; widen it so `readAll()` can run:

```kotlin
"getBoolean" -> (preferencesMap[args[0] as String] as? Boolean) ?: (args[1] as Boolean)
else -> if (method.name.startsWith("get") && args != null && args.size == 2) args[1]
        else if (method.name == "edit") editor
        else null
```

Add:

```kotlin
private fun readAll() {
    val method = AppSettings::class.java.getDeclaredMethod("readAll")
    method.isAccessible = true
    method.invoke(AppSettings)
}

@Test
fun `smartSourceAlignment defaults to off when nothing is stored`() {
    readAll()
    assertFalse(AppSettings.smartSourceAlignment.value)
}

@Test
fun `smartSourceAlignment reads a stored value`() {
    preferencesMap["smart_source_alignment"] = true
    readAll()
    assertTrue(AppSettings.smartSourceAlignment.value)
}

@Test
fun `sourceAlignmentEnabled requires the smartVersionAlignment parent`() {
    preferencesMap["smart_source_alignment"] = true
    preferencesMap["smart_version_alignment"] = false
    readAll()
    assertFalse(AppSettings.sourceAlignmentEnabled)

    preferencesMap["smart_version_alignment"] = true
    readAll()
    assertTrue(AppSettings.sourceAlignmentEnabled)
}
```

Delete `smartSourceAlignment defaults to false` (it sets the flow then asserts it).

- [ ] **Step 2: Run tests to verify they fail**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.StreamAlignmentUpgradeTest" --tests "com.music.bitchord.SourceAlignmentSettingsTest"`
Expected: FAIL — unresolved `seekPositionMs` / `sourceAlignmentEnabled`.

- [ ] **Step 3: Implement**

`AppSettings.kt`, beside `val smartSourceAlignment = MutableStateFlow(false)`:

```kotlin
/** [smartVersionAlignment] is the parent: source alignment may not run without it. */
val sourceAlignmentEnabled: Boolean
    get() = smartVersionAlignment.value && smartSourceAlignment.value
```

`StreamAudioAligner.kt`, above `resolveWindow`:

```kotlin
/** Where the incoming deck must start so [offsetMs] of content compensation is applied. */
fun seekPositionMs(currentPositionMs: Long, offsetMs: Long): Long =
    (currentPositionMs + offsetMs).coerceAtLeast(0L)
```

In `PlaybackService.kt`, replace every `(X + alignmentOffsetMs).coerceAtLeast(0L)` with `StreamAudioAligner.seekPositionMs(X, alignmentOffsetMs)` (lines 2087, 2103, 2152, 2169, 2193) and every `(activePlayer.currentPosition + offsetMs).coerceAtLeast(0L)` with `StreamAudioAligner.seekPositionMs(activePlayer.currentPosition, offsetMs)` (lines 4569, 4639, 4649). Replace the two gate expressions `AppSettings.smartSourceAlignment.value && AppSettings.smartVersionAlignment.value` (lines 4558, 4797) with `AppSettings.sourceAlignmentEnabled`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.StreamAlignmentUpgradeTest" --tests "com.music.bitchord.SourceAlignmentSettingsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt \
  app/src/main/java/com/music/bitchord/playback/smart/StreamAudioAligner.kt \
  app/src/main/java/com/music/bitchord/playback/PlaybackService.kt \
  app/src/test/java/com/music/bitchord/playback/StreamAlignmentUpgradeTest.kt \
  app/src/test/java/com/music/bitchord/SourceAlignmentSettingsTest.kt
rtk git commit -m "test(playback): exercise real seek math and alignment gate"
```

---

### Task 2: Stop swallowing cancellation; let the timeout stop the decode

**Skills:** `kotlin-patterns`, `kotlin-coroutines-flows`, `mobile-developer`, `code-reviewer`

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/smart/StreamAudioAligner.kt:163-217`
- Test: `app/src/test/java/com/music/bitchord/playback/smart/StreamAudioAlignerTest.kt`

**Interfaces:**
- Produces: `StreamAudioAligner.acceptedOffset(offsetMs: Long, confidence: Double): Long` (internal).

- [ ] **Step 1: Write the failing test**

Replace the tautological `low confidence correlation falls back to 0ms` with a test of the real gate, and add the empty-signal guard:

```kotlin
@Test
fun `offset is accepted only at or above the confidence floor`() {
    assertEquals(300L, StreamAudioAligner.acceptedOffset(300L, StreamAudioAligner.MIN_CONFIDENCE))
    assertEquals(0L, StreamAudioAligner.acceptedOffset(300L, 0.39))
    assertEquals(-200L, StreamAudioAligner.acceptedOffset(-200L, 0.9))
}

@Test
fun `empty signals report no offset and no confidence`() {
    val (offset, confidence) = StreamAudioAligner.computeOffsetFromSignals(
        sourceSamples = FloatArray(0),
        sourceSampleRate = 44100.0,
        sourceStartSec = 0.0,
        targetSamples = FloatArray(0),
        targetSampleRate = 44100.0,
        targetStartSec = 0.0,
    )
    assertEquals(0L, offset)
    assertEquals(0.0, confidence, 0.0)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.StreamAudioAlignerTest"`
Expected: FAIL — unresolved `acceptedOffset`.

- [ ] **Step 3: Implement**

Add to `StreamAudioAligner`, above `findStreamOffsetMs`:

```kotlin
/** The measured offset, or 0L when the correlation was too weak to trust. */
internal fun acceptedOffset(offsetMs: Long, confidence: Double): Long =
    if (confidence >= MIN_CONFIDENCE) offsetMs else 0L
```

Use it in `findStreamOffsetMs`, replacing the `if (confidence >= MIN_CONFIDENCE) { ... }` block with a call to `acceptedOffset` (keep the cache write and the log for the accepted case; log the fallback when the result is 0L because of confidence).

Pass `abort = { !isActive }` to all four `AudioDecoder.decodeRegion` calls (the two primary calls and the two secondary calls). The lambda's receiver is the `withContext(Dispatchers.IO)` `CoroutineScope`, so `isActive` is the job's own state: on timeout the job is cancelled and each decode loop exits on its next pass.

Change the catch to rethrow cancellation before the generic handler, and import `kotlinx.coroutines.CancellationException`:

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.StreamAudioAlignerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add app/src/main/java/com/music/bitchord/playback/smart/StreamAudioAligner.kt \
  app/src/test/java/com/music/bitchord/playback/smart/StreamAudioAlignerTest.kt
rtk git commit -m "fix(playback): propagate cancellation and stop decode on timeout"
```

---

### Task 3: Key the offset cache by stream URL, not by upgrade generation

**Skills:** `kotlin-patterns`, `code-reviewer`

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/smart/StreamAudioAligner.kt:138-160,204`
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4800-4806`
- Test: `app/src/test/java/com/music/bitchord/playback/smart/StreamAudioAlignerTest.kt`

**Interfaces:**
- Changes: `findStreamOffsetMs(context, mediaId, currentPosMs, currentSong, targetStreamUri, streamUrl: String)` — the new parameter is the real stream URL and becomes the cache key half.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `cache entries are keyed by stream url, so a repeat upgrade reuses the measurement`() {
    val mediaId = "track_xyz"
    StreamAudioAligner.putCachedOffsetMs(mediaId, "https://cdn/stream.flac", 320L)

    // A second upgrade of the same track resolves the same stream URL, whatever
    // generation marker the cache URI carries.
    assertEquals(320L, StreamAudioAligner.getCachedOffsetMs(mediaId, "https://cdn/stream.flac"))
    assertNull(StreamAudioAligner.getCachedOffsetMs(mediaId, "bitchord://watch?v=abc&q=hifi-2"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.StreamAudioAlignerTest"`
Expected: PASS already (the cache accessors are dumb) — this test pins the contract the production key must satisfy. Continue; the real failure is the compile break in Step 3.

- [ ] **Step 3: Implement**

In `findStreamOffsetMs`, add `streamUrl: String` after `targetStreamUri`, and replace every `mediaId to streamKey` with `mediaId to streamUrl`; delete the `streamKey` local. In `PlaybackService.kt` pass `streamUrl = stream.url`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.StreamAudioAlignerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add app/src/main/java/com/music/bitchord/playback/smart/StreamAudioAligner.kt \
  app/src/main/java/com/music/bitchord/playback/PlaybackService.kt \
  app/src/test/java/com/music/bitchord/playback/smart/StreamAudioAlignerTest.kt
rtk git commit -m "fix(playback): key alignment cache by stream url, not upgrade generation"
```

---

### Task 4: Bound the wait for a pre-computed offset

**Skills:** `kotlin-patterns`, `kotlin-coroutines-flows`, `code-reviewer`

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/smart/StreamAudioAligner.kt` (top-level, after the object)
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4892`
- Test: `app/src/test/java/com/music/bitchord/playback/smart/StreamAudioAlignerTest.kt`

**Interfaces:**
- Produces: `internal suspend fun awaitAlignmentOrZero(job: Deferred<Long>?, waitMs: Long): Long`.
- Produces: `PlaybackService.Companion.ALIGN_SWAP_WAIT_MS = 500L`.

- [ ] **Step 1: Write the failing test**

Import `kotlinx.coroutines.CompletableDeferred` and `kotlinx.coroutines.runBlocking` alongside the existing `assertEquals`.

```kotlin
@Test
fun `a slow alignment does not hold the swap, and its job keeps running`() = runBlocking {
    val never = CompletableDeferred<Long>()
    val started = System.currentTimeMillis()
    assertEquals(0L, awaitAlignmentOrZero(never, waitMs = 50L))
    assertTrue("waited ${System.currentTimeMillis() - started}ms", System.currentTimeMillis() - started < 1000L)
    assertTrue("the job must survive the wait so it can still cache", never.isActive)
}

@Test
fun `a finished alignment is returned as-is`() = runBlocking {
    val done = CompletableDeferred(275L)
    assertEquals(275L, awaitAlignmentOrZero(done, waitMs = 50L))
    assertEquals(0L, awaitAlignmentOrZero(null, waitMs = 50L))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.StreamAudioAlignerTest"`
Expected: FAIL — unresolved `awaitAlignmentOrZero`.

- [ ] **Step 3: Implement**

Top-level in `StreamAudioAligner.kt` (outside the `object`):

```kotlin
/**
 * The alignment already computed, or 0L once [waitMs] passes.
 *
 * The measurement runs alongside the audition, so it is normally finished by
 * the time the swap is ready. When it is not, landing unaligned beats stalling
 * the player for the rest of the deadline; [job] keeps running either way and
 * caches its answer for the next upgrade.
 */
internal suspend fun awaitAlignmentOrZero(job: Deferred<Long>?, waitMs: Long): Long =
    if (job == null) 0L else withTimeoutOrNull(waitMs) { job.await() } ?: 0L
```

Add imports `kotlinx.coroutines.Deferred`, `kotlinx.coroutines.withTimeoutOrNull`. In `PlaybackService.kt`, add to the companion beside `UPGRADE_CROSSFADE_POLL_MS`:

```kotlin
/** Longest the swap waits on a pre-computed alignment before landing unaligned. */
const val ALIGN_SWAP_WAIT_MS = 500L
```

and replace `val offsetMs = alignJob?.await() ?: 0L` with:

```kotlin
val offsetMs = awaitAlignmentOrZero(alignJob, ALIGN_SWAP_WAIT_MS)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.StreamAudioAlignerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add app/src/main/java/com/music/bitchord/playback/smart/StreamAudioAligner.kt \
  app/src/main/java/com/music/bitchord/playback/PlaybackService.kt \
  app/src/test/java/com/music/bitchord/playback/smart/StreamAudioAlignerTest.kt
rtk git commit -m "fix(playback): cap how long a swap waits for stream alignment"
```

---

### Task 5: Make the standby sync lead testable

**Skills:** `kotlin-patterns`, `kotlin-coroutines-flows`, `code-reviewer`

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4676-4711,4722` (top-level class in the same file)
- Test: `app/src/test/java/com/music/bitchord/playback/StreamAlignmentUpgradeTest.kt:96-112`

**Interfaces:**
- Produces: `internal class StandbySyncLead` with `val leadMs: Long` and `fun observe(driftMs: Long): Long`.

- [ ] **Step 1: Write the failing test**

Replace `standby sync lead converges to mean of observed seek latencies`:

```kotlin
@Test
fun `standby sync lead converges to the mean of observed seek latencies`() {
    val lead = StandbySyncLead()

    // The first measurement only establishes the baseline; nothing is seeked on it yet.
    assertEquals(0L, lead.observe(280L))

    // That seek cost 0 + 20ms; one sample.
    assertEquals(20L, lead.observe(20L))
    assertTrue(lead.leadMs == 20L)

    // Next seek overshot by 40ms: samples are 20 and -20, mean 0.
    assertEquals(0L, lead.observe(-40L))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.StreamAlignmentUpgradeTest"`
Expected: FAIL — unresolved `StandbySyncLead`.

- [ ] **Step 3: Implement**

Top-level in `PlaybackService.kt`, near `measureStandbyDriftMs`:

```kotlin
/**
 * The per-swap estimate of what a near-seek costs the standby deck.
 *
 * One sample is noisy, so the lead is the mean of every correction seen so far.
 * The first drift measurement only establishes the baseline.
 */
internal class StandbySyncLead {
    private val latencies = ArrayList<Long>()
    private var seen = 0

    var leadMs = 0L
        private set

    fun observe(driftMs: Long): Long {
        seen++
        if (seen > 1) {
            latencies += leadMs + driftMs
            leadMs = latencies.average().toLong()
        }
        return leadMs
    }
}
```

In the sync loop replace `var lead = 0L` / `val latencies = ArrayList<Long>()` with `val lead = StandbySyncLead()`, log `lead=${lead.leadMs}ms`, drop the `if (attempt > 0) { ... }` block in favour of `val currentLead = lead.observe(drift)`, seek by `drift + currentLead`, and record `offsetMs + lead.leadMs` in `NerdStats.recordAlignmentOffset`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.StreamAlignmentUpgradeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add app/src/main/java/com/music/bitchord/playback/PlaybackService.kt \
  app/src/test/java/com/music/bitchord/playback/StreamAlignmentUpgradeTest.kt
rtk git commit -m "test(playback): exercise the standby sync lead instead of a copy of it"
```

---

### Task 6: Omit the badge when the offset is zero

**Skills:** `kotlin-patterns`, `code-reviewer`

**Files:**
- Modify: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt:70`
- Test: `app/src/test/java/com/music/bitchord/playback/audio/NerdStatsRegressionTest.kt`

**Interfaces:**
- Produces: `NerdStats.Snapshot.shownAlignmentOffsetMs: Long?` (computed val; null when absent or 0).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `zero or absent alignment offset is not reported as aligned`() {
    assertNull(snapshot("track_1").shownAlignmentOffsetMs)
    assertNull(snapshot("track_1").copy(alignmentOffsetMs = 0L).shownAlignmentOffsetMs)
    assertEquals(-120L, snapshot("track_1").copy(alignmentOffsetMs = -120L).shownAlignmentOffsetMs)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.audio.NerdStatsRegressionTest"`
Expected: FAIL — unresolved `shownAlignmentOffsetMs`.

- [ ] **Step 3: Implement**

In `NerdStats.Snapshot`, after `alignmentOffsetMs`:

```kotlin
/** The compensation worth showing, or null when there is none: 0 ms is "no compensation". */
val shownAlignmentOffsetMs: Long?
    get() = alignmentOffsetMs?.takeIf { it != 0L }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.audio.NerdStatsRegressionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt \
  app/src/test/java/com/music/bitchord/playback/audio/NerdStatsRegressionTest.kt
rtk git commit -m "feat(telemetry): treat a zero alignment offset as unaligned"
```

---

### Task 7: Rename the clock-drift figure out of "residual"

**Skills:** `kotlin-patterns`, `code-reviewer`

**Files:**
- Modify: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt:72,346-372,427`
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:5945`
- Modify: `app/src/main/java/com/music/bitchord/ui/components/AudioPipelineDialog.kt:286`
- Modify: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt:388`
- Test: `app/src/test/java/com/music/bitchord/playback/audio/NerdStatsRegressionTest.kt`

**Interfaces:**
- Renames: `Snapshot.alignmentResidualMs` → `Snapshot.syncDriftMs`; `NerdStats.getAlignmentResidual` → `NerdStats.getSyncDrift`; `recordAlignmentOffset(mediaId, offsetMs, residualMs)` → `(mediaId, offsetMs, syncDriftMs)`.
- Consumes: `Snapshot.shownAlignmentOffsetMs` from Task 6.

- [ ] **Step 1: Write the failing tests**

Rename inside `alignment offset and residual are recorded...` and assert the new doc'd meaning:

```kotlin
@Test
fun `sync drift is recorded, cleared on a residual-free update, and forgotten with the track`() {
    assertNull(NerdStats.getSyncDrift("track_1"))

    NerdStats.recordAlignmentOffset("track_1", 145L, -8L)
    assertEquals(-8L, NerdStats.getSyncDrift("track_1"))

    NerdStats.recordAlignmentOffset("track_1", 20L, null)
    assertNull(NerdStats.getSyncDrift("track_1"))

    NerdStats.clearDeclared("track_1")
    assertNull(NerdStats.getAlignmentOffset("track_1"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.audio.NerdStatsRegressionTest"`
Expected: FAIL — unresolved `getSyncDrift`.

- [ ] **Step 3: Implement**

Rename the field, the map (`alignmentResiduals` → `syncDrifts`), the getter, and the `recordAlignmentOffset` parameter. Replace the field's doc with:

```kotlin
/**
 * How far the two decks' clocks still disagreed after the last sync seek, in
 * ms. Measured on player positions, so its resolution is the sink playhead
 * step (~275ms) — this is clock drift, not a content measurement.
 */
val syncDriftMs: Long? = null,
```

Update the three consumers (`publishNerdStats`, `AudioPipelineDialog`, `PlayerControls`) to the new names.

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.audio.NerdStatsRegressionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt \
  app/src/main/java/com/music/bitchord/playback/PlaybackService.kt \
  app/src/main/java/com/music/bitchord/ui/components/AudioPipelineDialog.kt \
  sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt \
  app/src/test/java/com/music/bitchord/playback/audio/NerdStatsRegressionTest.kt
rtk git commit -m "refactor(telemetry): name the deck clock gap sync drift, not residual"
```

---

### Task 8: Move both labels into string resources

**Skills:** `kotlin-patterns`, `android-jetpack-compose`, `code-reviewer`

**Files:**
- Modify: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt:387-398`
- Modify: `app/src/main/java/com/music/bitchord/ui/components/AudioPipelineDialog.kt:285-292`
- Modify: `app/src/main/res/values/strings.xml` (add `pipeline_alignment_drift`; leave `pipeline_alignment` untouched)
- Modify: `sharedUi/src/commonMain/composeResources/values/strings.xml` (base catalogue only)
- Test: `app/src/test/java/com/music/bitchord/LocaleStringsTest.kt` (existing, must stay green)

**Interfaces:**
- Produces: compose-resource keys `nerd_stats_aligned` (`"Aligned %1$s"`), `nerd_stats_aligned_drift` (`"Aligned %1$s (drift %2$s)"`).
- Produces: Android key `pipeline_alignment_drift` (`"%1$s (drift %2$s)"`, `translatable="false"`). `pipeline_alignment` keeps `translatable="false"`.
- Consumes: `Snapshot.shownAlignmentOffsetMs` (Task 6), `Snapshot.syncDriftMs` (Task 7).

- [ ] **Step 1: Add the three strings, touching no locale catalogue**

`sharedUi/src/commonMain/composeResources/values/strings.xml`:

```xml
<string name="nerd_stats_aligned">Aligned %1$s</string>
<string name="nerd_stats_aligned_drift">Aligned %1$s (drift %2$s)</string>
```

`app/src/main/res/values/strings.xml`, beside the existing `pipeline_alignment`:

```xml
<string name="pipeline_alignment_drift" translatable="false">%1$s (drift %2$s)</string>
```

- [ ] **Step 2: Confirm no key became translatable**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.LocaleStringsTest"`
Expected: PASS. It fails only when a translatable key is missing from a locale, so green here means every new Android key is untranslatable and no catalogue owes a translation.

- [ ] **Step 3: Wire the UI**

`PlayerControls.kt`:

```kotlin
nerdStats?.shownAlignmentOffsetMs?.let { offset ->
    val offsetText = "%+d ms".format(Locale.ROOT, offset)
    val drift = nerdStats.syncDriftMs
    Text(
        text = if (drift != null) {
            stringResource(Res.string.nerd_stats_aligned_drift, offsetText, "%+d ms".format(Locale.ROOT, drift))
        } else {
            stringResource(Res.string.nerd_stats_aligned, offsetText)
        },
        style = nerdStyle,
        color = Color.White.copy(alpha = 0.65f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}
```

`AudioPipelineDialog.kt`:

```kotlin
nerdStats?.shownAlignmentOffsetMs?.let { offset ->
    val offsetText = "%+d ms".format(Locale.ROOT, offset)
    val drift = nerdStats.syncDriftMs
    PipelineRow(
        stringResource(R.string.pipeline_alignment),
        if (drift != null) {
            stringResource(
                R.string.pipeline_alignment_drift,
                offsetText,
                "%+d ms".format(Locale.ROOT, drift),
            )
        } else {
            offsetText
        },
    )
}
```

- [ ] **Step 4: Verify**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.LocaleStringsTest"`
Expected: PASS.

Run: `rtk proxy grep -rn '"Aligned\|residual %' app/src/main sharedUi/src`
Expected: no output — no hardcoded alignment label survives, and the old `residual` wording is gone.

Run: `rtk git diff --stat -- "app/src/main/res/values-*/strings.xml" "sharedUi/src/commonMain/composeResources/values*/strings.xml"`
Expected: empty — no locale catalogue is touched.

- [ ] **Step 5: Commit**

```bash
rtk git add app/src/main/java/com/music/bitchord/ui/components/AudioPipelineDialog.kt \
  sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt \
  app/src/main/res/values/strings.xml \
  sharedUi/src/commonMain/composeResources/values/strings.xml
rtk git commit -m "feat(ui): route the alignment labels through string resources"
```

---

### Task 9: Fold the duplicated helpers back into `VersionAudioAligner`

**Skills:** `kotlin-patterns`, `mobile-developer`, `code-reviewer`

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/smart/VersionAudioAligner.kt:144-145,248-249,270,292-324`
- Modify: `app/src/main/java/com/music/bitchord/playback/smart/StreamAudioAligner.kt:70-93,224-245`
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4596-4606,2106-2119`

**Interfaces:**
- Produces: `VersionAudioAligner.computeEnergyEnvelope(samples, sampleRate, hopMs, winMs)` (internal, already parameterised), `VersionAudioAligner.openDataSource(context: Context, song: Song)` (internal), `StreamAudioSource.safeClose(ds)` (already public).
- Produces: `PlaybackService.configureStandby(standby, active, items, index, startPositionMs, mediaId)` (private).

- [ ] **Step 1: Write the failing check**

The existing aligner tests are the regression net for the envelope move; no new test. The check is that the suite still passes after the deletion.

- [ ] **Step 2: Baseline the suite**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.*"`
Expected: PASS (baseline before refactor).

- [ ] **Step 3: Implement**

`VersionAudioAligner.kt`: change `private fun computeEnergyEnvelope` → `internal fun computeEnergyEnvelope`; change `private fun openDataSource` → `internal fun openDataSource`; delete `private fun safeClose` and replace its four call sites with `StreamAudioSource.safeClose(...)`.

`StreamAudioAligner.kt`: delete its `computeEnergyEnvelope`; in `computeOffsetFromSignals` call `VersionAudioAligner.computeEnergyEnvelope(samples, rate, HOP_MS, WIN_MS)` (pass both explicitly — `VersionAudioAligner`'s defaults are its own 10 ms/40 ms pair). Replace `openSourceDataSource(context, song)` with `if (context == null || song == null) null else VersionAudioAligner.openDataSource(context, song)` and delete the private helper.

`PlaybackService.kt`: extract the standby setup (stop, clearMediaItems, skipSilence, repeat, shuffle, speed, volume, setMediaItems, spareLoudness().track, playWhenReady, prepare) into

```kotlin
private fun configureStandby(
    standby: Player,
    active: Player,
    items: List<MediaItem>,
    index: Int,
    startPositionMs: Long,
    mediaId: String,
)
```

and call it from both `swapToAlignedStream` and the version-swap path, preserving the existing comment above `setPlaybackSpeed`.

- [ ] **Step 4: Verify**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.*" --tests "com.music.bitchord.playback.StreamAlignmentUpgradeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add app/src/main/java/com/music/bitchord/playback/smart \
  app/src/main/java/com/music/bitchord/playback/PlaybackService.kt
rtk git commit -m "refactor(playback): share the envelope, close and standby setup helpers"
```

---

### Task 10: Remove the dead surface

**Skills:** `kotlin-patterns`, `code-reviewer`

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/smart/StreamAudioAligner.kt:28-65,204`
- Modify: `app/src/main/java/com/music/bitchord/playback/smart/StreamAudioSource.kt:95`
- Test: `app/src/test/java/com/music/bitchord/playback/smart/StreamAudioAlignerTest.kt:100-116`

**Interfaces:**
- Changes: `resolveWindow(currentPosMs: Long): Pair<Double, Double>` (drops `hasOpening`).
- Changes: `StreamAudioSource.open(context: Context?, uri: Uri): MediaDataSource?` (drops `streamUrl`).
- Changes: `HOP_MS`, `WIN_MS`, `MIN_CONFIDENCE`, `TIMEOUT_MS`, `ANALYSIS_WARM_BYTES`, and the four window constants become `internal`.

- [ ] **Step 1: Write the failing test**

Update the window test to the single-argument production function:

```kotlin
@Test
fun `window falls back around the playback position and clamps at the track start`() {
    assertEquals(40.0 to 55.0, StreamAudioAligner.resolveWindow(currentPosMs = 45000L))
    assertEquals(0.0 to 12.0, StreamAudioAligner.resolveWindow(currentPosMs = 2000L))
}
```

and fix the stale comment in `aligns synthetic signals with positive latency offset (+350ms)` to `// HOP_MS is 2ms; ±15ms leaves room for envelope smear`.

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.StreamAudioAlignerTest"`
Expected: FAIL — no `resolveWindow(currentPosMs)` overload.

- [ ] **Step 3: Implement**

Drop the `hasOpening` parameter and its `if`; keep the secondary branch body. Update the call site (`resolveWindow(hasOpening = false, currentPosMs = currentPosMs)` → `resolveWindow(currentPosMs)`). Keep `PRIMARY_WINDOW_START_SEC`/`PRIMARY_WINDOW_END_SEC`: the primary decode still passes them inline at lines 165-174. Delete the unused `streamUrl` parameter from `StreamAudioSource.open` and its call site. Change the constants' visibility to `internal`. Route the production cache write through `putCachedOffsetMs` so it is not test-only.

- [ ] **Step 4: Verify**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add app/src/main/java/com/music/bitchord/playback/smart \
  app/src/test/java/com/music/bitchord/playback/smart
rtk git commit -m "refactor(playback): drop unused alignment parameters and test-only surface"
```

---

### Task 11: Whole-branch verification

**Skills:** `mobile-developer`

**Files:** none.

- [ ] **Step 1: Full unit suite**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest`
Expected: BUILD SUCCESSFUL, no new failures.

- [ ] **Step 2: Build**

Run: `rtk .\gradlew.bat assembleDevDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Diff sanity**

Run: `rtk git diff c2a86c247f9c20219f5a1306f650263e60f2f356^..HEAD --stat`
Expected: only the files this plan names, plus the test files it rewrites. No `values-*/strings.xml` locale file appears at all.

Run: `rtk proxy grep -rn "alignmentResidualMs\|getAlignmentResidual\|calculateSeekPosition" app shared sharedUi`
Expected: no output.

- [ ] **Step 4: Device check (maintainer)**

Install `app-dev-arm64-v8a-debug.apk`, play a track until a stream upgrade lands, open Stats for Nerds and the Audio pipeline dialog: the alignment line reads `Aligned +N ms`, never `+0 ms`, and the drift suffix appears only after a standby-deck swap. Labels stay English for now — translating them is a follow-up.
