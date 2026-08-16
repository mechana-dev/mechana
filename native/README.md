# Native Reverb development

This tree begins the native Audio Unit implementation while preserving an exit
from JUCE. `reverb-core` contains product DSP and has no JUCE dependency. The
temporary adapter under `adapters/juce-plugin` translates Audio Unit buffers,
parameters, state, and the bundled factory WAV into core-owned types.

`echo-core` is a separate JUCE-free real-time delay engine. It owns fractional
delay, feedback, repeat filtering, saturation, modulation, stereo routing, and
neutral plus initial tape/BBD-style behavioral defaults. The temporary Echo
adapter builds a separate AU and standalone test host; it does not use convolution.

## Build on macOS

```shell
cmake -S native -B native/build -G Xcode
cmake --build native/build --config Release
ctest --test-dir native/build -C Release --output-on-failure
```

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
