# Moved

See `../../brain/architecture.md`.
# Shared native audio DSP core (2026-08-18)

`native/audio-core` owns reusable JUCE-free smoothing, gain/mix/metering,
filtering, nonlinear, and 2x FIR oversampling primitives. Octave Fuzz is the
first broad consumer; Echo uses nonlinear helpers; Reverb migration is kept
incremental. Leslie, chorus, and flanger are intended future consumers.
