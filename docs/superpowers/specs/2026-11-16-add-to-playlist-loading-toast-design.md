# Add-to-Playlist Loading Toast — Design

## Intent

Adding a song to an online playlist is a network round trip with no feedback until it finishes. The picker closes on the tap, so the screen looks unchanged while the request runs. Show the app's existing `QueueActionNotice` strip immediately with a spinner and "Adding…", then replace it with the existing result message ("Added to playlist" / "Already in playlist" / failure) when `addToPlaylists` returns.

## Goals

- Instant acknowledgement on add.
- Same toast component, placement, blur, animation, and swipe dismissal as every other notice.
- Result notice replaces the loading notice rather than stacking.
- No auto-dismiss while the request is in flight; normal 3-second lifetime once the result lands.

## Non-Goals

- Offline/device playlists: `OfflinePlaylists.add` is synchronous and already documented as having no loading state (`MainActivity.kt:5022`). A spinner there would flash.
- New-playlist creation: the online `createPlaylist` path shows no result notice today; adding one is a separate change.
- Progress percentage, cancellation, or retry.
- Translating the new string beyond the default locale (other locales fall back to English until translated).

## Constraints

- Android app only; no new dependencies.
- One shared notice state (`queueNotice` in `MainActivity`), so both activity-window and fullscreen-player hosts get the spinner without extra wiring.
- Existing call sites of `showQueueNotice` keep their current one-argument form.

## Approaches

- **A. Extend `QueueActionNotice` with a `loading` flag (chosen).** One component, one state, one animation path. Host draws an indeterminate `CircularProgressIndicator` before the text. Auto-dismiss effect returns early while loading.
- **B. Separate loading toast composable.** Duplicates placement, blur, animation, and semantics for a four-line difference; two hosts would need wiring.
- **C. Blocking modal progress.** Heavier than the wait it covers, hides the result transition, and fights the picker's own dismiss behavior.

## Design

1. `QueueActionNotice(id: Int, message: String, loading: Boolean = false)`.
2. `MainActivity.showQueueNotice` becomes a local function with a defaulted `loading` parameter, so existing call sites are unchanged.
3. On picker `onAdd`, show `adding_to_playlist` with `loading = true` before calling `viewModel.addToPlaylists`; the existing result callback then shows the outcome normally.
4. `QueueActionNoticeHost` renders `Row` (spinner + text) when `loading`, plain text otherwise, under the same padding; spinner is 16dp, 2dp stroke, `onSurface` tint.
5. Auto-dismiss `LaunchedEffect` keys on `id` and `loading` and returns early while loading. The notice remains swipe-dismissible; the result notice carries its own id, so a stale timer cannot clear it.
6. Add `<string name="adding_to_playlist">Adding…</string>` to `values/strings.xml`.

## Verification

- `:app:testDevDebugUnitTest` passes; `QueueActionNoticeTest` gains the auto-dismiss predicate case.
- Every locale resource also carries `adding_to_playlist` (English until translated), because `LocaleStringsTest` requires every base key in every supported locale.
- `:app:assembleDevDebug` compiles.
- Manual: add to one playlist (spinner → "Added to playlist"), add a duplicate (spinner → "Already in playlist"), add to several (spinner → plural notice), spinner persists past three seconds on a slow request, swipe during loading still dismisses, fullscreen player shows the same spinner.
