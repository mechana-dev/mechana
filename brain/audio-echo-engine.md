# Native echo engine

Last verified: 2026-08-16

## Implemented core

`native/echo-core` is a product-owned C++20 DSP library with no JUCE or convolution
dependency. It uses a cubic-interpolated delay line and a feedback path containing
optional low-cut, high-cut, saturation, and sinusoidal delay modulation. The engine
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

The initial models need listening calibration before product use. A separate JUCE
Audio Unit adapter exposes the two colored models, automatable controls, host-state
persistence, and a functional generic editor. The `Mechana Effects` live-input app
hosts Reverb and Echo on separate tabs. A standard benchmark target exercises the
production Echo engine at 44.1, 48, 88.2, and 96 kHz and is packaged for arm64 and
x86_64. A custom Echo editor and Java/Mechana worker plugin remain follow-up work.

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
