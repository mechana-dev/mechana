# Native Leslie engine

Status: first real-time development model implemented 2026-08-17.

The Leslie effect is an algorithmic moving-speaker model, not convolution. Its
JUCE-free C++20 core owns a two-way crossover, independently rotating treble horn
and bass drum, Doppler delay, directional amplitude, stereo microphone geometry,
cabinet drive, parameter smoothing, and wet/dry composition. No JUCE type crosses
the core boundary and processing performs no allocation after `prepare`.

The first **Classic Cabinet** defaults use published modern Leslie 122H/142H
mechanical behavior as calibration anchors: approximately 44/402 RPM for the horn,
42/372 RPM for the drum, and materially slower acceleration for the heavy drum.
They remain a behavioral development model, not a measured clone or a claim of
manufacturer endorsement. Controlled recordings of a real cabinet will be needed
for listening calibration of crossover response, radiation pattern, microphone
geometry, drive, and output level.

The initial user surface exposes Stop/Slow/Fast rotor mode, drive, horn/drum
balance, microphone distance, stereo width, crossover, wet, dry, bypass, and a
single reset. Continuous controls are smoothed over 20 ms. Rotor transitions use
independent mechanical rise/fall behavior and add no reported host latency.

The thin JUCE adapter builds a separate Audio Unit for Logic/GarageBand. The native
Mechana Effects application adds a Leslie tab and processes only the currently
selected effect tab. The standard native benchmark registers Leslie independently
at 44.1, 48, 88.2, and 96 kHz on arm64 and x86_64.

Future calibration may add named cabinet models, adjustable rotor rise/fall times,
more detailed horn/drum radiation and cabinet filters, and measured amplifier
nonlinearity. Those refinements should remain in this core rather than JUCE.
