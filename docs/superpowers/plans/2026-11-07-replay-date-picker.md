# Replay Month and Year Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Replay viewers select a stored month or year with an iOS-style scrolling wheel, defaulting to current month/year.

**Architecture:** Keep `ReplayPeriod` as the tab selection and hold a selected `YearMonth` alongside it. Thread that month through summary generation and cache keys; month mode filters to that month, year mode filters to its year. Make the displayed Replay period label open an in-tree Compose wheel picker so its panel can use the app's Haze blur and opacity in the same window.

**Tech Stack:** Kotlin, Jetpack Compose Foundation snapping, Haze, JUnit 4.

**Spec:** User request in this conversation: tap month/year below Replay header to choose a past month or year using Apple-style scrolling wheels; default to current period; match the app's blur, opacity, and visual style.

## Global Constraints

- Android app only; leave `tv/`, `wearos/`, `web/`, `desktopApp/`, and `desktopUi/` untouched.
- Use existing Compose snapping and Haze dependencies; add no dependency.
- Keep current week and all-time behavior unchanged.
- Restrict selectable dates from earliest retained listening month through current month; default selection is current month.
- Keep existing Replay period tabs and story/chart behavior.
- Run only `:app:` Gradle tasks.

## Review Focus

- Selecting a past month queries that exact month, not current month.
- Selecting a year queries all retained monthly buckets from that year.
- Cache does not return a summary from another selected month/year.
- Empty or sparse months and years show existing empty states without crashing.
- Picker wheels initialize, snap, and dismiss consistently for narrow screens and reduced blur.

---

### Task 1: Query and label selected Replay dates

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/data/stats/ListeningStats.kt`
- Modify: `app/src/test/java/com/music/bitchord/data/stats/ReplayPeriodTest.kt`

**Interfaces:**
- `ListeningStats.summary(period: ReplayPeriod, selectedMonth: YearMonth = YearMonth.now())`
- `ReplayPeriod.covers(month: YearMonth, today: LocalDate, selectedMonth: YearMonth)`
- `ReplayPeriod.label(today: LocalDate, selectedMonth: YearMonth)`

- [ ] Add tests: month covers selected `YearMonth` only; year covers all months in selected year; current-date default covers current month/year; generated month/year labels use selected date.
- [ ] Run `:app:testDevDebugUnitTest --tests com.music.bitchord.data.stats.ReplayPeriodTest`; confirm failure before implementation.
- [ ] Add `selectedMonth` to summary cache identity and use it for month/year bucket filtering and labels; leave week range based on today and all-time unfiltered.
- [ ] Rerun the focused test; confirm pass.

### Task 2: Hold and propagate the chosen month

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/ui/replay/ReplayModel.kt`
- Modify: `app/src/main/java/com/music/bitchord/MainActivity.kt`
- Modify: `app/src/main/java/com/music/bitchord/ui/replay/ReplayModel.kt` (date label for story/share surfaces)

**Interfaces:**
- `ReplayState` exposes `selectedMonth: YearMonth` and `earliestMonth: YearMonth?`.
- `rememberReplayState` exposes a callback for changing the selected month while retaining its existing period-change callback.
- `ReplayScreen` receives selected month, earliest month, month callback, and the activity `HazeState`.

- [ ] Initialize saved selection to current `YearMonth`; derive earliest retained month on IO with the existing Replay state load.
- [ ] Fetch summaries when period or selected month changes; ensure artist-fact refresh uses latest selection.
- [ ] Preserve selected date in `ReplaySummary` labels used by stories and share poster; keep current week/all-time labels.
- [ ] Run focused Replay unit tests and app Kotlin compilation.

### Task 3: Add the styled scrolling-wheel picker

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/ui/replay/ReplayScreen.kt`
- Create: `app/src/main/java/com/music/bitchord/ui/replay/ReplayDatePicker.kt`
- Modify: `app/src/main/res/values/strings.xml` (only if new picker copy is needed)

**Interfaces:**
- `ReplayDatePicker(period, selectedMonth, earliestMonth, hazeState, dark, onSelect, onDismiss)`.
- Month mode shows month and year wheels; year mode shows year wheel.

- [ ] Make the displayed month/year label clickable only in month/year modes and open the picker as an in-tree overlay.
- [ ] Build centered snapping wheels with current value centered, selection highlight, bounded values, and confirmation/cancel actions.
- [ ] Style the panel with the existing app frost: rounded surface, `menuFrost(surface)` for 24.dp blur and 80% tint, theme text/border, and opaque surface when `reduceDynamicBlur` is enabled.
- [ ] Add dismiss scrim, BackHandler, dialog semantics, and wheel labels for TalkBack.
- [ ] Verify the selected date updates the label and existing cards, charts, stories, and share content.

### Task 4: Verify the Android app path

**Files:**
- No additional production files expected.

- [ ] Run `:app:testDevDebugUnitTest --no-configuration-cache -PbitchordAbi=arm64-v8a`.
- [ ] Run `:app:assembleDevDebug --no-configuration-cache -PbitchordAbi=arm64-v8a`.
- [ ] Review `git diff --check` and confirm no non-`app/` product code changed.
- [ ] On device, open Replay; switch among current/past month and year; verify wheel bounds, empty selection, cancel, blur, and reduced-blur fallback.
