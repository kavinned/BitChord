# Keep YouTube Cache While Prefetching Addons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When addon prefetch is enabled, use an available addon stream for playback and prefetch while preserving YouTube's cached copy.

**Architecture:** Keep YouTube bytes and source bytes in separate cache entries. `StreamChoice` pins the selected source, and `AudioCache` already has source-specific keys (`videoId#alt:<sourceId>`). Remove guards and cleanup that prevent an addon lookup or delete YouTube bytes, while retaining pinning and cache-key selection that prevent mixing streams.

**Tech Stack:** Kotlin, Android, Jetpack Media3 cache, JUnit 4.

**Spec:** User request in this thread: retain YouTube cache; play the addon copy when an enabled, ranked addon has a match.

## Global Constraints

- Android app only; leave `tv/`, `wearos/`, `web/`, `desktopApp/`, and `desktopUi/` untouched.
- Keep changes surgical; add no dependencies.
- Preserve addon rank order and fallback behavior when no addon match is available.
- A cache entry must contain bytes from one source only.

## Review Focus

- Existing YouTube cache entry plus addon match: addon uses its source-specific cache key and YouTube bytes remain available.
- Existing YouTube cache entry plus no addon match: continue on YouTube without corrupting either entry.
- Addon stream refused or lookup fails: fall through to YouTube; do not leave a stale addon pin.
- Queue prefetch and Direct Source Stream: both retain YouTube entries when selecting an addon.
- Source identity and Atmos suffix: source-specific cache key remains distinct and stable.

---

### Task 1: Pin coexistence in cache-key tests

**Files:**
- Modify: `app/src/test/java/com/music/bitchord/AudioCacheReadAheadKeyTest.kt`
- Modify: `app/src/test/java/com/music/bitchord/AudioCacheKeyStickinessTest.kt` (only if needed to cover playback selecting the prefetched source entry)

**Interfaces:**
- Consumes: `AudioCache.videoCacheKey(...)`, `AudioCache.cacheKeyFor(...)`.
- Produces: assertions that a YouTube entry and an addon entry resolve to distinct keys, and that the pinned addon selects its own key.

- [ ] **Step 1: Add failing cache-key assertions**

Test a YouTube key (`abc`) and a pinned addon key (`abc#alt:nebula`) for the same video. Assert they differ, and assert a prefetched addon pin resolves to `abc#alt:nebula` on playback.

- [ ] **Step 2: Run targeted tests and confirm assertions pass against existing key logic**

Run: `./gradlew testDevDebugUnitTest --tests "com.music.bitchord.AudioCacheReadAheadKeyTest" --tests "com.music.bitchord.AudioCacheKeyStickinessTest"`
Expected: PASS. Existing key logic should already satisfy coexistence; this locks the required contract before changing prefetch guards.

### Task 2: Allow addon prefetch alongside cached YouTube bytes

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1594-1607` (queue prefetch)
- Modify: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1412-1445` and `1669-1703` (Direct Source Stream settlement and prefetch, if both paths still discard/skip for this reason)
- Modify: `app/src/test/java/com/music/bitchord/AudioCachePrefetchTest.kt` or a focused new `AudioCachePrefetchCacheTest.kt`

**Interfaces:**
- Consumes: `SourceResolver.prefetchSubstitute(...)`, `StreamChoice.remember(...)`, `AudioCache.cacheKeyFor(...)`.
- Produces: prefetch that checks eligible ranked addons despite a cached YouTube entry, pins the successful addon before writing bytes, and retains the YouTube cache entry.

- [ ] **Step 1: Add regression coverage for cached-YouTube prefetch**

Add a focused test for the prefetch decision: a cached YouTube entry must not suppress an enabled addon lookup; a miss must preserve YouTube fallback. Cover both ordinary queue prefetch and Direct Source Stream if their decision paths are independently testable.

- [ ] **Step 2: Run the focused test and confirm it fails on the YouTube-cache guard**

Run: `./gradlew testDevDebugUnitTest --tests "com.music.bitchord.AudioCachePrefetchCacheTest"`
Expected: FAIL because a YouTube cache hit currently suppresses or removes the addon opportunity.

- [ ] **Step 3: Remove the YouTube-cache suppression and deletion from addon selection paths**

In `AudioCache.prefetchQueue`, allow `prefetchSubstitute` to run when YouTube bytes exist. Pin a returned stream before starting byte fetches. Keep the current no-match fallback. In Direct Source Stream, stop deleting YouTube's entry when an addon is selected; preserve its existing source pin and fallback behavior.

- [ ] **Step 4: Run the focused regression and cache-key tests**

Run: `./gradlew testDevDebugUnitTest --tests "com.music.bitchord.AudioCachePrefetchCacheTest" --tests "com.music.bitchord.AudioCacheReadAheadKeyTest" --tests "com.music.bitchord.AudioCacheKeyStickinessTest"`
Expected: PASS; addon and YouTube entries remain separate, and addon pin routes playback to addon bytes.

### Task 3: Verify app integration

**Files:**
- No further production files expected.

- [ ] **Step 1: Run Android unit test suite**

Run: `./gradlew testDevDebugUnitTest`
Expected: PASS, or document failures confirmed pre-existing.

- [ ] **Step 2: Review final diff**

Confirm only addon prefetch/cache behavior and its tests changed; verify no YouTube cache deletion remains on addon selection paths and no path can append addon bytes to a YouTube cache entry.
