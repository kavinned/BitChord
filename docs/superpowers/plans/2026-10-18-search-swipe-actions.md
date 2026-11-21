# Search Page Swipe-to-Queue and Play-Next Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable swipe-to-queue and swipe-to-play-next gestures on Search History track items and the Search Top Result card, matching the behavior of standard song rows.

**Architecture:** Extract a reusable `SwipeToQueueBox` in `sharedUi` encapsulating the existing `SwipeToDismissBoxState`, 45% threshold logic, haptics, and `QueueSwipeBackground`. Apply this component to `TopResultCard` and `RecentSearchEntityRow` (for `EntityType.TRACK`), converting history entities to `Song` models for queue dispatch.

**Tech Stack:** Kotlin, Jetpack Compose Material 3 (`SwipeToDismissBox`, `SwipeToDismissBoxState`), Coroutines/Flows, Gradle.

**Spec:** User request: "swipe to add a song to queue or play next depending on the setting... apply to the songs that show on the search page, meaning when you open search page, it shows previous search results there... also work on the top result on the search results."

## Global Constraints
- Android mobile app focus only (`app/` and `:sharedUi` used by `:app`).
- Preserve architectural boundaries (UI layer Compose components only; dispatch through `onSongSwipe: (Song) -> Unit`).
- Zero code duplication between `SongRow`, `TopResultCard`, and `RecentSearchEntityRow` swipe handling.
- GPLv3 licensing compliance — no external dependencies.
- Surgical diffs; maintain all existing comments and formatting.

## Review Focus
1. Non-track history entities (`ALBUM`, `ARTIST`, `PLAYLIST`) must remain non-swipable with no swipe gesture or background reveal.
2. Trailing 'X' delete button on `RecentSearchEntityRow` must continue to process taps without accidental swipe triggers.
3. `TopResultCard` background must remain opaque (`MaterialTheme.colorScheme.background`) so underlying action background does not bleed through.
4. Setting change between "Play Next" and "Add to Queue" (`AppSettings.swipeToPlayNext`) must reactively update revealed icons and text on all swipeable search items.
5. Swipes below the 45% box width threshold must snap back without invoking queue callbacks or false haptics.

---

### Task 1: Extract Reusable `SwipeToQueueBox` in `Common.kt`

**Files:**
- Modify: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt:340-445`

**Interfaces:**
- Produces: `SwipeToQueueBox(onSwipeToQueue: (() -> Unit)?, modifier: Modifier = Modifier, rowBackground: Color = MaterialTheme.colorScheme.background, content: @Composable () -> Unit)`
- Consumes: `QueueSwipeBackground(swipeState: SwipeToDismissBoxState)`

- [ ] **Step 1: Define `SwipeToQueueBox` composable**
  Extract gesture tracking, `rememberSwipeToDismissBoxState`, haptic arming `LaunchedEffect`, and `SwipeToDismissBox` wrapping into `SwipeToQueueBox`. When `onSwipeToQueue == null`, render `content()` directly.

- [ ] **Step 2: Refactor `SongRow` to use `SwipeToQueueBox`**
  Replace inline `SwipeToDismissBox` implementation in `SongRow` with `SwipeToQueueBox(onSwipeToQueue = onSwipeToQueue, modifier = modifier, rowBackground = rowBackground) { SongRowContent(...) }`.

- [ ] **Step 3: Verify clean build**
  Run: `rtk .\gradlew.bat compileDevDebugKotlin`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit Task 1**
  ```bash
  rtk git add sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt
  rtk git commit -m "refactor(ui): extract reusable SwipeToQueueBox component"
  ```

---

### Task 2: Enable Swipe on Top Result Card

**Files:**
- Modify: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt:313-323, 475-487`

**Interfaces:**
- Consumes: `SwipeToQueueBox` from `com.music.bitchord.ui.components`
- Consumes: `onSongSwipe: (Song) -> Unit` from `SearchScreen`

- [ ] **Step 1: Update `TopResultCard` container**
  Ensure `TopResultCard` accepts `modifier: Modifier = Modifier` and applies an opaque background `Modifier.background(MaterialTheme.colorScheme.background)` so dragged card contents cover the revealed action.

- [ ] **Step 2: Wrap `TopResultCard` with `SwipeToQueueBox` in `SearchScreen`**
  At `SearchScreen.kt:315`, wrap `TopResultCard` invocation in `SwipeToQueueBox(onSwipeToQueue = { onSongSwipe(topResult.song) })`.

- [ ] **Step 3: Verify compilation**
  Run: `rtk .\gradlew.bat compileDevDebugKotlin`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit Task 2**
  ```bash
  rtk git add sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt
  rtk git commit -m "feat(search): add swipe to queue on top search result"
  ```

---

### Task 3: Enable Swipe on Search History Track Items

**Files:**
- Modify: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt:293-301, 757-798, 877-890`
- Create: `app/src/test/java/com/music/bitchord/SearchHistoryTrackMappingTest.kt`

**Interfaces:**
- Consumes: `onSongSwipe: (Song) -> Unit`, `SearchHistoryEntity`, `EntityType.TRACK`
- Produces: Track entity-to-Song mapping for swipe dispatch

- [ ] **Step 1: Write test for `SearchHistoryEntity` track conversion**
  Create `SearchHistoryTrackMappingTest.kt` verifying `SearchHistoryEntity` with `EntityType.TRACK` maps correctly to `Song(videoId = entity.id, title = entity.title, artist = entity.subtitle, thumbnailUrl = entity.artworkUrl)` and non-track entities return null song.

- [ ] **Step 2: Run test to verify failure/clean state**
  Run: `rtk .\gradlew.bat testDevDebugUnitTest --tests "com.music.bitchord.SearchHistoryTrackMappingTest"`

- [ ] **Step 3: Forward `onSongSwipe` to `recentSearches`**
  Add `onSongSwipe: (Song) -> Unit` parameter to `recentSearches` in `SearchScreen.kt`.

- [ ] **Step 4: Update `RecentSearchEntityRow` with `onSwipeToQueue`**
  In `recentSearches`, calculate `onSwipe = if (entity.entityType == EntityType.TRACK) { { onSongSwipe(entity.toSong()) } } else null`.
  Wrap `RecentSearchEntityRow` in `SwipeToQueueBox(onSwipeToQueue = onSwipe, rowBackground = MaterialTheme.colorScheme.background)`.

- [ ] **Step 5: Run tests and verify**
  Run: `rtk .\gradlew.bat testDevDebugUnitTest --tests "com.music.bitchord.SearchHistoryTrackMappingTest"`
  Expected: PASS

- [ ] **Step 6: Commit Task 3**
  ```bash
  rtk git add sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt app/src/test/java/com/music/bitchord/SearchHistoryTrackMappingTest.kt
  rtk git commit -m "feat(search): add swipe to queue for search history track items"
  ```

---

### Task 4: Regression Testing & Full Verification

**Files:**
- Verify: Entire project build and unit test suite

- [ ] **Step 1: Run complete unit test suite**
  Run: `rtk .\gradlew.bat testDevDebugUnitTest`
  Expected: All tests pass with 0 failures.

- [ ] **Step 2: Assemble Dev Debug APK**
  Run: `rtk .\gradlew.bat assembleDevDebug`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify clean git status**
  Run: `rtk git status`
  Expected: clean — nothing to commit
