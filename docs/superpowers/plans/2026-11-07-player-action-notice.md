# Player Action Notice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show playlist and other app action notices over the fullscreen player; allow swipe dismissal; match the mini player's non-glass blur and opacity.

**Architecture:** Keep `QueueActionNotice` as the single activity-owned state and retain its existing three-second lifetime. Continue rendering its host above the mini player in the activity window, and render the same notice in the fullscreen player's separate modal window, which is visually above the activity. Give each window a `HazeState` sourced from its own visible content, and use `HazeMaterials.thin` in both hosts. The player's notice host is an overlay sibling after `NowPlayingScreen`, positioned above its bottom controls and system navigation area.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Haze, JUnit 4.

**Spec:** User request in this thread: the playlist-add toast is hidden in the fullscreen player; make notices visible there, swipeable, and use mini-player blur/opacity without Liquid Glass. Trace found playlist success is `QueueActionNotice`, not Android `Toast`; other app confirmations routed through `showQueueNotice` share the same host. Native `Toast.makeText` calls outside this host are out of scope.

## Global Constraints

- Android app only; leave `tv/`, `wearos/`, `web/`, `desktopApp/`, and `desktopUi/` untouched.
- Keep existing notice text, live-region semantics, animation setting, and three-second timeout.
- Do not add dependencies or switch the notice to Liquid Glass.
- Dismiss only the notice instance that received the swipe; an old notice's timer or gesture must not clear a newer notice.
- Preserve existing activity-window placement above the mini player when the fullscreen player is closed.

## Review Focus

- Notice fired while fullscreen player is already open: visible in modal window above `NowPlayingScreen`.
- Notice fired before opening player: same notice remains visible after the player window opens, without duplicate visible notices.
- Left and right swipes beyond threshold dismiss; short horizontal drag resets; vertical movement does not dismiss.
- A repeated notice replaces/restarts the current notice; previous timeout cannot clear its replacement.
- `reduceDynamicBlur` uses opaque surface fallback; otherwise both hosts use `HazeMaterials.thin` and source only content in their own window.
- Swipe-dismiss accessibility action invokes the same id-checked dismissal path.

---

### Task 1: Make the notice swipeable and use mini-player material

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/ui/components/QueueActionNotice.kt`
- Create: `app/src/test/java/com/music/bitchord/QueueActionNoticeTest.kt`

**Interfaces:**
- Consumes: `QueueActionNoticeHost(notice, hazeState, onDismiss, modifier)` where `hazeState: HazeState`, `onDismiss: (Int) -> Unit`.
- Produces: a notice whose horizontal drag dismiss threshold is 35% of its measured width, with accessible dismiss semantics and Haze `thin` material styling.

- [x] **Step 1: Write failing swipe-threshold tests**

Add tests for `shouldDismissQueueActionNotice(dragPx: Float, widthPx: Float): Boolean`: both signs at 36% return true; both signs at 34% return false; zero width returns false.

- [x] **Step 2: Run focused test and verify it fails because helper is absent**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.QueueActionNoticeTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: compile failure for the missing helper.

- [x] **Step 3: Implement threshold and gesture in `QueueActionNotice.kt`**

Add top-level `internal fun shouldDismissQueueActionNotice(dragPx: Float, widthPx: Float): Boolean` using the 35% threshold. Track drag translation with Compose state and `detectHorizontalDragGestures`; dismiss on threshold, otherwise reset on drag end/cancel. Add semantics `dismiss` action and route it to `onDismiss(notice.id)`. Keep the current notice live region and enter/exit animation.

- [x] **Step 4: Apply mini-player Haze material to the host**

Use the passed `HazeState` and `Modifier.optimizedHazeEffect(..., HazeMaterials.thin(MaterialTheme.colorScheme.surface))`; clip to the existing rounded shape and keep the subtle white border and elevation. If `reduceDynamicBlur` is on, use `MaterialTheme.colorScheme.surface` as the solid background, matching `MiniPlayer` behavior.

- [x] **Step 5: Run focused tests**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests com.music.bitchord.QueueActionNoticeTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: PASS.

### Task 2: Render the shared notice above the fullscreen player

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/MainActivity.kt`

**Interfaces:**
- Consumes: Task 1 `QueueActionNoticeHost(notice, hazeState, onDismiss, modifier)`.
- Produces: the existing bottom-bar host uses the activity `hazeState`; the fullscreen player window has a local Haze source on player content and a notice host above player content.

- [x] **Step 1: Update the two activity-window host calls**

Pass the existing activity `hazeState` and an id-checked callback that clears `queueNotice` only when `queueNotice?.id == dismissedId`.

- [x] **Step 2: Add a Haze source and host to the modal player window**

Create a remembered `HazeState` for the player window. Add `.hazeSource(playerNoticeHaze)` to the `Box` wrapping `NowPlayingScreen` content, then place `QueueActionNoticeHost` as a later sibling in the modal sheet's outer `Box`. Align bottom-center, apply `navigationBarsPadding()`, then 96.dp bottom padding so notice sits above transport controls. Pass the same id-checked dismissal callback and existing `queueNotice` state.

- [x] **Step 3: Verify all notice origins still share the host**

Inspect all `showQueueNotice` call sites, especially playlist success/failure, queue addition, version-switch feedback, and party-control refusals. Confirm they use the same state and remain visible in both windows. Do not replace separate native Android Toast calls.

### Task 3: Verify integration and layout

**Files:**
- No additional production files expected.

- [x] **Step 1: Run the full Android app unit suite**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --no-configuration-cache -PbitchordAbi=arm64-v8a`
Expected: PASS; report unrelated pre-existing failures if any.

- [x] **Step 2: Review diff and check whitespace**

Run: `git diff --check`; inspect that activity and modal player each host exactly the same notice state and that the modal host is drawn after `NowPlayingScreen` content.

- [ ] **Step 3: Manual interaction checklist**

Open player, add a song to a playlist, confirm notice appears above player controls, swipe left and right to dismiss, confirm it auto-dismisses after three seconds, then repeat with reduced blur and reduced animation enabled.
