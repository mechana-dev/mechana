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
RMS attenuation, spectral centroid, high- and low-band energy ratios, crest-factor
smear proxy, stereo correlation, adjacent-window lag drift, and a THD-like harmonic
ratio only when a window is sufficiently tone-dominated. Windowed program-material results are useful
calibration evidence, while deterministic impulse/burst tests remain authoritative for
DSP invariants.

Analog Memory is BBD-inspired rather than component-exact. Its recursive path performs a
modulated fractional-delay read, user high-cut, user low-cut, unity-slope asymmetric
soft limiting, calibrated feedback gain, and delay write. A stronger two-pole output
reconstruction filter voices each audible repeat without over-darkening the recursive
tail; both bandwidth stages remain tied to High Cut. The normal wet path is mono-centered
while stereo dry and explicit ping-pong remain available. A deterministic slow oscillator,
subtle flutter, and smoothed seeded wander move the read head continuously. The model
does not currently add hiss or clock feedthrough because the private tail floor has not
been characterized reliably.

This design follows general BBD constraints documented in the [Analog Devices analog
delay application note](https://www.analog.com/media/en/technical-documentation/application-notes/5866763300941an245.pdf)
and the modulation behavior described by the [manufacturer's Deluxe Memory Man
manual](https://www.ehx.com/wp-content/uploads/2020/11/deluxe-memory-man.pdf). These
sources guide a credible product-owned model, not a proprietary implementation clone.

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

Native release packaging rebuilds the Echo AU from the selected architecture build
immediately before staging. Each signed component contains `MechanaBuild.txt` with the
Git commit, Echo DSP source SHA-256, pre-signing component checksum, and architecture;
the final ZIP receives a SHA-256 sidecar.

Local AU calibration must install through
`packaging/macos/install-echo-au-development.sh`. It verifies that the package marker
matches the current checkout before replacing the user component, applies an Apple
Development signature, forces Audio Component Registrar discovery, and runs full
Apple validation. A successful `auval` result without the marker comparison is not
sufficient because the registrar may still be loading a previously installed build.
