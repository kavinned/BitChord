# Seamless Audio Source Switching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate audible pause/stutter during mid-playback audio stream upgrade (Solution 1) and pre-resolve high-quality addon sources for upcoming queue tracks before playback starts (Solution 2).

**Architecture:**
- **Solution 1 (Dual-Player Handover):** Integrate `spare` ExoPlayer into `swapIn()` in `PlaybackService.kt`. Instead of calling `replaceMediaItem` which flushes the decoder and `AudioTrack`, pre-load the auditioned and cached stream into `spare`, align playback position, start playback silently, perform a short equal-power crossfade (~300ms) with loudness trim compensation, and adopt `spare` as the new active player via `adoptPlayerForVersionSwap()`.
- **Solution 2 (Queue Lookahead Pre-Resolution):** Enable `worthPrefetching = true` on `SourceKind.ADDON` and wire upcoming queue tracks in `AudioCache.prefetchQueue` / `SourceResolver.prefetchSubstitute` to resolve and pin Hi-Fi addon streams in `StreamChoice` ahead of time so songs start directly on high-quality stream from 0ms.

**Tech Stack:** Kotlin, Android Media3 / ExoPlayer, Kotlin Coroutines & Flows, JUnit.

**Spec / Requirements:**
- Android mobile app scope (`app/`).
- Zero audio pause or drop during mid-stream source upgrade.
- Fallback safely to immediate `replaceMediaItem` if `spare` is unavailable or Automix is transitioning.
- No regression in Automix, party sync, or manual version swapping.
- Ensure all tests pass.

## Global Constraints
- Android SDK compileSdk = 36, targetSdk = 36, minSdk = 26.
- Strict GPLv3 compliance; no external dependencies added.
- All commands prefixed with `rtk`.
- Keep changes surgical and modular.

## Review Focus
1. `spare` player collision: Ensure `swapIn()` does not steal `spare` while Automix is mid-transition.
2. Buffer underrun during micro-crossfade: Verify `standbyPlayer` is at `STATE_READY` and producing samples before gain ramp begins.
3. AudioTrack / Loudness trim reset: Ensure loudness trim resets to 1.0f in `finally` block even if crossfade is cancelled.
4. Position drift: Maintain sub-50ms position alignment between outgoing and incoming stream during handoff.
5. Prefetch bandwidth / network throttle: Do not saturate network with prefetch while active track is buffering.

---

### Task 1: Enable Addon Source Prefetching (Solution 2)

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/data/sources/SourceKind.kt:70-85`
- Test: `app/src/test/java/com/music/bitchord/SourcesTest.kt`

**Interfaces:**
- Consumes: `SourceKind.ADDON`
- Produces: `SourceKind.ADDON.worthPrefetching == true`

- [ ] **Step 1: Write failing test in `SourcesTest.kt`**
Verify `SourceKind.ADDON.worthPrefetching` is expected to be `true` for prefetching upcoming queue tracks.

- [ ] **Step 2: Run test to verify failure**
Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.SourcesTest"`

- [ ] **Step 3: Update `SourceKind.ADDON` in `SourceKind.kt`**
Set `worthPrefetching = true` on `SourceKind.ADDON`.

- [ ] **Step 4: Run test to verify pass**
Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.SourcesTest"`

- [ ] **Step 5: Commit**
`git commit -m "feat(sources): enable queue prefetching for addon audio sources"`

---

### Task 2: Implement Standby Crossfade Helper for In-Flight Stream Swap (Solution 1)

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4580-4665`
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:2380-2415`

**Interfaces:**
- Consumes: `spare: ExoPlayer?`, `loudnessA: LoudnessProcessor`, `loudnessB: LoudnessProcessor`, `adoptPlayerForVersionSwap(outgoing, incoming)`
- Produces: `seamlessSwapToStream(now: SwapPoint, upgradedUri: String, stream: SourceStream)`

- [ ] **Step 1: Create `seamlessSwapToStream` implementation in `PlaybackService.kt`**
  - Check if `spare != null` and `crossfade?.isTransitioning() != true`.
  - Prepare standby player with new `MediaItem` (with `upgradedUri`), seek to `now.position`, and volume = 0f.
  - Wait for `standby.playbackState == Player.STATE_READY`.
  - Start `standby.play()` and sync live position.
  - Perform 300ms equal-power crossfade (sine/cosine curves) with `setBlendTrim`.
  - Call `adoptPlayerForVersionSwap(outgoing = activePlayer, incoming = standby)`.
  - If `spare == null` or fails to prepare, fallback to `player.replaceMediaItem`.

- [ ] **Step 2: Connect `swapIn()` to `seamlessSwapToStream`**
In `swapIn()`, replace the synchronous `player.replaceMediaItem(...)` invocation with `seamlessSwapToStream(...)`.

- [ ] **Step 3: Verification & Unit Tests**
Verify compilation and run existing playback tests:
Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest`

- [ ] **Step 4: Commit**
`git commit -m "feat(playback): implement seamless dual-player crossfade for source upgrades"`

---

### Task 3: Lookahead Queue Stream Resolution Validation

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:945-985`
- Test: `app/src/test/java/com/music/bitchord/AudioCachePrefetchTest.kt`

**Interfaces:**
- Consumes: `AudioCache.prefetchQueue(upcoming: List<Upcoming>)`, `SourceResolver.prefetchSubstitute(target)`
- Produces: Verified pinning of upcoming track streams in `StreamChoice`

- [ ] **Step 1: Write unit test validating prefetch pinning behavior**
Ensure upcoming tracks trigger `SourceResolver.prefetchSubstitute` and remember stream in `StreamChoice`.

- [ ] **Step 2: Run test to verify**
Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.AudioCachePrefetchTest"`

- [ ] **Step 3: Commit**
`git commit -m "test(playback): verify lookahead queue stream pre-resolution"`
