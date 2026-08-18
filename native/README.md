# Native audio effects development

This tree begins the native Audio Unit implementation while preserving an exit
from JUCE. `reverb-core` contains product DSP and has no JUCE dependency. The
temporary adapter under `adapters/juce-plugin` translates Audio Unit buffers,
parameters, state, and the bundled factory WAV into core-owned types.

`echo-core` is a separate JUCE-free real-time delay engine. It owns fractional
delay, feedback, repeat filtering, saturation, modulation, stereo routing, and
neutral plus initial tape/BBD-style behavioral defaults. The temporary Echo
adapter builds a separate AU; it does not use convolution. `apps/mechana-effects`
builds a live-input macOS application with separate Reverb and Echo tabs.
Analog Memory combines gentler recursive bandwidth loss with a stronger two-pole
output-reconstruction filter, asymmetric soft limiting, deterministic composite clock
motion, and a mono-centered normal wet path. Modulation rate and depth changes are smoothed.

`audio-core` is the shared, JUCE-free foundation for real-time effects. It
provides parameter smoothing, gain/equal-power mix and peak helpers, reusable
DC-blocking and one-pole filtering, clipping/waveshaping primitives, and a
streaming 2x linear-phase FIR oversampler. Echo consumes shared nonlinear
primitives; Octave Fuzz uses the smoothing, filters, nonlinearities, and
oversampler. Reverb remains unchanged while reusable pieces are migrated
incrementally. Leslie, chorus, and flanger are intended next consumers.

`octave-fuzz-core` is inspired by classic octave-fuzz topology, not a
circuit-authentic or trademarked-product emulation. Its path is input gain,
2x oversampled asymmetric fuzz, full-wave octave generation and DC rejection,
octave blend, tone shaping, and bounded output saturation. Drive, Tone, Level,
and Octave are smoothed per sample. Mono and stereo channels are independent.
The FIR path has fixed eight-sample base-rate latency, reported by the AU.

`leslie-core` is a separate JUCE-free moving-speaker engine. It models a
crossed-over treble horn and bass drum with independent mechanical inertia,
Doppler and directional amplitude motion, stereo microphone geometry, drive,
and smoothed real-time controls. Its temporary adapter builds a separate AU, and
the combined application adds a Leslie tab. Only the selected tab processes audio.

## Build on macOS

```shell
cmake -S native -B native/build -G Xcode
cmake --build native/build --config Release
ctest --test-dir native/build -C Release --output-on-failure
```

Native apps, AU components, and benchmarks target macOS 12.0 or later by default,
including Intel Monterey systems. Callers may override
`CMAKE_OSX_DEPLOYMENT_TARGET` explicitly when configuring a specialized build.

For the JUCE-free correctness suite followed by standardized performance output:

```shell
native/verify.sh
```

The performance suite reports preparation separately, then average,
95th-percentile, and maximum milliseconds per 128-sample block with each value
expressed as a percentage of the sample rate's callback deadline. Every effect is
measured at 44.1, 48, 88.2, and 96 kHz. Results are informational rather than a
machine-specific pass/fail gate. On Apple Silicon, verification also runs the
x86_64 benchmark under Rosetta; Intel hosts run x86_64 natively. The distributable
benchmark suite is built with `packaging/macos/build-effect-benchmarks.sh` and
automatically discovers each registered native effect benchmark.

`packaging/macos/package-native-effects.sh` packages architecture-specific ZIPs
under `packaging/macos/target/arm64/` and `packaging/macos/target/x86_64/`
for all four separate AU components and the benchmark suite. The native live-input
host remains a development target and is not a release artifact. The full
file-oriented Effects app is built separately with
`packaging/macos/build-reverb-app.sh` and uses the same architecture folders.
Reverb, Echo, Leslie, and Octave Fuzz results remain distinct at all four standard rates.
Development packaging uses ad-hoc signatures by default. Release packaging sets
`MACOS_SIGNING_IDENTITY` to a Developer ID Application identity, which enables
hardened runtime and secure timestamps, and may limit packaging to `arm64` or
`x86_64`. After the one-time `notarytool` Keychain profile setup, run
`packaging/macos/notarize-native-effects.sh <architecture>` to submit all five
archives, staple supported bundles, rebuild their ZIPs, and validate the result.

The build downloads the pinned JUCE 9.0.0 source into the ignored build tree.
JUCE is not vendored and no JUCE type crosses into `reverb-core`. The AU component
is emitted beneath `native/build/adapters/juce-plugin/MechanaReverb_artefacts`.
It is not copied into the user's Audio Plug-Ins directory automatically.

JUCE 9 is AGPLv3/commercial dual-licensed. This internal proof of concept does
not establish commercial distribution rights; acquire the applicable JUCE licence
before distributing a closed-source binary containing the adapter.

## Current milestone

- mono/stereo effect layouts
- allocation-free processing after preparation
- 32-bit float real/half-spectrum FFT convolution with a 128-sample reported latency
- non-uniform 128/512/2048-sample partitions for efficient long responses
- Apple Accelerate/vDSP FFT acceleration on Intel and Apple Silicon, with a
  portable radix-2 fallback
- six bundled factory responses plus custom WAV import
- automatable Wet, Dry, Pre-delay, Early, Late, Attack, Decay, wet Low-cut,
  wet High-cut, and Bypass parameters
- reset controls for mix/timing, captured-response shaping, and EQ
- background IR sample-rate conversion, response shaping, calibration, prepared
  response caching, and crossfaded engine exchange outside the real-time callback
- Logic project state save/restore
- JUCE-free core regression test

Durable user-profile library management, parameter smoothing, automatic peak
protection parity, Universal packaging, signing, and notarization remain
follow-up work. IR-shaping automation is intentionally applied asynchronously
at block-safe boundaries rather than sample-accurately in this proof of concept.
