# Source Waveform Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate audio stutter and beat clashing during mid-playback stream quality upgrades by cross-correlating waveform envelopes across source and upgraded streams and applying a compensated seek offset. Fully decoupled from unmerged dual-player crossfade branch (`feat/seamless-source-switch-only`).

**Architecture:** Reuse existing hardware `MediaCodec` and FFT-based normalized cross-correlation engine (`AudioDecoder` + `VersionAudioAligner` math). Bridge random-access stream reading via `StreamAudioSource`. Run correlation concurrently in the background during `auditionUpgrade` so `offsetMs` is pre-calculated before the swap. If opening bytes are absent from cache, dynamically correlate around current playback position. When swapping, seek to `(now.position + offsetMs).coerceAtLeast(0L)`.

**Tech Stack:** Kotlin, Android MediaCodec / MediaExtractor, Media3 ExoPlayer & AudioCache, Coroutines (`Dispatchers.IO` / `Dispatchers.Default`), Jetpack Compose, JUnit 4.

**Spec & Branch:** Branch `feat/source-waveform-alignment` based on `upstream/v1.8.1` (`7d956f21`). Decoupled from `feat/seamless-source-switch-only`.

## Required Skills from `.agents/skills`
- [x] `writing-plans`: Plan structure, TDD task granularity, and clear verification steps.
- [ ] `executing-plans`: Task-by-task execution loop, maintaining TDD discipline and commit atomicity.
- [ ] `kotlin-patterns`: Idiomatic Kotlin, null safety, object singletons, domain boundaries, zero bloat.
- [ ] `kotlin-coroutines-flows`: Structured concurrency, timeouts (`withTimeoutOrNull(3000L)`), `StateFlow`, async cancellation cleanup.
- [ ] `android-jetpack-compose`: Declarative UI in `SettingsSheet.kt`, `collectAsStateWithLifecycle`, reactive switch gating.
- [ ] `mobile-developer`: Android audio subsystems, MediaCodec PCM decoding, ExoPlayer MediaItem swapping, hardware testing.
- [ ] `code-reviewer`: Pre-commit code quality audits, resource leak prevention (`safeClose`), GPLv3 compliance.

## Global Constraints
- **Scope**: Android mobile app only (`app/` and `shared/`). Ignore `tv/`, `wearos/`, `web/`, `desktopApp/`.
- **License**: Strict GPLv3 compliance. No new external dependencies.
- **Branch**: `feat/source-waveform-alignment` based on `upstream/v1.8.1` (`7d956f21`).
- **Decoupling**: Do NOT depend on or include `feat/seamless-source-switch-only` (PR #932). No `sourceCrossfadeEnabled` toggle.
- **Atomic Commits**: Exactly one atomic commit per task.

## Key Design Decisions (from /grill-me)
1. **Analysis Timing**: Triggered concurrently during `auditionUpgrade` in the background. While the audition player pre-buffers the stream header, waveform correlation decodes and computes `offsetMs`. Pre-calculated before the swap executes, introducing zero latency overhead.
2. **Audio Byte Acquisition**: Pre-warms 1.5 MB of audio data via `AudioCache.warmRange` in the background. This allows `AudioDecoder.decodeRegion` to decode directly from local disk in ~25ms.
3. **Dynamic Window Fallback**:
   - Primary: Analyze opening window `[0.0s .. 15.0s]`.
   - Secondary (if opening evicted/missing): Decode a 15.0s window around current playback position `[max(0.0, currentPos - 5.0) .. currentPos + 10.0]`.
   - Fallback: If decoding fails or correlation confidence < 0.40 or timeout (>3.0s), fall back to `0L` (raw `now.position`).
4. **Negative Seek Clamping**: If `(now.position + offsetMs) < 0L` near the start of a track, clamp directly to `0L` (`.coerceAtLeast(0L)`) and swap immediately without stalling.
5. **In-Memory Caching**: Cache computed offsets in a `ConcurrentHashMap` keyed by `(mediaId, streamUrl)` so repeat upgrades/reverts reuse the measurement instantly.

---

### Task 1: Settings, Preference, and UI Gating

**Skills:** `android-jetpack-compose`, `kotlin-patterns`, `code-reviewer`

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt`
- Modify: `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt`
- Create: `app/src/test/java/com/music/bitchord/SourceAlignmentSettingsTest.kt`

- [ ] **Step 1: Write unit test for settings default, persistence, and gating**
  - Verify `smartSourceAlignment` defaults to `false`.
  - Verify setting updates persist in `SharedPreferences`.
  - Verify UI gating: dependent on `smartVersionAlignment`.

- [ ] **Step 2: Run test to verify failure**
  - Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.SourceAlignmentSettingsTest"`
  - Expected: FAIL.

- [ ] **Step 3: Implement settings and UI**
  - In `strings.xml`:
    - Add `smart_source_alignment` ("Align upgraded audio sources").
    - Add `smart_source_alignment_subtitle` ("Analyze waveforms when upgrading stream quality to eliminate beat skips").
  - In `AppSettings.kt`:
    - Add `val smartSourceAlignment = MutableStateFlow(false)`.
    - Add `setSmartSourceAlignment(value: Boolean)`.
    - Add `KEY_SMART_SOURCE_ALIGNMENT` reading and writing.
  - In `SettingsSheet.kt`:
    - Use `collectAsStateWithLifecycle` for `smartSourceAlignment`.
    - Add switch row directly below `smartVersionAlignment` in Advanced Options.
    - Set `enabled = smartVersionAlignment`.

- [ ] **Step 4: Run test to verify pass**
  - Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.SourceAlignmentSettingsTest"`
  - Expected: PASS.

- [ ] **Step 5: Code review & commit (Atomic Commit 1)**
  - Review: verify no unrelated changes, style matches existing settings rows.
  - Run: `git commit -m "feat(settings): add smartSourceAlignment toggle gated on smartVersionAlignment"`

---

### Task 2: Stream Random-Access MediaDataSource (`StreamAudioSource`)

**Skills:** `mobile-developer`, `kotlin-patterns`, `code-reviewer`

**Files:**
- Create: `app/src/main/java/com/music/bitchord/playback/smart/StreamAudioSource.kt`
- Create: `app/src/test/java/com/music/bitchord/playback/smart/StreamAudioSourceTest.kt`

- [ ] **Step 1: Write unit test for `StreamAudioSource`**
  - Test byte-range reads over simulated cache and stream sources.
  - Verify EOF handling (`-1`), bounds checking, and resource closing.

- [ ] **Step 2: Run test to verify failure**
  - Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.StreamAudioSourceTest"`
  - Expected: FAIL.

- [ ] **Step 3: Implement `StreamAudioSource`**
  - Extend `android.media.MediaDataSource`.
  - Bridge to `AudioCache` / `CacheDataSource` for random access byte reading of candidate stream URLs.
  - Expose factory method `StreamAudioSource.open(context, uri, streamUrl)`.

- [ ] **Step 4: Run test to verify pass**
  - Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.StreamAudioSourceTest"`
  - Expected: PASS.

- [ ] **Step 5: Code review & commit (Atomic Commit 2)**
  - Review: ensure all streams safely closed in finally blocks (`safeClose`).
  - Run: `git commit -m "feat(playback): add StreamAudioSource adapter for random-access stream inspection"`

---

### Task 3: Stream Waveform Alignment Engine (`StreamAudioAligner`)

**Skills:** `mobile-developer`, `kotlin-coroutines-flows`, `kotlin-patterns`, `code-reviewer`

**Files:**
- Create: `app/src/main/java/com/music/bitchord/playback/smart/StreamAudioAligner.kt`
- Create: `app/src/test/java/com/music/bitchord/playback/smart/StreamAudioAlignerTest.kt`

- [ ] **Step 1: Write unit test for `StreamAudioAligner`**
  - Test synthetic PCM signals with known latency offset (e.g. +350ms, -200ms).
  - Verify dynamic window selection: tries opening [0.0..15.0s], falls back to [pos-5..pos+10s] if opening unavailable.
  - Verify confidence thresholding: confidence < 0.40 falls back to 0L.
  - Verify in-memory offset caching by `(mediaId, streamUrl)`.

- [ ] **Step 2: Run test to verify failure**
  - Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.StreamAudioAlignerTest"`
  - Expected: FAIL.

- [ ] **Step 3: Implement `StreamAudioAligner`**
  - Background pre-warming of 1.5MB audio bytes via `AudioCache.warmRange`.
  - Decodes 15.0s PCM chunk from both sources via `AudioDecoder.decodeRegion`.
  - Computes RMS energy envelope (`40ms` window, `10ms` hop).
  - Calculates FFT normalized cross-correlation.
  - Implements `findStreamOffsetMs(context, mediaId, currentPosMs, currentSong, targetStreamUri)` with 3.0s timeout (`withTimeoutOrNull(3000L)`).
  - Stores cached offsets in `ConcurrentHashMap<String, Long>`.

- [ ] **Step 4: Run test to verify pass**
  - Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.smart.StreamAudioAlignerTest"`
  - Expected: PASS.

- [ ] **Step 5: Code review & commit (Atomic Commit 3)**
  - Review: verify coroutine cancellation cleanup and allocation footprint.
  - Run: `git commit -m "feat(playback): implement StreamAudioAligner for cross-source waveform correlation"`

---

### Task 4: PlaybackService Upgrade Alignment Integration

**Skills:** `mobile-developer`, `kotlin-coroutines-flows`, `kotlin-patterns`, `code-reviewer`

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- Create: `app/src/test/java/com/music/bitchord/playback/StreamAlignmentUpgradeTest.kt`

- [ ] **Step 1: Write unit test for upgrade seek offset calculation**
  - Verify seek position calculation uses `(now.position + offsetMs).coerceAtLeast(0L)`.
  - Verify low confidence, negative result, or timeout falls back smoothly.

- [ ] **Step 2: Run test to verify failure**
  - Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.StreamAlignmentUpgradeTest"`
  - Expected: FAIL.

- [ ] **Step 3: Integrate alignment into `PlaybackService.kt`**
  - Trigger alignment concurrently inside / alongside `auditionUpgrade`.
  - When swap executes in `performStreamUpgrade`:
    - Check: `AppSettings.smartSourceAlignment.value && AppSettings.smartVersionAlignment.value`.
    - If enabled, query cached / awaited `StreamAudioAligner.findStreamOffsetMs(...)`.
    - Apply compensated seek `player.seekTo(player.currentMediaItemIndex, (now.position + offsetMs).coerceAtLeast(0L))`.
    - Log offset and confidence in `TrackLog.d`.

- [ ] **Step 4: Run test to verify pass**
  - Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.StreamAlignmentUpgradeTest"`
  - Expected: PASS.

- [ ] **Step 5: Code review & commit (Atomic Commit 4)**
  - Review: verify player state handling, ensure no deadlocks or lingering auditions.
  - Run: `git commit -m "feat(playback): apply waveform alignment offset during stream quality upgrades"`

---

### Task 5: End-to-End Build & Device Verification

**Skills:** `mobile-developer`, `code-reviewer`

**Files:**
- None (verification phase)

- [ ] **Step 1: Run complete unit test suite**
  - Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest`
  - Expected: All unit tests pass.

- [ ] **Step 2: Assemble Dev Debug APK**
  - Run: `rtk .\gradlew.bat assembleDevDebug`
  - Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Deploy & Test on Android Hardware**
  - Deploy `app-dev-arm64-v8a-debug.apk` to connected device `81af53cd` via ADB.
  - Verify stream quality upgrade plays in rhythm without beat stutter.
