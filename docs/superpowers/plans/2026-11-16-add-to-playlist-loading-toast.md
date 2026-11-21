# Add-to-Playlist Loading Toast Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the existing `QueueActionNotice` with a spinner and "Adding…" while an online playlist add is in flight, then replace it with the existing result notice.

**Architecture:** `QueueActionNotice` gains a `loading` flag. The single activity-owned `queueNotice` state drives both the activity-window and fullscreen-player hosts, so only the model, host, and one auto-dismiss guard change. `MainActivity` shows the loading notice before `viewModel.addToPlaylists` and lets the existing result callback replace it.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Haze, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-16-add-to-playlist-loading-toast-design.md`

## Global Constraints

- Android app only; leave `tv/`, `wearos/`, `web/`, `desktopApp/`, and `desktopUi/` untouched.
- No new dependencies.
- Existing one-argument `showQueueNotice(message)` call sites stay unchanged.
- Non-loading notice lifetime stays three seconds.
- Offline/device playlist adds (`OfflinePlaylists.add`) and new-playlist creation are untouched.
- New string lives in `values/strings.xml` only; other locales fall back to English.

## Review Focus

- A request slower than three seconds: the loading notice must remain until the result arrives.
- A duplicate add: spinner must be replaced by "Already in playlist", not left spinning.
- A failed add: spinner must be replaced by "Failed".
- Swipe-dismissing a loading notice: the result notice still appears afterward.
- Fullscreen player open: the same spinner appears in the player's notice host.

UI-state transitions above are covered by Task 3's manual checklist; the timed-lifetime rule is pinned by the unit test in Task 1.

---

### Task 1: Loading-aware notice model and host

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/ui/components/QueueActionNotice.kt`
- Test: `app/src/test/java/com/music/bitchord/QueueActionNoticeTest.kt`
- Modify: `app/src/main/res/values/strings.xml` (near `song_added_to_playlist`, line 635)

**Interfaces:**
- Consumes: existing `QueueActionNoticeHost(notice, hazeState, onDismiss, modifier)` signature.
- Produces:
  - `data class QueueActionNotice(val id: Int, val message: String, val loading: Boolean = false)`
  - `internal fun shouldAutoDismissQueueActionNotice(notice: QueueActionNotice?): Boolean`

- [ ] **Step 1: Write the failing lifetime test**

Add to `QueueActionNoticeTest.kt`, with imports `com.music.bitchord.ui.components.QueueActionNotice` and `com.music.bitchord.ui.components.shouldAutoDismissQueueActionNotice`:

```kotlin
@Test
fun `only a settled notice auto-dismisses`() {
    assertTrue(shouldAutoDismissQueueActionNotice(QueueActionNotice(1, "Added to playlist")))
    assertFalse(
        shouldAutoDismissQueueActionNotice(QueueActionNotice(2, "Adding…", loading = true)),
    )
    assertFalse(shouldAutoDismissQueueActionNotice(null))
}
```

- [ ] **Step 2: Run the focused test and verify it fails to compile**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.QueueActionNoticeTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: compile failure — `shouldAutoDismissQueueActionNotice` and/or `loading` unresolved.

- [ ] **Step 3: Implement the model change, helper, spinner, and string**

In `QueueActionNotice.kt`:

```kotlin
data class QueueActionNotice(
    val id: Int,
    val message: String,
    val loading: Boolean = false,
)

/** Loading notices stay until their result arrives; only settled ones time out. */
internal fun shouldAutoDismissQueueActionNotice(notice: QueueActionNotice?): Boolean =
    notice != null && !notice.loading
```

In `QueueActionNoticeHost`'s inner `Box`, replace the lone `Text` with a `Row` (`verticalAlignment = Alignment.CenterVertically`, `horizontalArrangement = Arrangement.spacedBy(12.dp)`) carrying the existing padding. Put the `Text` second. When `current.loading`, put first:

```kotlin
CircularProgressIndicator(
    modifier = Modifier.size(16.dp),
    strokeWidth = 2.dp,
    color = MaterialTheme.colorScheme.onSurface,
)
```

Import `androidx.compose.foundation.layout.Arrangement`, `Row`, `size`, `androidx.compose.material3.CircularProgressIndicator`, and `androidx.compose.ui.Alignment`.

In `values/strings.xml` beside `song_added_to_playlist`:

```xml
<string name="adding_to_playlist">Adding…</string>
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.QueueActionNoticeTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/music/bitchord/ui/components/QueueActionNotice.kt app/src/test/java/com/music/bitchord/QueueActionNoticeTest.kt app/src/main/res/values/strings.xml
git commit -m "feat(playlist): add loading state to queue action notice"
```

### Task 2: Show "Adding…" while the playlist add runs

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/MainActivity.kt:1096-1109`
- Modify: `app/src/main/java/com/music/bitchord/MainActivity.kt:4996-5013`

**Interfaces:**
- Consumes: Task 1's `QueueActionNotice(... loading = false)` and `shouldAutoDismissQueueActionNotice`.
- Produces: `showQueueNotice(message: String, loading: Boolean = false)` callable from all existing notice sites.

- [ ] **Step 1: Make the notice setter loading-aware**

Replace the `val showQueueNotice: (String) -> Unit` lambda with a local function and update the lifetime effect:

```kotlin
fun showQueueNotice(message: String, loading: Boolean = false) {
    queueNoticeId += 1
    queueNotice = QueueActionNotice(queueNoticeId, message, loading)
}
val dismissQueueNotice: (Int) -> Unit = { id ->
    if (queueNotice?.id == id) queueNotice = null
}
LaunchedEffect(queueNotice?.id, queueNotice?.loading) {
    val shown = queueNotice ?: return@LaunchedEffect
    if (!shouldAutoDismissQueueActionNotice(shown)) return@LaunchedEffect
    delay(3_000)
    if (queueNotice?.id == shown.id) queueNotice = null
}
```

Import `com.music.bitchord.ui.components.shouldAutoDismissQueueActionNotice`. Leave `showHostOnlyNotice` and every other call site as is.

- [ ] **Step 2: Show the loading notice on picker add**

In the online picker's `onAdd`, before `viewModel.addToPlaylists(picked, song) { ... }`:

```kotlin
showQueueNotice(context.getString(R.string.adding_to_playlist), loading = true)
```

The existing result callback is unchanged and replaces the loading notice by issuing a new id.

- [ ] **Step 3: Verify compilation**

Run: `.\gradlew.bat :app:assembleDevDebug --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/music/bitchord/MainActivity.kt
git commit -m "feat(playlist): show adding spinner while playlist add runs"
```

### Task 3: Full verification

**Files:**
- No production changes expected.

- [ ] **Step 1: Run the Android unit suite**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: PASS; report unrelated pre-existing failures if any.

- [ ] **Step 2: Check the diff**

Run: `git diff --check` and inspect `git diff dev`.
Expected: only the notice model/host, the string, the activity wiring, and the test changed; no offline/add-to-new-playlist paths touched.

- [ ] **Step 3: Manual checklist on device (dev build)**

Install with `.\gradlew.bat :app:installDevDebug --no-configuration-cache -PbitchordAbi=arm64-v8a`, then verify:
- Add to one playlist: spinner + "Adding…" then "Added to playlist".
- Add a duplicate: spinner then "Already in playlist".
- Add to several: spinner then the plural notice.
- Swipe the loading notice away: result notice still appears.
- Open the fullscreen player and add: spinner visible in the player's notice.

If no device is available, report the checklist as not run rather than passing it.
