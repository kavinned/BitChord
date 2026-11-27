# YouTube Music Locale Metadata & Long Playlist Pagination — Design

Tracks upstream issues **#1388**, **#1283**, and **#1651**.

## Intent

1. **Localized artist metadata (#1388, #1283):** When the app language is non-English (e.g. Spanish, Russian), `Innertube.postMusic()` sends `hl=<lang>` and receives localized YouTube Music type labels (`"Álbum"`, `"Canción"`, `"Композиция"`, `"Альбом"`) and tally strings (`"reproducciones"`, `"прослушиваний"`). English-only word lists in `InnertubeParser` and `DetailScreen` fail to filter these, corrupting `Song.artist` (`"Композиция"`, `"Ken Carson, Álbum"`), which in turn breaks album/track Canvas and lyrics lookups.
2. **Long playlist truncation (#1651):** Users with 1,000+ track playlists sometimes see only the first ~400 songs. There is no hardcoded 400-track cap; instead, `MainViewModel.fillIn` fires continuation requests back-to-back with 0ms inter-page delay, aborts permanently on a single 0-new-song page or transient failure with no scroll/retry recovery in `DetailScreen`, and `InnertubeParser.parsePlaylistShelf` only inspects the first `appendContinuationItemsAction` / `continuationItemRenderer` and deduplicates by `videoId` rather than entry key.

## Goals

- Extract artist names structurally from InnerTube `navigationEndpoint` (`MUSIC_PAGE_TYPE_ARTIST`) whenever present, instead of relying on English-only string filtering.
- Make fallback text filtering (`TYPE_WORDS`, `RELEASE_WORDS`, `KIND_WORDS`, `TALLY`) locale-aware across all supported app languages so album headers, `pageCredit()`, and `artistFromSubtitle()` never leak type labels or view counts into artist credits.
- Keep album/track Canvas and lyrics lookups working identically across all supported locales.
- Make `MainViewModel.fillIn` resilient on 1,000+ track playlists: throttle inter-page requests, tolerate empty/repeated continuation pages while a new token exists, and allow scroll-triggered / manual retry in `DetailScreen` when background pagination stops with a remaining continuation token.
- Fix `InnertubeParser.parsePlaylistShelf` to inspect all `appendContinuationItemsAction` blocks and preserve duplicate playlist entries by `contextEntryKey()`.

## Non-Goals

- Forcing `hl=en` globally on InnerTube requests (would turn localized home shelf titles, moods, and descriptions into English for non-English users).
- Removing `MAX_PAGES = 10` in `YtMusicRepository.songsPaged` for eager synchronous callers (`allSongs`, `playlistEntries`) where unbounded blocking requests could stall actions — keep `songsPaged` bounded (bump or leave documented; `browseSongs` + `moreSongs` is the streaming path).
- Persistent disk caching of playlist track lists (tracked separately in #1786).
- Touching non-Android modules (`tv/`, `wearos/`, `web/`, `desktopApp/`, `desktopUi/`).

## Constraints

- Android app scope only (`app/`, `shared/`); no new dependencies.
- `InnertubeParser` lives in `shared/src/jvmSharedMain`, so all parser logic must be pure JVM Kotlin (no Android `Context` or `R.string` dependencies).
- Existing English parsing behavior and tests (`SearchPagingTest`, `PlaylistContinuationTest`, `FillInRetryTest`, `ContextRegistryTest`) must continue to pass.

## Approaches

### Part A — Localized Metadata (#1388, #1283)

- **A1. Structural extraction first + multilingual stopword tables (chosen).**
  - In `InnertubeParser.parseTwoRowItem` (`InnertubeParser.kt:1667-1713`), inspect `subtitle.runs` for runs whose `navigationEndpoint.browseEndpoint.browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType == "MUSIC_PAGE_TYPE_ARTIST"` (the same structural signal `parseResponsiveListItem` already uses) and store a structured `artist` field (or preserve it on `ShelfItem`) before flattening to `subtitle`.
  - For `pageCredit()` (`InnertubeParser.kt:1102-1127`), `artistFromSubtitle()` (`InnertubeParser.kt:1724-1729`), and `DetailPage.headerLines()` (`DetailScreen.kt:2467-2512`), expand `TYPE_WORDS`, `RELEASE_WORDS`, and `KIND_WORDS` to cover YouTube Music's localized labels across all 17+ supported app locales (including Spanish `"álbum"`, `"canción"`, `"sencillo"`, `"vídeo"`, `"lista"`, `"artista"`, `"episodio"`; Russian `"альбом"`, `"композиция"`, `"сингл"`, `"видео"`, `"плейлист"`, `"исполнитель"`, `"выпуск"`, etc.), normalize splitting on both `" • "` and `" · "` with `Locale.ROOT`, and broaden `TALLY` to reject digit-prefixed localized count segments (e.g. `"12 M de reproducciones"`, `"1,2 млн прослушиваний"`).
  - Tradeoff: small static stopword set to maintain if new locales are added, but structural `MUSIC_PAGE_TYPE_ARTIST` handles the primary path for every language automatically.
- **A2. Force `hl=en` on all InnerTube requests.**
  - Eliminates localized labels instantly, but breaks localized Home/Explore shelf titles and recommendations for non-English users. Rejected.
- **A3. Multilingual stopword list only (no structural `navigationEndpoint` extraction).**
  - Leaves any unsupported or newly added locale vulnerable to the same bug. Rejected.

### Part B — Long Playlist Pagination (#1651)

- **B1. Harden `fillIn` + `parsePlaylistShelf` + scroll-to-resume in `DetailScreen` (chosen).**
  - Add a modest inter-page pause (`FILL_IN_PAGE_DELAY_MS = 250L`) between `moreSongs` calls in `MainViewModel.fillIn` to avoid YouTube rate-limiting after rapid consecutive pages.
  - Tolerate up to `MAX_EMPTY_PAGES = 2` consecutive 0-new-song pages when `fetched.continuation != null && fetched.continuation != token` (matching `PlaylistSync.kt:460-469`), rather than aborting on the first repeated/empty page.
  - Expose `MainViewModel.resumeFillIn(browseId: String)` so when `DetailScreen` scrolls near the bottom of a playlist that still has `page.continuation != null` and no active `fillInJobs[browseId]`, it restarts pagination from the stored token.
  - In `InnertubeParser.parsePlaylistShelf`: iterate all `onResponseReceivedActions` containing `appendContinuationItemsAction`, collect continuation tokens from all `continuationItemRenderer` nodes (preferring the last non-empty token in the playlist scope), and deduplicate by `contextEntryKey()` (keeping `setVideoId` distinct when present, falling back to `videoId`).
  - Tradeoff: slightly slower full-load time for 1,000+ track playlists (~2.5s extra across 10 pages), in exchange for reliable completion and automatic recovery.
- **B2. Only bump retry delays in `fillIn`.**
  - Doesn't fix rate-limit triggering, empty-page aborts, `parsePlaylistShelf` multi-action responses, or recovery once retries exhaust. Rejected.

## Design

### Part A — Localized Metadata
1. **`ShelfItem` (`shared/.../data/model/Models.kt`)**: Add `val artist: String? = null` (default `null`, preserving all existing call sites).
2. **`InnertubeParser.parseTwoRowItem` (`InnertubeParser.kt:1667-1713`)**: Extract artist runs from `renderer.o("subtitle")` where `pageType == "MUSIC_PAGE_TYPE_ARTIST"`; join with `", "` into `ShelfItem.artist`. Callers converting `ShelfItem` -> `Song` (`YtMusicRepository.kt:225, 247, 263, 281`, `MainActivity.kt:2154, 3348`, `PlaybackService.kt:11048, 11078`) prefer `item.artist?.takeIf { it.isNotBlank() } ?: InnertubeParser.artistFromSubtitle(item.subtitle)`.
3. **`InnertubeParser` localized sets (`InnertubeParser.kt:1759-1775`)**:
   - Extract shared `internal val RELEASE_WORDS` and `internal val TYPE_WORDS` covering all supported app locales (`en`, `es`, `ru`, `de`, `fr`, `it`, `pt`, `pl`, `tr`, `id`, `vi`, `th`, `hi`, `ja`, `ko`, `zh`, `ar`, `he`, `uk`).
   - Update `artistFromSubtitle` to split on regex `\s*[•·]\s*` and lowercase with `Locale.ROOT`.
   - Broaden `TALLY` (or add `looksLikeTally(segment: String)`) to match numeric-leading count strings with magnitude suffixes/units (e.g. `12M`, `1,2 млн`, `450 K`, `12 M de reproducciones`) without false-positiving on artist names that start with numbers like `"21 Savage"` or `"50 Cent"` (require a tally keyword OR abbreviation+separator pattern, or check that a segment is not the only non-type segment).
   - Update `pageCredit()` (`InnertubeParser.kt:1102-1127`) to also recognize release headers structurally via `browseEndpoint` / `straplineTextOne` presence when `straplineTextOne` has an artist endpoint, in addition to the expanded `RELEASE_WORDS`.
4. **`DetailPage.headerLines()` (`DetailScreen.kt:2467-2512`)**: Replace the private English-only `KIND_WORDS` with `InnertubeParser.TYPE_WORDS` (single source of truth) and lowercase with `Locale.ROOT`.

### Part B — Long Playlist Pagination
1. **`InnertubeParser.parsePlaylistShelf` (`InnertubeParser.kt:600-635`)**:
   - Aggregate items across all `appendContinuationItemsAction` entries in `onResponseReceivedActions` rather than `firstNotNullOfOrNull`.
   - Preserve entries by `setVideoId ?: videoId` (matching `Song.contextEntryKey()`) instead of `.distinctBy { it.videoId }`.
   - Extract continuation token using `lastOrNull` over `continuationItemRenderer` in the playlist scope so a leading metadata renderer doesn't shadow the tail continuation.
2. **`MainViewModel.fillIn` (`MainViewModel.kt:3361-3423`)**:
   - Add `private const val FILL_IN_PAGE_DELAY_MS = 250L` and `private const val FILL_IN_MAX_EMPTY_PAGES = 2`.
   - Track `emptyPages = 0`: when `!progressed`, if `fetched.continuation != null && fetched.continuation != token && ++emptyPages <= FILL_IN_MAX_EMPTY_PAGES`, advance `next = fetched.continuation` and continue; otherwise exit. Reset `emptyPages = 0` on any progress.
   - `delay(FILL_IN_PAGE_DELAY_MS)` between loop iterations (after the first page).
   - Add `fun resumeFillIn(browseId: String)` that checks `_detailStack.value.lastOrNull { it.browseId == browseId }?.continuation` and starts `fillIn` if `fillInJobs[browseId]?.isActive != true`.
3. **`DetailScreen` scroll-to-resume (`DetailScreen.kt`)**:
   - When `page.continuation != null` and `listState` scrolls within 15 items of the end, invoke `onResumeFillIn()` (wired to `viewModel.resumeFillIn(page.browseId)` in `MainActivity.kt`).

## Verification

- Unit tests:
  - `SearchPagingTest`: Spanish (`"Álbum"`, `"Canción"`, `"Ken Carson • Álbum • 2023"`) and Russian (`"Композиция • Artist"`, `"Альбом"`) cases for `parseTwoRowItem`, `artistFromSubtitle`, `pageCredit`, and `DetailPage.headerLines`.
  - `PlaylistContinuationTest`: multi-action `onResponseReceivedActions`, duplicate `videoId` with distinct `setVideoId`, and trailing `continuationItemRenderer`.
  - `FillInRetryTest`: empty-page tolerance (1–2 empty pages with new token continues; repeating same token stops) and `resumeFillIn` restarts after a failed walk.
- Build & run: `.\gradlew.bat :app:testDevDebugUnitTest -PbitchordAbi=arm64-v8a` and `:app:assembleDevDebug -PbitchordAbi=arm64-v8a`.
- Manual: switch app language to Spanish and Russian -> verify album credit ("Ken Carson", not "Ken Carson, Álbum"), Canvas playback, and "Listen again" artist/lyrics; open a 500+ song YT Music playlist and scroll to the bottom -> verify all tracks load.
