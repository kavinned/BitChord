# Aligned Deck Handover Level Timing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the aligned-deck handover's headroom trim on the speaker clock, so 32-bit float output hands over without a level bump, clipping, or delayed dip.

**Architecture:** Extract the equal-power handover gains into one internal helper that folds the pair trim into each deck's gain. Both aligned handovers assign those gains to `player.volume`, which Media3 applies through `AudioTrack.setVolume` at the speaker, instead of also driving `LoudnessProcessor.setBlendTrim`, which acts several seconds earlier on a float route.

**Tech Stack:** Kotlin, Android Media3 ExoPlayer, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-19-aligned-handover-level-timing-design.md`

## Global Constraints

- Android app only (`app/`). Never touch `tv/`, `wearos/`, `web/`, `desktopApp/`, `desktopUi/`.
- GPLv3-compatible only. No new dependencies.
- JDK 17; existing `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`.
- Prefix every shell command with `rtk`. Windows commands are shown; use `./gradlew` on Linux/macOS.
- One Gradle build at a time. Pass `-PbitchordAbi=arm64-v8a` to dev assemble/install tasks.
- Surgical diffs. Do not change fade durations, alignment offsets, seek/sync logic, output negotiation, `AudioDecoder`, Automix, or bit-perfect paths.
- Keep both existing `setBlendTrim(1f)` resets in `finally`; they are defensive no-ops after this change.
- One atomic commit per task, with the commit message given in the task.

## Review Focus

- Progress outside `[0, 1]` — a late fade tick may overshoot. Expected: clamped gains, never negative or above unity. Pinned by Task 1's clamp test.
- Cancellation or an exception mid-fade — the DSP trim must not be left below 1. Expected: the existing `finally` resets stay in both paths. Checked by inspection in Tasks 2 and 3.
- Both aligned handovers — source-quality upgrade and version swap share the defect. Expected: no `swapTrim` remains after Task 3.
- 16-bit route — trim now attenuates after quantization and the per-track limiter sees untrimmed peaks. Expected: no audible change during the 300-550 ms overlap. Checked by ear in Task 4.
- Float route — the reported failure. Expected: no bump, clip, or delayed dip at handover, and unchanged sync telemetry. Checked on device in Task 4.

---

### Task 1: Speaker-Timed Handover Gains

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` (top-level, after `StandbySyncLead`, ends around line 282)
- Create: `app/src/test/java/com/music/bitchord/playback/AlignedHandoverGainsTest.kt`

**Interfaces:**
- Produces: `internal fun alignedHandoverGains(progress: Float): Pair<Float, Float>` returning `(incoming, outgoing)`, each in `[0, 1]`, both clamped for `progress` outside `[0, 1]`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/music/bitchord/playback/AlignedHandoverGainsTest.kt`:

```kotlin
package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignedHandoverGainsTest {

    @Test
    fun `start is outgoing at unity and incoming at silence`() {
        val (incoming, outgoing) = alignedHandoverGains(0f)
        assertEquals(0f, incoming, 1e-6f)
        assertEquals(1f, outgoing, 1e-6f)
    }

    @Test
    fun `end is incoming at unity and outgoing at silence`() {
        val (incoming, outgoing) = alignedHandoverGains(1f)
        assertEquals(1f, incoming, 1e-6f)
        assertEquals(0f, outgoing, 1e-6f)
    }

    @Test
    fun `midpoint is equal and trimmed below a raw equal-power sum`() {
        val (incoming, outgoing) = alignedHandoverGains(0.5f)
        assertEquals(0.5946f, incoming, 1e-3f)
        assertEquals(incoming, outgoing, 1e-6f)
        assertTrue(incoming + outgoing <= 1.1893f)
    }

    @Test
    fun `progress outside zero to one clamps`() {
        assertEquals(alignedHandoverGains(0f), alignedHandoverGains(-0.5f))
        assertEquals(alignedHandoverGains(1f), alignedHandoverGains(1.5f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.AlignedHandoverGainsTest"`

Expected: FAIL with `unresolved reference: alignedHandoverGains`.

- [ ] **Step 3: Implement the helper**

In `PlaybackService.kt`, immediately after the `StandbySyncLead` class and before `ACTION_QUEUE_DRAG`, add:

```kotlin
/**
 * Level of each deck through an aligned handover, equal-power and headroom
 * trimmed: `(incoming, outgoing)`.
 *
 * The pair trim belongs on the faders, not on [LoudnessProcessor]'s
 * [LoudnessProcessor.setBlendTrim]. Faders are applied by the audio sink at
 * the speaker; the DSP gain is applied where the sink is writing, which on a
 * float route is seconds ahead of the speaker. Driving both with one progress
 * is what made the float handover clip.
 */
internal fun alignedHandoverGains(progress: Float): Pair<Float, Float> {
    val p = progress.coerceIn(0f, 1f)
    val incoming = sin(p * (PI / 2.0)).toFloat()
    val outgoing = cos(p * (PI / 2.0)).toFloat()
    val trim = 1f / sqrt((incoming + outgoing).coerceAtLeast(1f))
    return (incoming * trim) to (outgoing * trim)
}
```

`sin`, `cos`, `sqrt`, and `PI` are already imported at the top of the file.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.AlignedHandoverGainsTest"`

Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
rtk git add app/src/main/java/com/music/bitchord/playback/PlaybackService.kt \
  app/src/test/java/com/music/bitchord/playback/AlignedHandoverGainsTest.kt
rtk git commit -m "fix(playback): add speaker-timed gains for aligned handovers"
```

---

### Task 2: Source-Quality Upgrade Handover

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` (the `if (willPlay)` fade block in `swapToAlignedStream`, around lines 5276-5311)

**Interfaces:**
- Consumes: `alignedHandoverGains(progress: Float): Pair<Float, Float>` from Task 1.

- [ ] **Step 1: Baseline**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.AlignedHandoverGainsTest"`

Expected: PASS.

- [ ] **Step 2: Replace the fade-level block**

In `swapToAlignedStream`, find the loop that starts with
`val fadeStart = SystemClock.elapsedRealtime()` and contains the `sin`/`cos`
gain calculation. Replace exactly:

```kotlin
                        val inGain = sin(progress * (PI / 2.0)).toFloat()
                        val outGain = cos(progress * (PI / 2.0)).toFloat()
                        standbyPlayer.volume = inGain
                        activePlayer.volume = outGain
                        val swapTrim = 1f / sqrt((inGain + outGain).coerceAtLeast(1f))
                        loudnessA.setBlendTrim(swapTrim)
                        loudnessB.setBlendTrim(swapTrim)
```

with:

```kotlin
                        val (inGain, outGain) = alignedHandoverGains(progress)
                        standbyPlayer.volume = inGain
                        activePlayer.volume = outGain
```

Leave the surrounding comment, the loop's `progress` calculation, the
`progress >= 1f` break, and the `finally` block untouched. The `finally` must
still contain both `setBlendTrim(1f)` calls.

- [ ] **Step 3: Verify the call site**

Run: `rtk proxy grep -n "swapTrim\|setBlendTrim" app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`

Expected:
- exactly one `swapTrim` line remains, in the version-swap path (Task 3 removes it);
- four `setBlendTrim(1f)` lines remain, two per path;
- no `alignedHandoverGains` inside `swapToAlignedStream` is still computing raw gains.

- [ ] **Step 4: Run the helper test**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.AlignedHandoverGainsTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add app/src/main/java/com/music/bitchord/playback/PlaybackService.kt
rtk git commit -m "fix(playback): trim the source-upgrade handover on the speaker clock"
```

---

### Task 3: Version-Swap Handover

**Files:**
- Modify: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` (the `try { while (isActive) { ... } }` fade block in the version-swap coroutine, around lines 2452-2490)

**Interfaces:**
- Consumes: `alignedHandoverGains(progress: Float): Pair<Float, Float>` from Task 1.

- [ ] **Step 1: Baseline**

Run: `rtk proxy grep -n "swapTrim" app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`

Expected: exactly one match, in the version-swap fade.

- [ ] **Step 2: Replace the fade-level block**

In the version-swap coroutine's fade loop, replace exactly:

```kotlin
                    val inGain = sin(progress * (PI / 2.0)).toFloat()
                    val outGain = cos(progress * (PI / 2.0)).toFloat()
                    standbyPlayer.volume = inGain
                    activePlayer.volume = outGain
                    // The same headroom a crossfade takes, and for the same
                    // reason — see [LoudnessProcessor]. More so here: two
                    // versions of one song line up peak for peak.
                    val swapTrim = 1f / sqrt((inGain + outGain).coerceAtLeast(1f))
                    loudnessA.setBlendTrim(swapTrim)
                    loudnessB.setBlendTrim(swapTrim)
```

with:

```kotlin
                    // The same headroom a crossfade takes, and for the same
                    // reason — see [LoudnessProcessor]. More so here: two
                    // versions of one song line up peak for peak. Folded into
                    // the faders so it lands with the audible fade on a float
                    // route, where the DSP chain is seconds ahead.
                    val (inGain, outGain) = alignedHandoverGains(progress)
                    standbyPlayer.volume = inGain
                    activePlayer.volume = outGain
```

Leave the rest of the loop and the `finally` block untouched. The `finally`
must still contain both `setBlendTrim(1f)` calls.

- [ ] **Step 3: Verify no call site remains**

Run: `rtk proxy grep -n "swapTrim" app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`

Expected: no output.

Run: `rtk proxy grep -n "setBlendTrim(1f)" app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`

Expected: exactly four matches (both `finally` blocks).

- [ ] **Step 4: Run the tests**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest --tests "com.music.bitchord.playback.AlignedHandoverGainsTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add app/src/main/java/com/music/bitchord/playback/PlaybackService.kt
rtk git commit -m "fix(playback): trim the version-swap handover on the speaker clock"
```

---

### Task 4: Verification on Device

**Files:** none.

- [ ] **Step 1: Full unit suite**

Run: `rtk .\gradlew.bat :app:testDevDebugUnitTest`

Expected: BUILD SUCCESSFUL, no new failures. Note any pre-existing failure explicitly.

- [ ] **Step 2: Build and install the dev debug app**

Run: `rtk .\gradlew.bat :app:installDevDebug -PbitchordAbi=arm64-v8a`

Expected: BUILD SUCCESSFUL and `Installed on 1 device`.

Launch by the dev package id, not the release app:

Run: `rtk adb shell am start -n com.dev.bitchord/com.music.bitchord.LauncherDefault`

- [ ] **Step 3: 16-bit regression check**

Select 16-bit PCM output. Enable **Smart audio alignment** and **Align upgraded audio sources**. Play a lossless-eligible track on a route that will produce a quality upgrade. Confirm by ear:
- no level bump or clipping at the handover;
- no dip after the handover;
- deck sync/alignment badge still appears as before.

Run: `rtk adb logcat -c` before the track and `rtk adb logcat -s BitChord:V` during it. Expected: `live output switch: PCM_16BIT` when the mode is changed, an alignment line without `upgrade abandoned`, and `upgrade standby sync #` drift within the existing thresholds.

- [ ] **Step 4: 32-bit float check**

Switch to **32-bit float** on a route that advertises `PCM_FLOAT` (USB DAC or hi-res Bluetooth; the phone speaker policy stays 16-bit). Repeat the quality-upgrade listen and then trigger an audio/video version switch.

Expected log lines:
- `live output switch: PCM_FLOAT`;
- `waveform alignment applied on standby deck: offset=...`;
- no `upgrade abandoned`, no `decks are ...ms apart at handover`.

Expected by ear:
- no bump, click, or clipping at either handover;
- no level dip seconds later;
- alignment and drift telemetry matches the pre-fix values.

- [ ] **Step 5: Record the result**

Report pass/fail per route and attach the relevant logcat lines. No commit; there are no code changes in this task.
