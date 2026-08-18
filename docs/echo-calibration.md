# Echo calibration

The Analog Memory model is Mechana's own analog-delay design. Professional reference
renders inform listening and regression targets; they do not make it a Soundtoys
EchoBoy or Deluxe Memory Man clone.

Scott approved the Hendrix/Watchtower dry, EchoBoy, and Mechana renders for private
development calibration. They are copyrighted external material and must remain local:
do not add them, derived clips, or rendered candidates to this public repository.

Run the local comparison with paths outside the checkout:

```shell
python3 scripts/analyze-echo-calibration.py \
  --dry /private/path/Watchtower.wav \
  --reference '/private/path/Hendrix Echo Boy.wav' \
  --candidate '/private/path/Hendrix Mechana Echo.wav' \
  --delay-ms 350
```

The report includes direct-gain estimate, peak/RMS, threshold tail times, per-repeat
RMS attenuation, and spectral centroid. Windowed program-material results are useful
calibration evidence, while deterministic impulse/burst tests remain authoritative for
DSP invariants.

On the approved files, the original reference measured -7.84 dB per 350 ms tail window
and the old Mechana render -2.73 dB. The calibrated engine measured -8.00 dB, with its
tail centroid falling from about 1481 Hz to 1011 Hz over ten windows. The new 26% linear
Mix has 74% dry gain by definition; unlike the old independent controls, it cannot also
match a reference direct gain near 89% without separate makeup gain.

The file-oriented Mechana Effects application mirrors these native Echo semantics.
Its standalone regression suite locks the 36% Feedback coefficient,
unity-small-signal Analog Memory coloration, linear Mix endpoints, and smoothed Mix
automation so an app release cannot silently fall back to raw feedback. Existing app
preferences containing separate Wet and Dry values migrate to
`Mix = Wet / (Wet + Dry)`; new and reset Analog Memory settings use 26% Mix.
