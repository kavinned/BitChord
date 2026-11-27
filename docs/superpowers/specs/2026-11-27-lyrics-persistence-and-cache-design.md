# Lyrics Translation Persistence & Offline Lyrics Cache — Design

Tracks upstream issues **#1423** and **#1202**.

## Intent

1. **Translation toggle resets (#1423):** In `PlayerLyrics.kt`, `lyricsDisplayMode` (`Original` / `Romanized` / `Translated`) is local `remember(trackId, translationLanguage, lyrics)` state. Changing tracks, switching lyrics provider, or closing and reopening `NowPlayingScreen` resets it back to `Original`, forcing users listening to foreign-language playlists to re-tap translate on every track.
2. **No persistent lyrics cache (#1202):** `LyricsRepository` has no memory or disk cache for fetched `LyricsRepository.Result`s. Every track play re-fetches from online providers (independently in `MainViewModel`, `PlaybackService`, `LyricsTag`, and `BestParts`), and cached/streamed songs (`AudioCache`, `localUri == null`) have no lyrics during offline playback.

## Goals

- Persist the user's chosen `LyricsDisplayMode` (`Original`, `Romanized`, `Translated`) across track changes, provider switches, sheet dismissals, and app restarts.
- Automatically trigger translation or romanization when new lyrics load while a non-`Original` display mode is active, while honoring all `docs/LYRICS_TRANSLATION.md` invariants (cancel stale in-flight requests, keep original lines visible on `SameLanguage` or network failure without flipping the user's persisted preference off, don't replay the particle toggle animation on sheet reopen).
- Cache fetched `LyricsRepository.Result`s in memory + on disk (`cacheDir/lyrics_cache_v1`) using the existing zero-dependency GZIP `kotlinx.serialization` pattern from `LyricsTranslation.kt`.
- Serve cached lyrics instantaneously across all callers (`MainViewModel`, `PlaybackService`, `LyricsTag`, `BestParts`) and during offline playback of cached audio tracks.

## Non-Goals

- Adding new lyrics providers or changing provider ranking/scoring logic.
- Embedding lyrics into `AudioCache` media chunks (keep lyrics in a dedicated cache directory alongside `lyrics_translation_v3`).
- Adding an extra "Always auto-translate" toggle in Settings (persisting the in-player mode toggle itself solves the UX directly without redundant settings).
- Per-song lyrics offset or per-song provider pinning (tracked in upstream PR #1746).
- Touching non-Android modules (`tv/`, `wearos/`, `web/`, `desktopApp/`, `desktopUi/`).

## Constraints

- Android app scope only (`app/`, `shared/`, `sharedUi/`); no new dependencies (`kotlinx.serialization` is already in `shared/`).
- Preserve all `LyricLine` fidelity in cache: `timeMs`, `text`, `words` (`LyricWord` + `LyricSyllable`), `sungUntilMs`, `background`, `alignment`, and `LyricsSource`. (Do not use `LrcWriter`, which loses `LyricsSource`, `sungUntilMs`, `background`, and ms precision.)
- `LyricsRepository` is in `shared/src/jvmSharedMain`, so disk caching must work via an injectable `@Volatile var cacheDir: File? = null` (mirroring `LyricsTranslation.cacheDir`), degrading cleanly to in-memory LRU when `cacheDir == null` (e.g. unit tests).
- Must clear lyrics cache when the user clears app cache in Storage settings (if a clear-cache helper exists, wire `LyricsRepository.clearCache()` into it).

## Approaches

### Part A — Translation Mode Persistence (#1423)

- **A1. Persist `LyricsDisplayMode` in `AppSettings` + auto-run effect in `rememberLyricsTranslation` (chosen).**
  - Add `lyricsDisplayMode: MutableStateFlow<String>` (default `"ORIGINAL"`) to `AppSettings`, bridged through `PlayerSettingsSource` / `AndroidPlayerHost` (exact same pattern as `lastPlayerScreen`).
  - `rememberLyricsTranslation` reads the persisted mode as the desired mode, and runs a `LaunchedEffect(trackId, translationLanguage, lyrics, desiredMode)` that triggers `translateLyrics` / `romanizeLyrics` when `desiredMode != Original` and lyrics are non-empty.
  - If the track returns `SameLanguage` or fails to translate, `translationState` reflects `SameLanguage`/`Idle` and the UI displays original lyrics **for that track**, while the persisted `desiredMode` stays `Translated` so the next foreign-language track still auto-translates.
  - Increment the particle `transition` counter only on explicit user taps, never on initial composition / reopen / track change.
  - Tradeoff: toggling translation affects both `NowPlayingScreen` and `LyricsSidePanel` consistently (desired).
- **A2. Session-only state in `MainViewModel`.**
  - Survives sheet dismiss and track changes, but resets on process death / app restart, and still needs a `PlayerHost` bridge anyway. Persisting in `AppSettings` is the same wiring cost and better UX. Rejected.

### Part B — Persistent Lyrics Cache (#1202)

- **B1. Two-tier cache (in-memory LRU + GZIP JSON disk cache) inside `LyricsRepository` (chosen).**
  - Key disk/memory entries by `videoId` (sanitized filename hash, e.g. SHA-256 of `videoId`) storing a map of `sourceName -> CachedLyricsEntry(source, lines, cachedAtMs)` (or per-`(videoId, source)` file).
  - On `LyricsRepository.lyrics(...)`:
    1. Check cache for `videoId`: pick the best cached entry whose `source` is in the caller's enabled `sources` (honoring `order` and `prioritizeSyllableSync` when multiple sources are cached for that `videoId`, e.g. after manual provider switch).
    2. On cache hit, return immediately without network I/O (invoking `onSourceResult` so provider UI state stays consistent).
    3. On cache miss, run the existing online fetch; on non-empty success, write to memory + disk cache atomically (`.tmp` -> rename, bounded by `MAX_CACHE_BYTES = 16 * 1024 * 1024L` ~16 MiB LRU eviction).
    4. If all online providers fail (e.g. offline), fall back to *any* cached entry for `videoId` even if the user later tweaked source order, so offline playback still shows lyrics.
  - Tradeoff: requires a tiny `@Serializable` DTO layer (`CachedLyricLine`, `CachedLyricWord`, `CachedLyricSyllable`) in `shared/`, ~60 lines, zero new deps, lossless.
- **B2. Cache as `.lrc` files via `LrcWriter`.**
  - Loses `LyricsSource`, `sungUntilMs`, `background` vocals, `alignment`, and millisecond precision. Rejected.
- **B3. Room / SQLite table in `app/`.**
  - Couples cache to `app/` instead of `shared/LyricsRepository`, missing `PlaybackService` / `LyricsTag` / `BestParts` reuse or forcing an extra bridge. Rejected.

## Design

### Part A — Translation Mode Persistence
1. **`AppSettings` (`app/.../data/settings/AppSettings.kt`)**:
   - Add `private const val KEY_LYRICS_DISPLAY_MODE = "lyrics_display_mode"`.
   - Add `val lyricsDisplayMode = MutableStateFlow("ORIGINAL")`, load in `init()`, add `fun setLyricsDisplayMode(mode: String)`.
2. **`PlayerSettingsSource` (`sharedUi/.../ui/player/PlayerHost.kt`) & `AndroidPlayerHost` (`app/.../ui/player/AndroidPlayerHost.kt`)**:
   - Expose `val lyricsDisplayMode: StateFlow<String>` and `fun setLyricsDisplayMode(mode: String)`.
   - Wire in `AndroidPlayerHost` to `AppSettings.lyricsDisplayMode` and `AppSettings.setLyricsDisplayMode`.
3. **`rememberLyricsTranslation` (`sharedUi/.../ui/player/PlayerLyrics.kt:3117-3335`)**:
   - Observe `val persistedModeName by PlayerSettings.lyricsDisplayMode.collectAsState()` and map safely to `LyricsDisplayMode` (default `Original`).
   - User taps (`toggleTranslation`, `toggleRomanization`) update `PlayerSettings.setLyricsDisplayMode(...)` and increment the animation `transition` counter.
   - Add a `LaunchedEffect(trackId, translationLanguage, lyrics, desiredMode)` that:
     - Cancels any prior job via the existing `DisposableEffect` / coroutine scope.
     - If `desiredMode == Translated && lyrics.isNotEmpty() && translationState is Idle`, launches `PlayerPlatform.host.translateLyrics(...)` without incrementing `transition`.
     - If `desiredMode == Romanized && lyrics.isNotEmpty() && romanizationState is Idle`, launches `PlayerPlatform.host.romanizeLyrics(...)` without incrementing `transition`.
   - Effective displayed lines: show translated/romanized lines only when `desiredMode` matches AND corresponding state is `Ready`; otherwise show original lines (so `SameLanguage` and failures gracefully fall back to originals without mutating `PlayerSettings`).

### Part B — Persistent Lyrics Cache
1. **`LyricsCache` (`shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsCache.kt`)**:
   - New focused internal helper following `LyricsTranslation.kt` (L73-121, L568-624):
     - `@Volatile var cacheDir: File? = null`
     - `MAX_MEMORY_ENTRIES = 64`, `MAX_CACHE_BYTES = 16L * 1024 * 1024` (16 MiB), directory `"lyrics_cache_v1"`.
     - Serializable DTOs (`CachedTrackLyrics`, `CachedSourceLyrics`, `CachedLyricLine`, `CachedLyricWord`, `CachedLyricSyllable`) converting cleanly to/from `LyricsRepository.Result` and `LyricLine` (handling recursive `background: LyricLine?`, `alignment.name`, `LyricsSource.name`).
     - `suspend fun get(videoId: String, allowedSources: Set<LyricsSource>, order: List<LyricsSource>, prioritizeSyllableSync: Boolean): LyricsRepository.Result?`
     - `suspend fun getAny(videoId: String): LyricsRepository.Result?` (offline fallback)
     - `suspend fun put(videoId: String, result: LyricsRepository.Result)` (merges into track's cached sources map, writes gzipped JSON via `.tmp` + atomic rename, trims oldest files when exceeding `MAX_CACHE_BYTES`).
     - `suspend fun clear()` (clears memory + disk directory).
2. **`LyricsRepository` (`shared/.../data/lyrics/LyricsRepository.kt`)**:
   - Expose `var cacheDir: File?` delegating to `LyricsCache.cacheDir` and `suspend fun clearCache()`.
   - In `lyrics(...)`: check `LyricsCache.get(...)` first (skipping cache only when caller passes a `forceRefresh: Boolean = false` parameter for explicit manual refresh). On online hit, call `LyricsCache.put(videoId, result)`. On online miss (all sources failed/null), fall back to `LyricsCache.getAny(videoId)`.
   - In `fetchProvider(...)` (if separate single-source fetch exists): also check/populate `LyricsCache` for that single source.
3. **`BitChordApplication.kt` (`app/.../BitChordApplication.kt:110`)**:
   - Set `LyricsRepository.cacheDir = cacheDir` right beside `LyricsTranslation.cacheDir = cacheDir`.

## Verification

- Unit tests:
  - `LyricsCacheTest` (`shared/src/jvmTest/.../data/lyrics/LyricsCacheTest.kt`): round-trip serialization of synced + syllable + background + alignment + plain lyrics; memory + disk hit; source-order & `prioritizeSyllableSync` selection; `getAny` offline fallback; LRU byte-budget eviction; corrupt file resilience.
  - `LyricsDisplayModeTest` (`sharedUi/src/jvmTest/...`): `LyricsDisplayMode` parsing/fallback, effective display fallback on `SameLanguage`/error without mutating persisted mode, animation counter stays 0 on auto-load.
- Build & run: `.\gradlew.bat :app:testDevDebugUnitTest -PbitchordAbi=arm64-v8a` and `:app:assembleDevDebug -PbitchordAbi=arm64-v8a`.
- Manual: enable lyrics translation on a non-English song -> skip to next non-English song (stays translated) -> skip to English song (shows originals) -> skip back to non-English song (auto-translates) -> close & reopen Now Playing (stays translated without replaying particle animation); enable airplane mode and play a previously played cached song -> cached lyrics load immediately.
