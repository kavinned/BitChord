# Upstream Issues (Four Tracks) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 8 upstream Android issues across four independent tracks: YT Music locale metadata & long playlist pagination (#1388, #1283, #1651), lyrics translation persistence & offline disk cache (#1423, #1202), progressive blur cold-launch & v1.10 local artwork flicker (#1997, #2002), and currently-playing track playlist removal/locate UX (#482).

**Architecture:** Each track is self-contained and lands in atomic commits scoped to `app/`, `shared/`, and `sharedUi/`. Shared pure-Kotlin logic (InnerTube parsing, `LyricsCache`, `PlayerArtwork` state machine, playlist selection/index helpers) is unit-tested first in JVM tests before wiring into Android UI and `MainActivity`.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Haze 1.3.1, Coil, `kotlinx.serialization`, JUnit 4, Gradle (`:app:testDevDebugUnitTest`, `:app:assembleDevDebug`).

**Specs:**
- Track 1: `docs/superpowers/specs/2026-11-27-ytmusic-locale-and-playlist-pagination-design.md`
- Track 2: `docs/superpowers/specs/2026-11-27-lyrics-persistence-and-cache-design.md`
- Track 3: `docs/superpowers/specs/2026-11-27-ui-progressive-blur-and-local-artwork-design.md`
- Track 4: `docs/superpowers/specs/2026-11-27-playlist-current-track-ux-design.md`

## Global Constraints

- Android app scope only (`app/`, `shared/`, `sharedUi/`); leave `tv/`, `wearos/`, `web/`, `desktopApp/`, and `desktopUi/` untouched.
- Zero new external dependencies (reuse `kotlinx.serialization` in `shared/`, Haze `1.3.1`, existing `QueueActionNotice`, `SongActionsMenu`, `PlaylistPickerSheet`).
- Always pass `-PbitchordAbi=arm64-v8a` to dev Gradle build/test/install commands, and run only one Gradle build at a time.
- Every new string added to `app/src/main/res/values/strings.xml` must also be added to every `values-*/strings.xml` locale file (English fallback until translated) so `LocaleStringsTest` passes.
- Keep commits strictly atomic per task (never bundle multiple tracks into one commit).

## Review Focus

- **Artist names starting with numbers (#1388/#1283):** Broadened tally regex must not drop real artist names like `"21 Savage"` or `"50 Cent"` — pinned by a unit test in Task 1.
- **Looped YouTube continuation tokens (#1651):** Tolerating empty pages in `fillIn` must still terminate immediately when `fetched.continuation == token` (self-loop) or after `FILL_IN_MAX_EMPTY_PAGES = 2` consecutive empty pages — pinned by a unit test in Task 2.
- **Same-language track while translation is persisted (#1423):** A track returning `SameLanguage` must display original lyrics without resetting `AppSettings.lyricsDisplayMode` back to `ORIGINAL`, and reopening the sheet must not replay the particle animation — pinned by unit tests in Task 3.
- **Corrupt / truncated lyrics cache file (#1202):** A corrupted `.gz` file in `lyrics_cache_v1` must return `null` cleanly (falling back to network) without throwing — pinned by a unit test in Task 4.
- **Autoplay continuation masquerading as playlist track (#482):** Tracks with `fromAutoplay == true` inherit `playbackSourceId` from the seed track and must never expose "Remove from playlist" in Now Playing — pinned by a unit test in Task 7.

---

## Track 1: YT Music Locale Metadata & Long Playlist Pagination

### Task 1: Structural artist extraction & multilingual subtitle filtering (#1388, #1283)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/music/bitchord/data/model/Models.kt` (`ShelfItem`)
- Modify: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt:1102-1127, 1667-1785`
- Modify: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:220-290`
- Modify: `app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt:2467-2515`
- Modify: `app/src/main/java/com/music/bitchord/MainActivity.kt:2150-2160, 3344-3355`
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:11044-11085`
- Test: `app/src/test/java/com/music/bitchord/SearchPagingTest.kt`

**Interfaces:**
- Produces:
  - `ShelfItem.artist: String? = null`
  - `InnertubeParser.TYPE_WORDS: Set<String>` (changed from `private` to `internal`/public for `DetailScreen` reuse)
  - `InnertubeParser.RELEASE_WORDS: Set<String>`
  - `InnertubeParser.looksLikeTally(segment: String): Boolean`

- [ ] **Step 1: Write failing tests for Spanish/Russian metadata and numeric artist names**

Add to `app/src/test/java/com/music/bitchord/SearchPagingTest.kt`:

```kotlin
@Test
fun `artistFromSubtitle skips Spanish and Russian type labels and tallies`() {
    assertEquals("Ken Carson", InnertubeParser.artistFromSubtitle("Álbum • Ken Carson • 2023"))
    assertEquals("Ken Carson", InnertubeParser.artistFromSubtitle("Canción • Ken Carson • 12 M de reproducciones"))
    assertEquals("Miyagi", InnertubeParser.artistFromSubtitle("Композиция • Miyagi • 1,2 млн прослушиваний"))
    assertEquals("21 Savage", InnertubeParser.artistFromSubtitle("Song • 21 Savage • 50M plays"))
    assertEquals("50 Cent", InnertubeParser.artistFromSubtitle("Canción • 50 Cent"))
}

@Test
fun `DetailPage headerLines strips localized album kind words`() {
    val page = DetailPage(
        browseId = "MPREb_test",
        type = BrowseType.ALBUM,
        title = "A Great Chaos",
        subtitle = "Ken Carson • Álbum • 2023",
    )
    val (credit, _) = page.headerLines(songCount = 18)
    assertEquals("Ken Carson", credit)
}
```

- [ ] **Step 2: Run focused test and verify it fails**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.SearchPagingTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: FAIL (`"Álbum"`, `"Композиция"`, and `"Ken Carson, Álbum"` returned instead of artist).

- [ ] **Step 3: Implement structural artist extraction and multilingual filtering**

1. In `Models.kt`, add `val artist: String? = null` to `ShelfItem`.
2. In `InnertubeParser.kt`:
   - In `parseTwoRowItem` (`L1667-1713`), extract runs from `renderer.o("subtitle")` whose `navigationEndpoint.browseEndpoint.browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType == "MUSIC_PAGE_TYPE_ARTIST"` and join non-blank texts with `", "` into `ShelfItem.artist`.
   - Expand `RELEASE_WORDS` and `TYPE_WORDS` (`L1765-1775`) across all supported app locales (`en`, `es`, `ru`, `de`, `fr`, `it`, `pt`, `pl`, `tr`, `id`, `vi`, `th`, `hi`, `ja`, `ko`, `zh`, `ar`, `he`, `uk`) and make `TYPE_WORDS` accessible to `DetailScreen`.
   - Update `artistFromSubtitle` (`L1724-1729`) to split on `Regex("\\s*[•·]\\s*")`, lowercase with `Locale.ROOT`, and filter `!looksLikeTally(it)` (matching localized count units like `reproducciones`, `vistas`, `прослушиваний`, `просмотров`, `wiedergaben`, `vues`, `visualizações`, `回視聴`, `조회` in addition to English `TALLY`, while never rejecting plain numeric artist names like `"21 Savage"` or `"50 Cent"`).
   - Update `pageCredit()` (`L1102-1127`) to accept localized `RELEASE_WORDS` OR a `straplineTextOne` artist endpoint.
3. In `DetailScreen.kt` (`L2467-2512`), replace private `KIND_WORDS` with `InnertubeParser.TYPE_WORDS` and use `Locale.ROOT`.
4. Update `ShelfItem` -> `Song` call sites (`YtMusicRepository.kt:225,247,263,281`, `MainActivity.kt:2154,3348`, `PlaybackService.kt:11048,11078`) to prefer `item.artist?.takeIf { it.isNotBlank() } ?: InnertubeParser.artistFromSubtitle(item.subtitle)`.

- [ ] **Step 4: Run focused test and verify it passes**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.SearchPagingTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/music/bitchord/data/model/Models.kt shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt app/src/main/java/com/music/bitchord/MainActivity.kt app/src/main/java/com/music/bitchord/playback/PlaybackService.kt app/src/test/java/com/music/bitchord/SearchPagingTest.kt
git commit -m "fix(innertube): preserve artist metadata across non-English locales"
```

---

### Task 2: Harden long playlist pagination & scroll-to-resume (#1651)

**Files:**
- Modify: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt:600-635, 675-690`
- Modify: `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:3361-3425`
- Modify: `app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt` (scroll-near-bottom trigger)
- Modify: `app/src/main/java/com/music/bitchord/MainActivity.kt` (pass `onResumeFillIn` to `DetailScreen`)
- Test: `shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/PlaylistContinuationTest.kt`
- Test: `app/src/test/java/com/music/bitchord/FillInRetryTest.kt`

**Interfaces:**
- Produces:
  - `MainViewModel.resumeFillIn(browseId: String)`
  - `internal fun shouldAdvanceEmptyPage(currentToken: String, nextToken: String?, emptyPages: Int, maxEmptyPages: Int): Boolean`

- [ ] **Step 1: Write failing tests for multi-action continuations, duplicate entry preservation, and empty-page tolerance**

Add to `PlaylistContinuationTest.kt`:
- Test `parsePlaylistShelf` aggregates items across multiple `appendContinuationItemsAction` entries in `onResponseReceivedActions`.
- Test `parsePlaylistShelf` keeps two entries with identical `videoId` and distinct `setVideoId`.

Add to `FillInRetryTest.kt`:
- Test `shouldAdvanceEmptyPage` returns `true` for 1st and 2nd empty page when `nextToken != null && nextToken != currentToken`, `false` when `nextToken == currentToken` (self-loop), and `false` on the 3rd consecutive empty page (`emptyPages > 2`).

- [ ] **Step 2: Run focused tests and verify they fail**

Run: `.\gradlew.bat :shared:jvmTest --tests com.music.bitchord.data.innertube.PlaylistContinuationTest :app:testDevDebugUnitTest --tests com.music.bitchord.FillInRetryTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: FAIL.

- [ ] **Step 3: Implement parser, `fillIn`, and scroll-to-resume fixes**

1. In `InnertubeParser.parsePlaylistShelf` (`L600-635`):
   - Collect items across all `appendContinuationItemsAction` entries in `onResponseReceivedActions` (not `firstNotNullOfOrNull`).
   - Deduplicate by `it.setVideoId ?: it.videoId` instead of `it.videoId`.
   - Select the last non-blank `continuationItemRenderer` token in the playlist scope.
2. In `MainViewModel.kt` (`L3361-3425`):
   - Define `FILL_IN_PAGE_DELAY_MS = 250L`, `FILL_IN_MAX_EMPTY_PAGES = 2`, and `shouldAdvanceEmptyPage(...)`.
   - In `fillIn`, pause `delay(FILL_IN_PAGE_DELAY_MS)` between pages (after the first page), track consecutive `emptyPages`, continue when `shouldAdvanceEmptyPage(token, fetched.continuation, ++emptyPages, FILL_IN_MAX_EMPTY_PAGES)` is `true`, and reset `emptyPages = 0` on progress.
   - Add `fun resumeFillIn(browseId: String)` that restarts `fillIn` from `_detailStack.value.lastOrNull { it.browseId == browseId }?.continuation` when `fillInJobs[browseId]?.isActive != true`.
3. In `DetailScreen.kt` + `MainActivity.kt`:
   - Add `onResumeFillIn: () -> Unit = {}` parameter to `DetailScreen`; when `page.continuation != null` and `listState` scrolls within 15 items of the end of `matches`, call `onResumeFillIn()`.

- [ ] **Step 4: Run focused tests and verify they pass**

Run: `.\gradlew.bat :shared:jvmTest --tests com.music.bitchord.data.innertube.PlaylistContinuationTest :app:testDevDebugUnitTest --tests com.music.bitchord.FillInRetryTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt app/src/main/java/com/music/bitchord/ui/MainViewModel.kt app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt app/src/main/java/com/music/bitchord/MainActivity.kt shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/PlaylistContinuationTest.kt app/src/test/java/com/music/bitchord/FillInRetryTest.kt
git commit -m "fix(playlist): harden long playlist continuation loading and resume"
```

---

## Track 2: Lyrics Translation Persistence & Offline Cache

### Task 3: Persist `LyricsDisplayMode` across tracks and navigation (#1423)

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt`
- Modify: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt:264-331`
- Modify: `app/src/main/java/com/music/bitchord/ui/player/AndroidPlayerHost.kt:78-126`
- Modify: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt:515-530, 3117-3335`
- Create Test: `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/LyricsDisplayModeTest.kt`

**Interfaces:**
- Produces:
  - `AppSettings.lyricsDisplayMode: StateFlow<String>` and `AppSettings.setLyricsDisplayMode(mode: String)`
  - `PlayerSettingsSource.lyricsDisplayMode: StateFlow<String>` and `PlayerSettingsSource.setLyricsDisplayMode(mode: String)`
  - `internal fun parseLyricsDisplayMode(raw: String?): LyricsDisplayMode`
  - `internal fun effectiveDisplayMode(desired: LyricsDisplayMode, translation: LyricsTranslationUiState, romanization: LyricsTranslationUiState): LyricsDisplayMode`

- [ ] **Step 1: Write failing unit tests for mode parsing and fallback**

Create `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/LyricsDisplayModeTest.kt`:
- `parseLyricsDisplayMode("TRANSLATED") == LyricsDisplayMode.Translated`
- `parseLyricsDisplayMode("ROMANIZED") == LyricsDisplayMode.Romanized`
- `parseLyricsDisplayMode(null / "BOGUS") == LyricsDisplayMode.Original`
- `effectiveDisplayMode(Translated, SameLanguage, Idle) == Original` (displays originals without mutating desired mode)
- `effectiveDisplayMode(Translated, Ready(lines), Idle) == Translated`
- `effectiveDisplayMode(Romanized, Idle, Idle) == Original`

- [ ] **Step 2: Run focused test and verify it fails**

Run: `.\gradlew.bat :sharedUi:jvmTest --tests com.music.bitchord.ui.player.LyricsDisplayModeTest --no-configuration-cache`
Expected: compile failure (`parseLyricsDisplayMode` / `effectiveDisplayMode` unresolved).

- [ ] **Step 3: Implement persisted mode and auto-translate effect**

1. In `AppSettings.kt`, add `KEY_LYRICS_DISPLAY_MODE = "lyrics_display_mode"`, `val lyricsDisplayMode = MutableStateFlow("ORIGINAL")`, load in `init()`, add `setLyricsDisplayMode(mode: String)`.
2. In `PlayerHost.kt` (`PlayerSettingsSource`) and `AndroidPlayerHost.kt`, wire `lyricsDisplayMode` + `setLyricsDisplayMode`.
3. In `PlayerLyrics.kt`:
   - Add `parseLyricsDisplayMode` and `effectiveDisplayMode`.
   - In `rememberLyricsTranslation` (`L3117-3335`), collect `PlayerSettings.lyricsDisplayMode` as `desiredMode`.
   - User taps (`toggleTranslation`, `toggleRomanization`) persist the new mode via `PlayerSettings.setLyricsDisplayMode(...)` and increment the particle `transition` counter.
   - Add `LaunchedEffect(trackId, translationLanguage, lyrics, desiredMode)` that auto-fetches translation/romanization when `desiredMode != Original` and `lyrics.isNotEmpty()` without incrementing `transition`.
   - Render translated/romanized lines based on `effectiveDisplayMode(desiredMode, translationState, romanizationState)` so `SameLanguage` / errors gracefully show originals without overwriting the persisted preference.

- [ ] **Step 4: Run focused test and verify it passes**

Run: `.\gradlew.bat :sharedUi:jvmTest --tests com.music.bitchord.ui.player.LyricsDisplayModeTest --no-configuration-cache`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt app/src/main/java/com/music/bitchord/ui/player/AndroidPlayerHost.kt sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/LyricsDisplayModeTest.kt
git commit -m "fix(lyrics): persist lyrics translation display mode across tracks and views"
```

---

### Task 4: Two-tier persistent `LyricsCache` wired into `LyricsRepository` (#1202)

**Files:**
- Create: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsCache.kt`
- Modify: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt:67-175`
- Modify: `app/src/main/java/com/music/bitchord/BitChordApplication.kt:108-115`
- Create Test: `shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsCacheTest.kt`

**Interfaces:**
- Produces:
  - `LyricsRepository.cacheDir: File?`
  - `LyricsRepository.clearCache()`
  - `LyricsCache.get(videoId, allowedSources, order, prioritizeSyllableSync): LyricsRepository.Result?`
  - `LyricsCache.getAny(videoId): LyricsRepository.Result?`
  - `LyricsCache.put(videoId, result)`

- [ ] **Step 1: Write failing unit tests for `LyricsCache`**

Create `shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsCacheTest.kt`:
- Round-trip serialization preserves `LyricsSource`, `timeMs`, `text`, `words` (`LyricWord` + `LyricSyllable`), `sungUntilMs`, nested `background: LyricLine?`, and `alignment`.
- Disk reload works after clearing in-memory LRU (simulating cold restart).
- `get` respects `allowedSources`, `order`, and `prioritizeSyllableSync` when multiple sources are cached for the same `videoId`.
- `getAny` returns a cached entry even if its source is not in `allowedSources` (offline fallback).
- Corrupt `.gz` file on disk returns `null` without throwing.
- LRU eviction trims oldest files when exceeding byte budget.

- [ ] **Step 2: Run focused test and verify it fails**

Run: `.\gradlew.bat :shared:jvmTest --tests com.music.bitchord.data.lyrics.LyricsCacheTest --no-configuration-cache`
Expected: compile failure (`LyricsCache` unresolved).

- [ ] **Step 3: Implement `LyricsCache` and wire into `LyricsRepository` + `BitChordApplication`**

1. Create `LyricsCache.kt` mirroring `LyricsTranslation.kt` (L73-121, L568-624):
   - `@Volatile var cacheDir: File? = null`, `MAX_MEMORY_ENTRIES = 64`, `MAX_CACHE_BYTES = 16L * 1024 * 1024`, subdir `"lyrics_cache_v1"`.
   - `Mutex`-guarded memory LRU + GZIP `kotlinx.serialization` JSON files named by SHA-256 of `videoId`, written via `.tmp` + atomic rename, with oldest-first byte-budget trim.
   - Ignore/delete corrupt files on decode failure.
2. In `LyricsRepository.kt`:
   - Expose `var cacheDir: File?` and `suspend fun clearCache()`.
   - In `lyrics(...)`, add `forceRefresh: Boolean = false` (default preserves all callers). When `!forceRefresh`, check `LyricsCache.get(...)` first and invoke `onSourceResult` on hit. On online hit, call `LyricsCache.put(videoId, result)`. On online miss (all sources failed), fall back to `LyricsCache.getAny(videoId)`.
3. In `BitChordApplication.kt` (beside `LyricsTranslation.cacheDir = cacheDir` at L110), set `LyricsRepository.cacheDir = cacheDir`.

- [ ] **Step 4: Run focused test and verify it passes**

Run: `.\gradlew.bat :shared:jvmTest --tests com.music.bitchord.data.lyrics.LyricsCacheTest :shared:jvmTest --tests com.music.bitchord.data.lyrics.LyricsPickTest --no-configuration-cache`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsCache.kt shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt app/src/main/java/com/music/bitchord/BitChordApplication.kt shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsCacheTest.kt
git commit -m "feat(lyrics): add persistent memory and disk cache in LyricsRepository"
```

---

## Track 3: UI Rendering Regressions

### Task 5: Fix progressive blur on Home cold launch (#1997)

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/ui/components/TopFadeBlur.kt:80-111`
- Modify: `app/src/main/java/com/music/bitchord/MainActivity.kt:3690-3706`
- Create Test: `app/src/test/java/com/music/bitchord/TopFadeBlurAlphaTest.kt`

**Interfaces:**
- Produces:
  - `TopFadeBlur(hazeState: HazeState, pageColor: Color, modifier: Modifier = Modifier, alpha: () -> Float = { 1f })`
  - `internal fun clampTopFadeAlpha(raw: Float): Float`

- [ ] **Step 1: Write failing unit test for alpha clamping**

Create `app/src/test/java/com/music/bitchord/TopFadeBlurAlphaTest.kt`:
- `clampTopFadeAlpha(-0.5f) == 0f`, `clampTopFadeAlpha(0f) == 0f`, `clampTopFadeAlpha(0.4f) == 0.4f`, `clampTopFadeAlpha(1.5f) == 1f`, `clampTopFadeAlpha(Float.NaN) == 0f`.

- [ ] **Step 2: Run focused test and verify it fails**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.TopFadeBlurAlphaTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: compile failure (`clampTopFadeAlpha` unresolved).

- [ ] **Step 3: Drive `HazeEffectScope.alpha` directly in `TopFadeBlur`**

1. In `TopFadeBlur.kt` (`L80-111`):
   - Add `internal fun clampTopFadeAlpha(raw: Float): Float = if (raw.isFinite()) raw.coerceIn(0f, 1f) else 0f`.
   - Add `alpha: () -> Float = { 1f }` to `TopFadeBlur`.
   - Inside `.hazeEffect(state = hazeState) { ... }`, set `this.alpha = clampTopFadeAlpha(alpha())` so Haze 1.3.1 tracks alpha changes and invalidates `HazeEffectNode` cleanly from `0f -> >0f` without being skipped by an outer `graphicsLayer`.
2. In `MainActivity.kt` (`L3690-3706`):
   - Remove `.graphicsLayer { alpha = topFadeAlpha }` from the `TopFadeBlur` modifier and pass `alpha = { topFadeAlpha }`.

- [ ] **Step 4: Run focused test and compile**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.TopFadeBlurAlphaTest :app:assembleDevDebug --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/music/bitchord/ui/components/TopFadeBlur.kt app/src/main/java/com/music/bitchord/MainActivity.kt app/src/test/java/com/music/bitchord/TopFadeBlurAlphaTest.kt
git commit -m "fix(ui): drive TopFadeBlur alpha via HazeEffectScope for cold launch"
```

---

### Task 6: Fix v1.10 local artwork/placeholder flicker (#2002)

**Files:**
- Modify: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerState.kt:115-240`
- Modify: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt:1605-1615, 3760-3865`
- Modify: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LandscapePlayer.kt:279-358`
- Test: `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/PlayerArtworkHoldOverTest.kt`

**Interfaces:**
- Produces:
  - `internal fun isRetryableArtworkUrl(url: String?): Boolean`
  - `PlayerArtwork.retryable: Boolean`
  - `PlayerArtwork.settledEmpty: Boolean`
  - `PlayerArtwork.hasVisibleCover: Boolean`
  - Updated `PlayerArtwork.lastCover: MemoryCache.Key?`

- [ ] **Step 1: Write failing unit tests in `PlayerArtworkHoldOverTest.kt`**

Add test cases:
- `isRetryableArtworkUrl("content://media/external/audio/media/42/albumart") == false`
- `isRetryableArtworkUrl("file:///storage/emulated/0/a.mp3") == false`
- `isRetryableArtworkUrl("https://lh3.googleusercontent.com/abc") == true`
- `isRetryableArtworkUrl(null) == false`
- Local track with `holdOver = Key("prev")` hitting `Error`: `settledEmpty == true`, `lastCover == null`, `hasVisibleCover == false`.
- Track during `Loading` with `holdOver = Key("prev")`: `hasVisibleCover == true`, `lastCover == Key("prev")`.
- Remote `https://` track hitting first `Error` (`attempt = 0`): `settledEmpty == false`, still eligible for retry; after `attempt == ART_RETRIES` + `Error`, `settledEmpty == true` and `lastCover == null`.

- [ ] **Step 2: Run focused test and verify it fails**

Run: `.\gradlew.bat :sharedUi:jvmTest --tests com.music.bitchord.ui.player.PlayerArtworkHoldOverTest --no-configuration-cache`
Expected: FAIL.

- [ ] **Step 3: Implement `PlayerArtwork` fixes and wire `hasVisibleCover`**

1. In `PlayerState.kt` (`L115-240`):
   - Add `isRetryableArtworkUrl(url: String?): Boolean`.
   - On `PlayerArtwork`, add `val retryable = isRetryableArtworkUrl(url)`, `val settledEmpty get() = url == null || (failed && (!retryable || attempt >= ART_RETRIES))`, `val hasVisibleCover get() = loaded || (!failed && holdOver != null)`, and change `lastCover` to `if (settledEmpty) null else shown ?: holdOver`.
   - In `rememberPlayerArtwork`, guard the retry `LaunchedEffect` with `if (!art.retryable || !art.failed || art.attempt >= ART_RETRIES) return@LaunchedEffect`.
2. In `NowPlayingScreen.kt` (`L1611`) and `LandscapePlayer.kt` (`L279-358`):
   - Use `art.hasVisibleCover` for `coverLoaded()` / `artLoaded` (sleeve shadow/clip and hiding the `MusicNote` icon while a `holdOver` cover is shown).

- [ ] **Step 4: Run focused tests and verify they pass**

Run: `.\gradlew.bat :sharedUi:jvmTest --tests com.music.bitchord.ui.player.PlayerArtworkHoldOverTest :sharedUi:jvmTest --tests com.music.bitchord.ui.player.AutoMixCoverTest --no-configuration-cache`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerState.kt sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LandscapePlayer.kt sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/PlayerArtworkHoldOverTest.kt
git commit -m "fix(player): eliminate local artwork retry flashes and stale hold-over leaks"
```

---

## Track 4: Playlist Current Track Management & Locate UX

### Task 7: Now Playing `...` menu "Remove from playlist" (#482)

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:1346-1375`
- Modify: `app/src/main/java/com/music/bitchord/MainActivity.kt:4450-4480`
- Create Test: `app/src/test/java/com/music/bitchord/PlaylistCurrentTrackUxTest.kt`

**Interfaces:**
- Produces:
  - `internal fun removablePlaylistId(song: Song, detailBrowseId: String?, fromPlayer: Boolean): String?`
  - `MainViewModel.removeFromPlaylist(browseId: String, song: Song, onResult: ((Boolean) -> Unit)? = null)`

- [ ] **Step 1: Write failing unit tests for `removablePlaylistId`**

Create `app/src/test/java/com/music/bitchord/PlaylistCurrentTrackUxTest.kt`:
- When `fromPlayer = true`, `playbackSourceType = BROWSE`, `playbackSourceId = "VLPL123"`, `fromAutoplay = false` -> returns `"VLPL123"`.
- When `fromPlayer = true`, `fromAutoplay = true` -> returns `null`.
- When `fromPlayer = true`, `playbackSourceType = SEARCH` -> returns `null`.
- When `fromPlayer = false`, `detailBrowseId = "VLPL999"` -> returns `"VLPL999"`.

- [ ] **Step 2: Run focused test and verify it fails**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.PlaylistCurrentTrackUxTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: compile failure (`removablePlaylistId` unresolved).

- [ ] **Step 3: Implement `removablePlaylistId`, `setVideoId` fallback, and Now Playing menu wiring**

1. In `MainViewModel.kt` (`L1346-1375`):
   - Add `removablePlaylistId(song, detailBrowseId, fromPlayer)`.
   - Extend `removeFromPlaylist(browseId: String, song: Song, onResult: ((Boolean) -> Unit)? = null)`: if `song.setVideoId == null`, resolve `setVideoId` from `_detailStack` matching `browseId` & `videoId`, or fall back to `YtMusicRepository.playlistEntries(browseId)`. Call `onResult?.invoke(true)` on success and `onResult?.invoke(false)` on failure.
2. In `MainActivity.kt` (`L4450-4480`):
   - Pre-resolve ownership via `viewModel.resolvePlaylistOwnership(sourceId)` when the active `player.song` has a `"VL..."` `playbackSourceId` (`!song.fromAutoplay`) not yet in `_playlistOwned`.
   - Use `removablePlaylistId(song, detail?.browseId, fromPlayer)` for all 3 `onRemoveFromPlaylist` branches (YT owned playlist, `OfflinePlaylists`, `LocalPlaylistStore`) so Now Playing `...` menu exposes "Remove from playlist" and displays `QueueActionNotice` ("Removed from playlist" / "Failed").

- [ ] **Step 4: Run focused test and verify it passes**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.PlaylistCurrentTrackUxTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/music/bitchord/ui/MainViewModel.kt app/src/main/java/com/music/bitchord/MainActivity.kt app/src/test/java/com/music/bitchord/PlaylistCurrentTrackUxTest.kt
git commit -m "feat(player): allow removing currently playing track from its playlist"
```

---

### Task 8: `DetailScreen` Locate button, offline/local picker checkbox diff, and strings (#482)

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt`
- Modify: `app/src/main/java/com/music/bitchord/ui/components/PlaylistPickerSheet.kt:110-270`
- Modify: `app/src/main/java/com/music/bitchord/MainActivity.kt:3941-4006, 5275-5398`
- Modify: `app/src/main/res/values/strings.xml` and all `app/src/main/res/values-*/strings.xml`
- Test: `app/src/test/java/com/music/bitchord/PlaylistCurrentTrackUxTest.kt`
- Test: `app/src/test/java/com/music/bitchord/LocaleStringsTest.kt`

**Interfaces:**
- Produces:
  - `internal fun locatePlayingSongIndex(matches: List<IndexedValue<Song>>, currentSong: Song?, headerItemCount: Int): Int?`
  - `internal fun diffPlaylistSelection(playlists: List<UserPlaylist>, initialIds: Set<String>, finalIds: Set<String>): Pair<List<UserPlaylist>, List<UserPlaylist>>`
  - `PlaylistPickerSheet(..., initialSelectedIds: Set<String> = emptySet(), onConfirmSelection: ((List<UserPlaylist>, List<UserPlaylist>) -> Unit)? = null)`

- [ ] **Step 1: Write failing unit tests for `locatePlayingSongIndex` and `diffPlaylistSelection`**

Add to `PlaylistCurrentTrackUxTest.kt`:
- `locatePlayingSongIndex` accounts for `headerItemCount` (1 with header only, 2 with search bar open), matches `setVideoId` slot when `videoId` is duplicated, and returns `null` when absent.
- `diffPlaylistSelection` returns `(added, removed)` cleanly and `(emptyList(), emptyList())` when unchanged.

- [ ] **Step 2: Run focused test and verify it fails**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.PlaylistCurrentTrackUxTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: compile failure (`locatePlayingSongIndex` / `diffPlaylistSelection` unresolved).

- [ ] **Step 3: Implement Locate action, `PlaylistPickerSheet` membership diff, and locale strings**

1. Add strings `removed_from_playlist`, `locate_current_track`, `locating_track`, `playlists_updated` to `values/strings.xml` and every `values-*/strings.xml`.
2. In `DetailScreen.kt` + `MainActivity.kt` (`L3941-4006`):
   - Add `locatePlayingSongIndex(...)`.
   - Show a compact Locate icon button in `FrostedTopBar` on playlist detail pages when `currentSong != null` and (`locatePlayingSongIndex(...) != null` or `page.continuation != null && currentSong.playbackSourceId == page.browseId`).
   - On tap, animate `listState.animateScrollToItem(targetIndex, -locateScrollOffset)`; if still paginating, set `pendingLocate = true`, show `locating_track` notice, and scroll once `matches` includes the track (clearing if `page.continuation == null`).
3. In `PlaylistPickerSheet.kt` + `MainActivity.kt` (`L5275-5398`):
   - Add `initialSelectedIds`, `onConfirmSelection`, and `diffPlaylistSelection(...)`.
   - Wire offline (`OfflinePlaylists`) and local (`LocalPlaylistStore`) pickers to pre-check existing memberships and apply `(added, removed)` on confirm with a `QueueActionNotice`. Leave online YT Music picker using existing `onAdd`.

- [ ] **Step 4: Run focused tests including `LocaleStringsTest`**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.PlaylistCurrentTrackUxTest --tests com.music.bitchord.LocaleStringsTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt app/src/main/java/com/music/bitchord/ui/components/PlaylistPickerSheet.kt app/src/main/java/com/music/bitchord/MainActivity.kt app/src/main/res/values*/strings.xml app/src/test/java/com/music/bitchord/PlaylistCurrentTrackUxTest.kt
git commit -m "feat(playlist): add locate playing track and offline/local picker membership diff"
```

---

## Final Verification

### Task 9: Full Android suite, build, diff check, and device checklist

**Files:**
- No production changes expected.

- [ ] **Step 1: Run full Android & shared unit test suites**

Run: `.\gradlew.bat :shared:jvmTest :sharedUi:jvmTest :app:testDevDebugUnitTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: PASS (document any known pre-existing failures if encountered).

- [ ] **Step 2: Build Dev Debug APK and check diff cleanliness**

Run: `.\gradlew.bat :app:assembleDevDebug --no-configuration-cache -PbitchordAbi=arm64-v8a` and `git diff --check`
Expected: BUILD SUCCESSFUL, zero whitespace/conflict issues, only Android modules (`app/`, `shared/`, `sharedUi/`) modified.

- [ ] **Step 3: Manual checklist on physical device (`com.dev.bitchord`)**

Install with `.\gradlew.bat :app:installDevDebug --no-configuration-cache -PbitchordAbi=arm64-v8a`, then verify:
- **Track 1:** Switch language to Spanish & Russian -> album page shows `"Ken Carson"` (not `"Ken Carson, Álbum"`), Canvas plays, "Listen again" tracks show real artist + lyrics load; open a 500+ track YT playlist -> all tracks load and scroll-to-resume recovers if interrupted.
- **Track 2:** Enable lyrics translation -> skip to next foreign track (stays translated) -> skip to same-language track (shows originals) -> skip to foreign track (auto-translates) -> close & reopen Now Playing (stays translated, no replayed particle animation); enable airplane mode -> previously played cached song loads lyrics offline.
- **Track 3:** Cold launch on Home with Progressive Blur on -> scroll Home immediately -> blur fades in without switching tabs; skip across local tracks (with art -> without art -> without art -> with art) -> zero repeated cover flashes or stale cover leaks.
- **Track 4:** Play from owned YT playlist & offline playlist -> Now Playing `...` menu shows "Remove from playlist" and removes cleanly; let queue roll into Autoplay -> option hidden; open large playlist -> tap Locate -> scrolls to playing track; open offline playlist picker -> existing playlist pre-checked, unchecking removes song.

If no device is connected, report the manual checklist as not run rather than passing it.
