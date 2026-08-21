# Native echo engine

Last verified: 2026-08-18

## Implemented core

`native/echo-core` is a product-owned C++20 DSP library with no JUCE or convolution
dependency. It uses a cubic-interpolated delay line and a feedback path containing
optional low-cut, high-cut, saturation, and delay-time modulation. Analog Memory uses
a deterministic composite clock-motion model: a smoothed low-frequency oscillator,
subtle flutter, and interpolated pseudo-random wander. The engine
supports mono/stereo processing, optional ping-pong routing, wet/dry gain, bypass,
and click-smoothed delay-time changes. It allocates its buffers during `prepare`,
performs no allocation in `process`, and reports zero added latency.

`Neutral Echo` disables coloration and modulation. `Vintage Tape` and `Analog
Memory` are conservative behavioral starting points informed by the documented
features of well-known tape and bucket-brigade delays. They intentionally use
generic product-facing names. They are not hardware captures and do not claim
component-level or unit-specific equivalence.

## Model boundary

Delay storage, interpolation, feedback, routing, and parameter smoothing remain
common to every model. A model supplies defaults and may later supply reusable
repeat-path processors for measured frequency response, level-dependent
nonlinearity, noise, or non-periodic modulation. A future captured convolution may
color the input or repeat path, but it must remain optional; feedback timing and
time-varying behavior belong to the echo engine.

The Analog Memory feedback path is calibrated as Mechana's own analog-delay model,
using professional listening and private reference renders as qualitative evidence,
not as a Soundtoys, EchoBoy, or hardware clone. Feedback is a shaped musical control:
36% maps to an internal loop coefficient near 0.419 (about -7.6 dB per unfiltered
generation), with a strict sub-unity ceiling. Filtering and unity-small-signal-gain
nonlinearity are inside the loop, so every generation becomes progressively darker
and softer. Its recursive order is modulated delay read, user high-cut, user low-cut,
unity-slope asymmetric soft limiting, feedback gain, and delay write. A stronger
two-pole BBD output-reconstruction stage then voices each audible repeat without
making every later generation exponentially darker; it remains tied to High Cut, and
disabling High Cut disables both paths. Analog Memory centers the normal wet path to
match its mono hardware inspiration while dry stereo and explicit ping-pong remain
available. Noise and clock feedthrough are not currently synthesized.
Modulation depth and rate are smoothed, and the fixed seed makes reset renders
repeatable. A shared linear Mix primitive supplies `dry = 1 - mix`, `wet = mix` with
10 ms smoothing; output peak protection remains a separate concern.

A separate JUCE
Audio Unit adapter exposes the two colored models, automatable controls, host-state
persistence, and a custom editor. Feedback, Mix, and modulation depth are presented
as percentages. Legacy Wet/Dry IDs remain non-automatable metadata parameters so old
Logic state can migrate to `mix = wet / (wet + dry)` without shifting prior parameter
indices. A single linear control cannot preserve legacy makeup gain exactly. The AU
conservatively reports decay through
-100 dB amplitude plus one safety repeat, capped at 30 seconds, so hosts that honor
effect tails do not stop an otherwise orderly quiet decay at an audible boundary.
The shipping file-oriented `Mechana Effects` app mirrors the calibrated feedback,
repeat-path coloration, tail calculation, and single percentage-based Mix semantics.
Legacy standalone Wet/Dry preferences migrate to their normalized ratio. Its Java
regression tests lock the shared calibration constants to prevent silent release
drift. A standard benchmark target exercises the
production Echo engine at 44.1, 48, 88.2, and 96 kHz and is packaged for arm64 and
x86_64. A Java/Mechana worker plugin remains follow-up work.

Private Scott-approved Hendrix/Watchtower files are local calibration material only
and are never committed or redistributed. See `docs/echo-calibration.md`.

## Future hardware characterization

A single sweep cannot identify a nonlinear, time-varying feedback device. If real
hardware becomes available, capture 48 kHz/24-bit mono WAV with automatic gain,
noise reduction, and other processing disabled. Record interface loopback for gain
and latency reference, then collect:

1. A 100%-wet, minimum-feedback logarithmic sweep at several representative delay
   times. For a bucket-brigade unit, include short, middle, and maximum delay because
   bandwidth commonly changes with clock rate.
2. Minimum-feedback impulses or short full-band noise bursts at the same delay
   times to verify first-repeat timing and frequency response.
3. One impulse at several feedback settings, recorded through the complete decay,
   to estimate repeat-to-repeat gain and tonal change. Do not sweep with substantial
   feedback.
4. Sustained sine and multitone recordings with modulation off and at several
   rate/depth settings. Tape characterization should include at least 60 seconds to
   observe slow and non-periodic wow/flutter.
5. Identical bursts at stepped input levels from approximately -30 to -6 dBFS to
   estimate level-dependent saturation without clipping the interface converters.
6. At least 30 seconds of connected no-signal output for noise-floor analysis.

Record exact device settings and cabling, photograph the controls, leave generous
tail time, and change only one control per series. These recordings support model
fitting; they are not imported directly as reverb-style IR profiles.
