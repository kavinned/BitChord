# Aligned Deck Handover Level Timing

**Date:** 2026-11-19
**Status:** Proposed
**Scope:** Android app only (`app/`)

## Problem

On a 32-bit float output route, the aligned-deck handovers are not clean: the
source-quality upgrade and the audio/video version swap both produce a level
bump or clipping at the moment the standby deck takes over. The same swaps on
16-bit PCM output are clean.

Deck position alignment is not the cause. Both players' `currentPosition`
clocks are speaker-time in either mode, and the sync/seek logic does not read
the output encoding.

## Root cause

1. Float output enables AudioTrack playback parameters:
   `PlaybackService.kt:7476-7478`, `setEnableAudioTrackPlaybackParams(true)`.
2. Media3's `DefaultAudioTrackBufferSizeProvider` sizes the AudioTrack for the
   maximum playback speed it may be asked to run (8x):
   `500 ms × 8 = ~4 s`. The sink therefore accepts about four seconds of audio
   ahead of what is heard.
3. `PrecisionAudioSink`'s DSP chain writes into that buffer. Its
   `getCurrentPositionUs` exposes the gap as `leadUs`
   (`PrecisionAudioSink.kt:496-505`).
4. Two handover controls are driven together, but act at different times:
   - `player.volume` reaches `AudioTrack.setVolume` and is heard immediately at
     the speaker;
   - `LoudnessProcessor.setBlendTrim` is a DSP gain applied where the sink is
     writing, which on a float route is seconds before it is heard
     (`LoudnessProcessor.kt:171-188`).
5. Both aligned handovers drive the faders and the DSP trim with the same
   progress:
   - source-quality upgrade: `PlaybackService.kt:5284-5303`
   - version swap: `PlaybackService.kt:2455-2474`

Automix already accounts for this: its crossfade aims DSP controls at the blend
position the written audio will reach, using each deck's lead
(`CrossfadeController.kt:1830-1843`, `TransitionFilterProcessor.kt:59-67`).
The aligned-deck swaps do not.

At the audible handover the DSP trim is therefore absent: two aligned decks of
the same content sum at up to `1.414×`, which clips on loud masters. The trim
curve is heard about four seconds later as an unrelated level dip.

On 16-bit output the AudioTrack playback-parameter path is off, the buffer is
roughly `500 ms` or less, and the timing error is small enough to be inaudible.
That is why 16-bit is reported as fine.

## Goals

- Apply the pair's headroom trim at the same instant as the audible fade on
  both output routes.
- Use one tested formula for both aligned handovers.
- Leave the deck synchronization, seek offsets, fade lengths, and output
  negotiation unchanged.

## Non-goals

- Any claim of an audible difference between 16-bit PCM and 32-bit float
  output.
- The 16-bit-only assumption in `AudioDecoder` (`AudioDecoder.kt:291-345`),
  which affects waveform analysis of float or 24-bit source files, not the
  compressed streams used by these swaps.
- Automix, whose DSP lead compensation is already correct.
- Bit-perfect output, which bypasses these fades and the DSP chain.
- Changing the fade durations (`300 ms` source upgrade, `550 ms` version swap).

## Requirements

- R1: The gains must preserve the existing equal-power shape and headroom:
  `incoming = sin(p·π/2) · trim`, `outgoing = cos(p·π/2) · trim`,
  `trim = 1 / √(incomingRaw + outgoingRaw)`.
- R2: The trim must be folded into the player volume, the speaker-timed
  control. The DSP blend trim must not be driven by these swaps.
- R3: Progress outside `[0, 1]` must clamp.
- R4: Both handover paths must retain their `setBlendTrim(1f)` reset in
  `finally` as a defensive no-op.
- R5: Both call sites must use the same helper; no route-dependent branch.

## Design

### Helper

Add a top-level internal function in `PlaybackService.kt`:

```kotlin
internal fun alignedHandoverGains(progress: Float): Pair<Float, Float> {
    val p = progress.coerceIn(0f, 1f)
    val incoming = sin(p * (PI / 2.0)).toFloat()
    val outgoing = cos(p * (PI / 2.0)).toFloat()
    val trim = 1f / sqrt((incoming + outgoing).coerceAtLeast(1f))
    return (incoming * trim) to (outgoing * trim)
}
```

The pair is `(incoming, outgoing)`. The trimmed coherent sum is
`√(sin + cos)`, bounded by `1.0 .. 1.1892` (about `+1.5 dB` at the midpoint),
which matches the existing trim intent.

### Call sites

Replace, in both fade loops:

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

The values written to the players are mathematically the same levels as
before; only the instant at which they are applied changes, from write-time to
speaker-time. The existing `finally` reset stays.

## Alternatives considered

- **Delay the DSP trim by each sink's `leadUs`.** Correct, but it needs a
  second, time-shifted control schedule and per-sink state. The Automix
  controller owns that machinery; the swaps do not need a parallel copy.
  Rejected as more code for the same result.
- **Clamp or limit after the sum.** There is no post-sum stage inside the app;
  AudioFlinger and the HAL clip. Rejected.
- **Fix only the float route.** The same wrong pattern exists in both routes;
  one formula removes the route branch and keeps 16-bit behavior consistent.
  Rejected.

## Testing

- Unit: the helper's endpoints, midpoint, headroom bound, symmetry, and
  progress clamping.
- Build and unit suite: `:app:testDevDebugUnitTest`.
- Device:
  - 16-bit PCM route: aligned source upgrade and version swap remain clean.
  - 32-bit float route (`PCM_FLOAT` in the log): no level bump, clipping, or
    delayed dip at the handover.
  - No alignment or drift telemetry changes.

## Risks and caveats

- **16-bit attenuation after quantization.** The trim now scales the 16-bit
  output at AudioTrack instead of the float DSP samples. During the
  300-550 ms overlap the extra rounding is sub-LSB and masked by two decks
  summing.
- **Limiter sees untrimmed peaks.** The per-track limiter no longer receives
  the trim on its input, so it may engage slightly harder during the overlap.
  Output is still trimmed afterwards; the difference is confined to the fade
  and is checked by ear on the 16-bit route.
- **OEM output implementations.** Media3 applies volume through the audio
  output (`AudioTrack.setVolume`), which the mixer applies to buffered audio.
  If a specific device attenuates earlier, the change is no worse than today,
  not better; the device step verifies it.
- **Not hardware-verified yet.** This spec is based on a code trace and the
  Media3 1.11 buffer/volume behavior. Acceptance requires the device step.

## Acceptance

- `AlignedHandoverGainsTest` passes.
- Full `:app:testDevDebugUnitTest` passes.
- On float output, a quality upgrade and a version swap hand over without a
  level bump, clip, or later dip; deck sync telemetry is unchanged.
- On 16-bit output, both handovers remain at least as clean as before.
