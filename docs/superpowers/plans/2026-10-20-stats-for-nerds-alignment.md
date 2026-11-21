# Stats for Nerds Waveform Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface applied waveform alignment compensation (`offsetMs`) in Stats for Nerds (`SleeveNerdStats` and `AudioPipelineDialog`) when a stream quality upgrade is aligned.

**Architecture:** Extend `NerdStats.Snapshot` with an `alignmentOffsetMs: Long?` field. When `PlaybackService` executes a stream quality upgrade and applies cross-correlation waveform alignment (`StreamAudioAligner`), store the measured `offsetMs` for the active track and include it in `publishNerdStats()`. In UI, render the alignment compensation in `SleeveNerdStats` (e.g. `Aligned (+120 ms)`) and in `AudioPipelineDialog`'s source stage card.

**Tech Stack:** Kotlin, Jetpack Compose, Media3, StateFlow, Coroutines.

**Spec:** `docs/superpowers/plans/2026-10-20-source-waveform-alignment.md`

## Global Constraints
- Android mobile app only (`app/`, `shared/`, `sharedUi/`). Ignore `tv/`, `wearos/`, `desktopApp/`, `web/`.
- Strictly GPLv3 compatible.
- Zero dual-player crossfade dependencies; completely decoupled from unmerged branch `feat/seamless-source-switch-only` (PR #932).
- Prefix all shell commands with `rtk`.
- Every added string in `values/strings.xml` must be localized or present in all 18 `values-*/strings.xml` catalogues to pass `LocaleStringsTest`.
- Atomic commits per task.

## Review Focus
1. Null/Zero offset handling: When alignment is 0 ms or no upgrade has taken place, Stats for Nerds must omit the alignment badge or display standard stream stats without clutter.
2. Track boundary isolation: Switching or skipping tracks must cleanly reset `alignmentOffsetMs` in `NerdStats` so stale alignment from a prior track never displays on a new track.
3. Rapid repeated upgrades: If an upgrade fails or is abandoned (e.g. queue moved, crossfade active), the alignment offset must not be recorded or displayed.
4. Locale test integrity: Any new strings must be added across all 18 locales to satisfy `LocaleStringsTest`.
5. Non-intrusive UI layout: The sleeve text in `SleeveNerdStats` must maintain single-line ellipsis truncation on compact phone screens.

---

### Task 1: Telemetry Data Model (`NerdStats.Snapshot`)

**Files:**
- Modify: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt`
- Test: `app/src/test/java/com/music/bitchord/playback/audio/NerdStatsRegressionTest.kt`

- [ ] Add `val alignmentOffsetMs: Long? = null` to `NerdStats.Snapshot`.
- [ ] Add tracking helper in `NerdStats` object: `recordAlignmentOffset(mediaId: String?, offsetMs: Long?)` and clear on `onTrackTransition(mediaId: String?)`.
- [ ] Write unit test in `NerdStatsRegressionTest` asserting that `alignmentOffsetMs` propagates through `NerdStats.publish` and is cleared on track transition.
- [ ] Run test: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.audio.NerdStatsRegressionTest"`.
- [ ] Commit: `feat(telemetry): add alignmentOffsetMs to NerdStats snapshot`.

---

### Task 2: PlaybackService Telemetry Publication

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- Test: `app/src/test/java/com/music/bitchord/playback/StreamAlignmentUpgradeTest.kt`

- [ ] In `PlaybackService.kt`, after `alignJob?.await()` returns `offsetMs` and the swap is successfully applied to the player, record `NerdStats.recordAlignmentOffset(mediaId, offsetMs)`.
- [ ] In `PlaybackService.publishNerdStats()`, read `alignmentOffsetMs = NerdStats.getAlignmentOffset(mediaId)` and pass to `NerdStats.Snapshot`.
- [ ] Clear recorded offset when `NerdStats.clearDeclared(mediaId)` or track transition occurs.
- [ ] In `StreamAlignmentUpgradeTest.kt`, add unit test verifying that `NerdStats.getAlignmentOffset(mediaId)` reflects the applied `offsetMs` after upgrade.
- [ ] Run tests: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.StreamAlignmentUpgradeTest"`.
- [ ] Commit: `feat(playback): record and publish stream alignment offset in NerdStats`.

---

### Task 3: UI Rendering in SleeveNerdStats & AudioPipelineDialog

**Files:**
- Modify: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt`
- Modify: `app/src/main/java/com/music/bitchord/ui/components/AudioPipelineDialog.kt`
- Modify: `app/src/main/res/values/strings.xml` and all 18 `app/src/main/res/values-*/strings.xml`
- Test: `app/src/test/java/com/music/bitchord/LocaleStringsTest.kt`

- [ ] In `values/strings.xml` and 18 locale files, add string resources:
  - `nerd_stats_aligned`: `Aligned (%+d ms)`
  - `pipeline_alignment`: `Waveform alignment`
- [ ] In `PlayerControls.kt` (`NerdStats.Snapshot.describe()`), append formatted alignment string when `alignmentOffsetMs != null && alignmentOffsetMs != 0L`.
- [ ] In `AudioPipelineDialog.kt`, render an "Alignment" metric row in the Source Stage card if `nerdStats?.alignmentOffsetMs` is present.
- [ ] Run tests: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.LocaleStringsTest"`.
- [ ] Commit: `feat(ui): display waveform alignment compensation in stats for nerds`.

---

### Task 4: End-to-End Verification

- [ ] Run full unit test suite: `rtk .\gradlew.bat :app:testDevDebugUnitTest`.
- [ ] Assemble dev debug APK: `rtk .\gradlew.bat assembleDevDebug`.
- [ ] Install on target device: `rtk adb -s 192.168.0.191:5555 install -r app/build/outputs/apk/dev/debug/app-dev-arm64-v8a-debug.apk`.
- [ ] Verify Stats for Nerds overlay displays alignment offset on stream upgrade.
