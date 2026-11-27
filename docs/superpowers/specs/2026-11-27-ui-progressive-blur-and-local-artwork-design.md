# Progressive Blur Cold Launch & Local Artwork Flicker — Design

Tracks upstream issues **#1997** and **#2002**.

## Intent

1. **Progressive blur inactive on Home cold launch (#1997):** On app launch on the Home tab (`scrollOffset == 0`), `MainActivity.kt:3675-3706` wraps `TopFadeBlur` in an outer `.graphicsLayer { alpha = topFadeAlpha }` whose initial alpha is `0f`. Compose skips drawing the child subtree (`Offscreen` compositing layer + `HazeEffectNode.draw()`). Scrolling Home updates `topFadeAlpha` only in the outer layer phase, never invalidating `HazeEffectNode`, so progressive blur stays dead until the user switches tabs (which recomposes `TopFadeBlur` at `alpha = 1f`).
2. **Local track artwork/placeholder flicker in v1.10 (#2002):** Three interlocking issues in `PlayerState.kt` (`rememberPlayerArtwork`) and `NowPlayingScreen.kt` / `LandscapePlayer.kt` cause repeated cover flashing when skipping between local files with and without embedded art:
   - `LocalMediaRepository.trackArtworkUrl` assigns a non-null `content://.../albumart` URI even when a file has no embedded art, so `rememberPlayerArtwork` runs the 3x 1.5s network retry loop on a local file, re-flashing the previous track's `holdOver` cover 3 times.
   - `PlayerArtwork.lastCover` (`shown ?: holdOver`) never clears when a track settles on `Error` (placeholder), leaking an older track's cover across subsequent placeholder tracks.
   - `loaded` starts `false` during `Loading` even while `holdOver` is drawn, briefly dropping sleeve shadow/clip and exposing the `MusicNote` placeholder underneath.

## Goals

- Progressive blur renders immediately when scrolling Home on cold launch, without needing a tab switch.
- Switching between local audio files (art -> no art, no art -> no art, no art -> art, art -> art) produces zero repeated flashes, zero stale cover leaks from earlier tracks, and zero transient `MusicNote` / shadow flicker while a valid `holdOver` cover is displayed.
- Preserve v1.10's smooth cover hold-over behavior for remote/streaming tracks (including network retry on transient remote failures).

## Non-Goals

- Changing `TopFadeBlur`'s visual radius, fade curve, or 0.33x input scale.
- Scanning/decoding embedded artwork during `LocalMediaRepository` indexing (expensive I/O on large local libraries; handling local `content://` URI failures cleanly in `PlayerArtwork` avoids startup scan cost).
- Rewriting AutoMix artwork transitions (tracked in upstream PR #1947).
- Touching non-Android modules (`tv/`, `wearos/`, `web/`, `desktopApp/`, `desktopUi/`).

## Constraints

- Android app scope only (`app/`, `sharedUi/`); no new dependencies (Haze stays at `1.3.1`).
- Keep `TopFadeBlur` scroll-driven alpha read out of full `MainActivity` composition scope (no per-pixel recomposition of the whole screen on scroll).
- Existing `PlayerArtworkHoldOverTest` and `AutoMixCoverTest` cases must continue to pass.

## Approaches

### Part A — Progressive Blur Cold Launch (#1997)

- **A1. Pass `alpha: () -> Float` into `TopFadeBlur` and drive `HazeEffectScope.alpha` + draw-phase fade (chosen).**
  - Remove the outer `.graphicsLayer { alpha = topFadeAlpha }` wrapper in `MainActivity.kt:3699-3705` (which caused Compose to skip the entire child `Offscreen` + `HazeEffectNode` subtree at `alpha == 0f`).
  - Pass `alpha: () -> Float = { 1f }` into `TopFadeBlur` (`TopFadeBlur.kt:80-111`), and apply it inside `.hazeEffect(state = hazeState) { this.alpha = alpha() ... }` (Haze 1.3.1's native `HazeEffectScope.alpha` property triggers `HazeEffectNode` dirty-tracking/invalidation when it changes from `0f` to `> 0f`, while still skipping blur work cleanly when `alpha == 0f`).
  - Tradeoff: `TopFadeBlur`'s `hazeEffect` block reads `topFadeAlpha` inside Haze's draw/effect phase (zero extra `MainActivity` recompositions) and properly wakes `HazeEffectNode` on first scroll.
- **A2. Clamp `topFadeAlpha` floor to `0.001f` on Home.**
  - Hacky; wastes a 1/3-res GPU blur pass on every idle Home frame at scroll 0. Rejected.
- **A3. Recompose `TopFadeBlur` conditionally (`if (topFadeAlpha > 0f)`).**
  - Mounts/unmounts `HazeEffectNode` on every return to scroll 0, risking frame hitches when crossing the threshold. Rejected.

### Part B — Local Track Artwork Flicker (#2002)

- **B1. Fix `PlayerArtwork` state machine for local URIs, settled errors, and hold-over visibility (chosen).**
  1. **No retry on local URIs:** Add `internal fun shouldRetryArtwork(artUrl: String?): Boolean` returning `false` when `artUrl == null` or `artUrl.startsWith("content://")` or `artUrl.startsWith("file://")` (a missing embedded cover on disk will never magically appear 1.5s later).
  2. **Clear `lastCover` on settled failure:** In `PlayerArtwork.lastCover` (`PlayerState.kt:171`), return `null` when `failed && !shouldRetry` (or when `failed` and retries are exhausted / disabled) instead of falling back to `holdOver`. That way, once Track B settles on the `MusicNote` placeholder, Track C receives `holdOver = null` instead of Track A's stale cover.
  3. **Expose `hasVisibleCover` (`loaded || (!failed && holdOver != null)`):** Use `art.hasVisibleCover` for `coverLoaded()` (sleeve shadow, clip, and hiding the `MusicNote` placeholder underneath) in `NowPlayingScreen.kt` and `LandscapePlayer.kt`. While Track B is loading with Track A held over, the sleeve keeps its shadow and doesn't flash `MusicNote`; the instant Track B settles on `Error`, `hasVisibleCover` flips cleanly to `false` once, showing `MusicNote` without flashing back.
  - Tradeoff: zero I/O added to `LocalMediaRepository`; fixes all 3 flicker sources in one cohesive state machine with pure unit tests in `sharedUi/src/jvmTest`.
- **B2. Probe embedded artwork during `LocalMediaRepository` scan to emit `thumbnailUrl = null`.**
  - Slows local library scan (opening `MediaMetadataRetriever` / `ContentResolver` for thousands of files) and still wouldn't fix Bug 2 (`lastCover` leaking after any failed remote/local load) or Bug 3 (`loaded = false` during `holdOver`). Rejected.

## Design

### Part A — Progressive Blur Cold Launch
1. **`TopFadeBlur` (`app/src/main/java/com/music/bitchord/ui/components/TopFadeBlur.kt:80-111`)**:
   - Add parameter `alpha: () -> Float = { 1f }`.
   - Inside `.hazeEffect(state = hazeState) { ... }`, set `this.alpha = alpha().coerceIn(0f, 1f)`.
   - Inside `.drawWithContent { ... }`, skip drawing when `alpha() <= 0f` or let `hazeEffect`'s `alpha` handle zero-cost no-op while keeping the `Offscreen` layer active once `alpha > 0f`.
2. **`MainActivity.kt` (`app/src/main/java/com/music/bitchord/MainActivity.kt:3690-3706`)**:
   - Remove `.graphicsLayer { alpha = topFadeAlpha }` from the `TopFadeBlur` modifier and pass `alpha = { topFadeAlpha }`.

### Part B — Local Track Artwork Flicker
1. **`PlayerState.kt` (`sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerState.kt:115-240`)**:
   - Add `internal fun isRetryableArtworkUrl(url: String?): Boolean = !url.isNullOrBlank() && !url.startsWith("content://") && !url.startsWith("file://")`.
   - Update `PlayerArtwork`:
     - Store `val retryable: Boolean = isRetryableArtworkUrl(url)`.
     - Add `val settledEmpty: Boolean get() = url == null || (failed && (!retryable || attempt >= ART_RETRIES))`.
     - Update `val lastCover: MemoryCache.Key? get() = if (settledEmpty) null else shown ?: holdOver`.
     - Add `val hasVisibleCover: Boolean get() = loaded || (!failed && holdOver != null)`.
   - In `rememberPlayerArtwork`:
     - Guard retry `LaunchedEffect` with `if (!art.retryable || !art.failed || art.attempt >= ART_RETRIES) return@LaunchedEffect`.
     - On `art.retry()`, keep `holdOver` only when retrying a remote URL (already guaranteed by `art.retryable`).
2. **`NowPlayingScreen.kt` (`L1605-1615, L3762-3865`) & `LandscapePlayer.kt` (`L279-358`)**:
   - Update `coverLoaded()` / `artLoaded` to read `art.hasVisibleCover` (so sleeve shadow/clip stays stable and `MusicNote` stays hidden while a `holdOver` cover is displayed, and flips cleanly once on settled error).

## Verification

- Unit tests:
  - `PlayerArtworkHoldOverTest` (`sharedUi/src/jvmTest/.../PlayerArtworkHoldOverTest.kt`):
    - Local `content://` and `file://` URLs report `isRetryableArtworkUrl == false` and `settledEmpty == true` immediately on first `Error`.
    - `lastCover` becomes `null` after a track settles on `Error`, so the next track does not inherit an older track's `holdOver`.
    - `hasVisibleCover` stays `true` during `Loading` when `holdOver != null`, and flips to `false` on `Error`.
    - Remote `https://` URLs still retry up to `ART_RETRIES` and preserve `holdOver` during transient retries.
  - `TopFadeBlurAlphaTest` (`app/src/test/...`): verify alpha clamping/predicate helper used by `TopFadeBlur`.
- Build & run: `.\gradlew.bat :app:testDevDebugUnitTest -PbitchordAbi=arm64-v8a` and `:app:assembleDevDebug -PbitchordAbi=arm64-v8a`.
- Manual on device:
  - Cold-launch app on Home with Progressive Blur enabled -> scroll Home immediately without switching tabs -> blur fades in smoothly under top bar.
  - Skip across local tracks: with art -> without art -> without art -> with art -> zero repeated flashing, zero old cover flash on placeholder tracks, clean shadow/icon transition.
