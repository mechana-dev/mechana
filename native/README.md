# Native Reverb development

This tree begins the native Audio Unit implementation while preserving an exit
from JUCE. `reverb-core` contains product DSP and has no JUCE dependency. The
temporary adapter under `adapters/juce-plugin` translates Audio Unit buffers,
parameters, state, and the bundled factory WAV into core-owned types.

## Build on macOS

```shell
cmake -S native -B native/build -G Xcode
cmake --build native/build --config Release
ctest --test-dir native/build -C Release --output-on-failure
```

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
- native uniform partitioned convolution with a 128-sample reported latency
- six bundled factory responses plus custom WAV import
- automatable Wet, Dry, Pre-delay, Early, Late, Attack, Decay, wet Low-cut,
  wet High-cut, and Bypass parameters
- reset controls for mix/timing, captured-response shaping, and EQ
- IR sample-rate conversion, response shaping, and calibration outside the
  real-time audio callback
- Logic project state save/restore
- JUCE-free core regression test

Durable user-profile library management, parameter smoothing, automatic peak
protection parity, Universal packaging, signing, and notarization remain
follow-up work. IR-shaping automation is intentionally applied asynchronously
at block-safe boundaries rather than sample-accurately in this proof of concept.
