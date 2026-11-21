# Rounded Miniplayer Album Artwork Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to round the miniplayer album art's outer corners (top-left and bottom-left) to match the curvature of the miniplayer pill container, gated behind an in-app setting in Settings > Appearance.

**Architecture:** Add a new `roundMiniPlayerArtwork` boolean setting in `AppSettings` backed by `SharedPreferences`. In `MiniPlayer.kt` (and optionally `GlassNavBar.kt`), dynamically compute the artwork clipping and border shape: when enabled, top-start and bottom-start corners are curved with a concentric 20.dp radius (half the 40.dp artwork height, forming a smooth semicircle concentric with the outer 28.dp pill) while top-end and bottom-end remain square (0.dp); when disabled, retains the default uniform 8.dp rounded corner. Expose a toggle switch in `SettingsSheet.kt` under Appearance.

**Tech Stack:** Kotlin, Jetpack Compose (`RoundedCornerShape`, `Modifier.clip`), Android `SharedPreferences`, StateFlow.

**Spec:** User request to round miniplayer album art to match the miniplayer's border radius roundness, specifically only for the top-left and bottom-left borders, with an optional toggle in settings.

## Global Constraints

- **Scope:** Android mobile app only (`app/`).
- **Default State:** Setting defaults to `false` (standard uniform 8.dp corners).
- **Curvature Matching:** Miniplayer pill has height 56.dp (`ROW_PADDING_VERTICAL * 2 + 40.dp`) and radius 28.dp (`RoundedCornerShape(percent = 50)`). With 8.dp vertical padding, concentric radius is $28\text{dp} - 8\text{dp} = 20\text{dp}$, which equals 50% of the 40.dp artwork height, creating an identical semicircular profile.
- **RTL Support:** Use `topStart` and `bottomStart` in `RoundedCornerShape` so LTR rounds top-left and bottom-left, while RTL rounds top-right and bottom-right facing the outer pill edge.
- **Localization:** New string resources in `values/strings.xml` must have `translatable="false"` to prevent `LocaleStringsTest` failures across the 16 non-English locale resource sets until translations are supplied.

## Review Focus

1. **Visual Alignment:** Concentric curvature (20.dp radius on 40.dp height) must look seamless alongside the 28.dp pill curve without visual artifacts or clipping oddities.
2. **Right Edge Shape:** Top-end and bottom-end corners must remain flat (0.dp) when the setting is enabled so only the outer corners facing the pill curve are rounded.
3. **Thumbnail Border:** `Modifier.thumbnailBorder(shape)` must receive the exact same `Shape` as `Modifier.clip(shape)` so the border outline matches the artwork clip perfectly.
4. **Player Dock Sheet Transition:** `dock.reportMiniArt(coordinates, corner)` receives the active corner radius so expanding the player into full-screen sheet animates smoothly.
5. **Persistence & Lifecycle:** Setting toggles instantly via `StateFlow` across recompositions and persists across app restarts.

---

## Tasks

### Task 1: Add Setting and String Resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt`
- Test: `app/src/test/java/com/music/bitchord/LocaleStringsTest.kt`

- [ ] **Step 1:** Add strings to `app/src/main/res/values/strings.xml`:
  ```xml
  <string name="round_miniplayer_artwork" translatable="false">Round miniplayer album art</string>
  <string name="round_miniplayer_artwork_subtitle" translatable="false">Curves the outer corners of the album art to match the miniplayer pill</string>
  ```
- [ ] **Step 2:** In `AppSettings.kt`:
  - Define `val roundMiniPlayerArtwork = MutableStateFlow(false)`
  - Add key `private const val KEY_ROUND_MINIPLAYER_ARTWORK = "round_miniplayer_artwork"`
  - Add setter `fun setRoundMiniPlayerArtwork(value: Boolean)`
  - Initialize in `init(context)`: `roundMiniPlayerArtwork.value = prefs.getBoolean(KEY_ROUND_MINIPLAYER_ARTWORK, false)`
  - Handle in `resetAll(context)`: `roundMiniPlayerArtwork.value = false`
- [ ] **Step 3:** Run `.\gradlew.bat testDevDebugUnitTest --tests "com.music.bitchord.LocaleStringsTest"` to verify strings configuration passes.

---

### Task 2: Implement Artwork Shape Geometry & Unit Test

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt`
- Create: `app/src/test/java/com/music/bitchord/MiniPlayerArtworkShapeTest.kt`

- [ ] **Step 1:** In `MiniPlayer.kt`, define shape helper:
  ```kotlin
  internal fun miniPlayerArtShape(rounded: Boolean): RoundedCornerShape =
      if (rounded) {
          RoundedCornerShape(
              topStart = 20.dp,
              bottomStart = 20.dp,
              topEnd = 0.dp,
              bottomEnd = 0.dp,
          )
      } else {
          RoundedCornerShape(ART_CORNER)
      }
  ```
- [ ] **Step 2:** Write unit test in `app/src/test/java/com/music/bitchord/MiniPlayerArtworkShapeTest.kt`:
  - Test `miniPlayerArtShape(false)` yields 8.dp on all corners.
  - Test `miniPlayerArtShape(true)` yields 20.dp on `topStart`/`bottomStart` and 0.dp on `topEnd`/`bottomEnd`.
  - Test `AppSettings.roundMiniPlayerArtwork` initial value is `false`.
- [ ] **Step 3:** Run unit tests via `.\gradlew.bat testDevDebugUnitTest --tests "com.music.bitchord.MiniPlayerArtworkShapeTest"` and verify green.

---

### Task 3: Wire Artwork Shape to MiniPlayer and GlassNavBar

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt:412-421`
- Modify: `app/src/main/java/com/music/bitchord/ui/components/GlassNavBar.kt:355-365`

- [ ] **Step 1:** In `MiniPlayer.kt`:
  - Collect `val roundMiniPlayerArtwork by AppSettings.roundMiniPlayerArtwork.collectAsStateWithLifecycle()`
  - Compute `val artShape = miniPlayerArtShape(roundMiniPlayerArtwork)`
  - Apply `artShape` to `.clip(artShape)` and `.thumbnailBorder(artShape)`
  - Pass `if (roundMiniPlayerArtwork) 20.dp else ART_CORNER` to `.playerDockArt(dock, ...)`
- [ ] **Step 2:** In `GlassNavBar.kt` (`GlassNowPlaying`):
  - When not inline (`!isInline`), apply `miniPlayerArtShape(roundMiniPlayerArtwork)` so liquid glass miniplayer mode matches.

---

### Task 4: Add Toggle Row in Settings Appearance Section

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt`

- [ ] **Step 1:** In `SettingsSheet.kt`:
  - Collect `val roundMiniPlayerArtwork by AppSettings.roundMiniPlayerArtwork.collectAsStateWithLifecycle()`
  - In `Appearance` section (near `reduceDynamicBlur` / `liquidGlass`), add:
    ```kotlin
    val roundMiniPlayerArtTitle = stringResource(R.string.round_miniplayer_artwork)
    row(roundMiniPlayerArtTitle, "miniplayer", "artwork", "round") {
        SettingsRow(
            icon = Icons.Rounded.RoundedCorner,
            title = roundMiniPlayerArtTitle,
            subtitle = stringResource(R.string.round_miniplayer_artwork_subtitle),
            trailing = {
                Switch(
                    checked = roundMiniPlayerArtwork,
                    onCheckedChange = AppSettings::setRoundMiniPlayerArtwork,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            },
            onClick = { AppSettings.setRoundMiniPlayerArtwork(!roundMiniPlayerArtwork) },
        )
    }
    ```

---

### Task 5: Build & Test Verification

**Files:**
- None (verification step)

- [ ] **Step 1:** Run all unit tests:
  `.\gradlew.bat testDevDebugUnitTest`
- [ ] **Step 2:** Assemble dev debug build:
  `.\gradlew.bat assembleDevDebug`
