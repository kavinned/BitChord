# Currently Playing Track Playlist Management & Locate UX — Design

Tracks upstream issue **#482**.

## Intent

When listening to a large playlist (especially on shuffle), removing or finding the currently playing song requires manually opening the playlist and scrolling through hundreds of tracks. `SongActionsMenu` already has a "Remove from playlist" item, `MainViewModel.removeFromPlaylist` already exists, and `DetailScreen` already highlights the current row via `playingRowMatcher` — they just aren't wired for the Now Playing context or quick scroll-to-track.

## Goals

1. **Now Playing `...` menu — "Remove from playlist":** When playback originated from an editable playlist (YT Music owned playlist, Offline playlist, or Local/imported playlist) and the current track is not an Autoplay continuation, show **"Remove from playlist"** directly in the Now Playing `...` menu.
2. **Playlist Detail Screen — "Locate current track":** When viewing a playlist that contains the currently playing track (or matches `currentSong.playbackSourceId`), provide a one-tap **Locate** action that scrolls smoothly straight to the playing row (waiting automatically if the playlist is still paginating via `fillIn`).
3. **Playlist Picker (`PlaylistPickerSheet`) — Checkbox membership & uncheck-to-remove (where instant):** For Offline (`OfflinePlaylists`) and Local (`LocalPlaylistStore`) playlists whose full track lists are already in memory, pre-check playlists that already contain the track and allow unchecking to remove (applying add + remove diff on confirm).

## Non-Goals

- Fetching all YT Music user playlists' track lists on `PlaylistPickerSheet` open (would fire N network requests and trigger rate limiting, as noted in `MainViewModel.kt:1082-1084`; YT Music picker remains add-only unless a single-call InnerTube membership endpoint is added later).
- Removing the track from the active ExoPlayer queue automatically when removed from a playlist (removing from a playlist should not abruptly kill the song currently playing; user can skip if desired).
- Removing from read-only playlists (Spotify library playlists, other users' YT Music playlists, YT Music radio/mixes).
- Touching non-Android modules (`tv/`, `wearos/`, `web/`, `desktopApp/`, `desktopUi/`).

## Constraints

- Android app scope only (`app/`, `shared/`); no new dependencies.
- Guard against Autoplay tracks (`Autoplay.kt:115-117` copies `seedSong.playbackSource*` onto autoplay tracks with `setVideoId == null`). Never show "Remove from playlist" for `song.fromAutoplay` / `queueTier == QueueTier.AUTOPLAY`.
- Handle missing `setVideoId` gracefully on YT Music playlists: `LastPlayed` restore and audio/video version-swap drop `setVideoId`, so removal from Now Playing must look up `setVideoId` from the open `_detailStack` or `YtMusicRepository.playlistEntries(browseId)` before calling `Innertube.removeFromPlaylist`.
- Every new string in `values/strings.xml` must also be added to all supported locale `strings.xml` files (English fallback until translated) to satisfy `LocaleStringsTest`.

## Approaches

- **A. Ship all three complementary pieces using existing components (chosen).**
  - Piece 1 (Now Playing `...` menu): Reuse `SongActionsMenu`'s existing `onRemoveFromPlaylist` slot (`SongActionsMenu.kt:306-313`). Wire `MainActivity.kt:4453-4473` to resolve the target playlist from `song.playbackSourceId` when `fromPlayer && song.playbackSourceType == PlaybackSourceType.BROWSE && !song.fromAutoplay`, trigger `resolvePlaylistOwnership(browseId)` if unknown, and surface a `QueueActionNotice` ("Removed from playlist" / failure) on completion.
  - Piece 2 (DetailScreen Locate): Add a compact top-bar action icon (`Icons.Rounded.MyLocation` or `CenterFocusStrong`) in `FrostedTopBar` (`MainActivity.kt:3941-4006`) shown only on playlist pages when `currentSong != null` and either `currentSong` is in `matches` OR `(currentSong.playbackSourceId == page.browseId && page.continuation != null)`. Tapping scrolls via `listState.animateScrollToItem(headerOffset + matchIndex, -topStop)`; if still paginating, sets a `pendingLocate` flag that scrolls as soon as `fillIn` appends the track (and clears if pagination ends without a match).
  - Piece 3 (`PlaylistPickerSheet` membership for offline/local): Pass `initialSelectedIds: Set<String> = emptySet()` into `PlaylistPickerSheet` and an optional `onApplyDiff: ((added: List<UserPlaylist>, removed: List<UserPlaylist>) -> Unit)? = null`. Pre-populate `selected = initialSelectedIds`; enable confirm whenever `selected != initialSelectedIds`. Wire for offline/local pickers immediately; keep online YT Music picker using existing `onAdd` with `initialSelectedIds = emptySet()`.
  - Tradeoff: covers all 3 user requests from #482 while staying zero-risk on YouTube API rate limits.
- **B. Only add Now Playing "Remove from playlist" (skip Locate & Picker uncheck).**
  - Leaves large-playlist navigation friction unsolved when browsing a playlist. Rejected.
- **C. Eagerly fetch every YT Music playlist's tracks on picker open for full online checkbox diffing.**
  - Triggers N `browse` calls per picker open, risking YouTube 429 bans and multi-second spinner lag. Rejected.

## Design

### 1. Now Playing "Remove from playlist"
- **Target resolution helper** (`internal fun removablePlaylistId(song: Song, detailBrowseId: String?, fromPlayer: Boolean): String?`):
  - If `!fromPlayer`: return `detailBrowseId` (existing behavior).
  - If `fromPlayer`: return `song.playbackSourceId` only when `song.playbackSourceType == PlaybackSourceType.BROWSE && !song.fromAutoplay`.
- **Ownership & `setVideoId` resolution (`MainViewModel.kt`)**:
  - When Now Playing menu opens for a `"VL..."` `playbackSourceId` not yet in `_playlistOwned`, call `resolvePlaylistOwnership(browseId)` (or pre-resolve when `player.song` changes with a `"VL..."` `playbackSourceId` so the menu item is immediately ready on tap).
  - Extend `MainViewModel.removeFromPlaylist(browseId: String, song: Song, onResult: ((Boolean) -> Unit)? = null)`:
    - If `song.setVideoId != null`, remove directly (existing path).
    - If `song.setVideoId == null`, check `_detailStack` for a matching `videoId` with non-null `setVideoId`; if absent, fetch `YtMusicRepository.playlistEntries(browseId)` and match by `videoId`.
    - Invoke `onResult(true)` on success and `onResult(false)` on failure so `MainActivity` shows `QueueActionNotice` ("Removed from playlist" / "Failed").
- **Offline & Local playlists (`MainActivity.kt:4453-4473`)**:
  - Allow `OfflinePlaylists.remove(targetId, song)` (when `targetId` is an offline playlist and `song.localUri != null`) and `LocalPlaylistStore.removeSong(targetId, song)` (when `targetId` is a local playlist) when `fromPlayer == true` as well, showing `QueueActionNotice` confirmation.

### 2. Playlist Detail Screen "Locate current track"
- **Pure index helper** (`internal fun locatePlayingSongIndex(matches: List<IndexedValue<Song>>, currentSong: Song?, headerItemCount: Int): Int?`):
  - Uses `playingRowMatcher(matches.map { it.value }, currentSong)` (`Models.kt:267-280`) to find the first matching index in `matches`; returns `headerItemCount + idx` (where `headerItemCount` accounts for `"header"`, optional `"search"`, and optional `"about"` rows in `DetailScreen.kt:592-780`).
- **UI & Pending Locate (`DetailScreen.kt` + `MainActivity.kt:3941-4006`)**:
  - Show a Locate icon button in `FrostedTopBar` on playlist detail pages when `currentSong != null` and (`locatePlayingSongIndex(...) != null` or `page.continuation != null && currentSong.playbackSourceId == page.browseId`).
  - On tap: if index is found, `coroutineScope.launch { listState.animateScrollToItem(targetIndex, -locateScrollOffset) }`. If not yet loaded and `page.continuation != null`, set `pendingLocate = true` and show a brief `QueueActionNotice` ("Locating track…"); a `LaunchedEffect(matches, page.continuation, pendingLocate)` scrolls as soon as the track arrives, or clears `pendingLocate` when `page.continuation == null`.

### 3. `PlaylistPickerSheet` Checkbox Membership Diff (Offline / Local)
- **`PlaylistPickerSheet` (`app/src/main/java/com/music/bitchord/ui/components/PlaylistPickerSheet.kt`)**:
  - Add optional parameters `initialSelectedIds: Set<String> = emptySet()` and `onConfirmSelection: ((added: List<UserPlaylist>, removed: List<UserPlaylist>) -> Unit)? = null`.
  - Initialize `selected = remember(initialSelectedIds) { initialSelectedIds }`.
  - `confirmEnabled = if (onConfirmSelection != null) selected != initialSelectedIds else selected.isNotEmpty()`.
  - Pure diff helper: `internal fun diffPlaylistSelection(playlists: List<UserPlaylist>, initialIds: Set<String>, finalIds: Set<String>): Pair<List<UserPlaylist>, List<UserPlaylist>>`.
- **Wiring (`MainActivity.kt:5275-5398`)**:
  - For offline/local playlist pickers, compute `initialSelectedIds` synchronously from `OfflinePlaylists` / `LocalPlaylistStore` and pass `onConfirmSelection` to add newly checked playlists and remove newly unchecked playlists, showing a `QueueActionNotice` summary.
  - Online YT Music picker keeps existing `onAdd` behavior untouched.

### 4. Strings
- Add to `values/strings.xml` and all locale `values-*/strings.xml` (English fallback until translated):
  - `removed_from_playlist`: `"Removed from playlist"`
  - `locate_current_track`: `"Locate playing track"`
  - `locating_track`: `"Locating track…"`
  - `playlists_updated`: `"Playlists updated"`

## Verification

- Unit tests:
  - `PlaylistCurrentTrackUxTest` (`app/src/test/java/com/music/bitchord/PlaylistCurrentTrackUxTest.kt`):
    - `removablePlaylistId`: returns `playbackSourceId` for `BROWSE` non-autoplay song in player; returns `null` for `fromAutoplay`, non-`BROWSE` sources (`SEARCH`, `HOME`), and falls back to `detailBrowseId` when `!fromPlayer`.
    - `locatePlayingSongIndex`: computes exact `LazyColumn` index with/without search bar and about section, prefers `setVideoId` slot match over duplicate `videoId`, returns `null` when absent.
    - `diffPlaylistSelection`: computes exact `(added, removed)` lists and empty diff when unchanged.
  - `LocaleStringsTest`: passes with all new string keys present in every locale.
- Build & run: `.\gradlew.bat :app:testDevDebugUnitTest -PbitchordAbi=arm64-v8a` and `:app:assembleDevDebug -PbitchordAbi=arm64-v8a`.
- Manual on device:
  - Play from an owned YT Music playlist (and an offline playlist) -> open Now Playing `...` menu -> tap "Remove from playlist" -> confirm toast & removal from playlist.
  - Play into Autoplay after playlist ends -> confirm "Remove from playlist" is NOT shown.
  - Open a large playlist while shuffling -> tap Locate in top bar -> scrolls directly to playing track.
  - Open offline "Add to playlist" picker for a song already in a playlist -> playlist is pre-checked -> uncheck and confirm -> song removed.
