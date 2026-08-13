# Audio convolution reverb plugin

Last verified: 2026-08-12

## Implemented proof of concept

`audio-convolution-reverb` is a single-work-unit, pure-Java plugin. A dry WAV and
an impulse-response WAV are staged as immutable worker inputs. The plugin emits a
24-bit PCM WAV, and the coordinator publishes it through the selected artifact
store. `server-local` is the only submission placement enabled in this slice;
the descriptor remains provider-shaped without claiming client-direct support.
The launcher also offers an optional shared artifacts root. The server preserves
its normal durable job artifacts and mirrors each successful reverb job into a
separate `<root>/<job-id>/` directory for convenient collection and Finder access.
The launcher suggests an output filename based on the dry source and IR stems plus
the wet, dry, pre-delay, and IR-normalization controls. The suggestion remains live
until the user types an explicit override; peak protection and headroom are
intentionally omitted from the filename.

Every terminal Reverb job publishes `reverb-job-report.txt` beside the existing
machine-readable summaries and any output WAV. The plain-text report records job
identity and status, submitted/completed timestamps, processing and wall-clock
duration, worker assignments, input filenames/paths/sizes, every submission
control, output artifact metadata, sizes, providers, and SHA-256 values. Successful
jobs using a shared artifact root mirror this report with the rest of the folder.
For successful jobs, the report also records the processor's actual global output
gain as a linear multiplier and dB value and states whether peak protection
engaged. The worker returns this measured result rather than asking the server to
infer it from configured controls.

The DSP layer is separated into WAV I/O, an internal radix-2 FFT, IR preparation,
uniform partitioned convolution, and streaming block orchestration. The dry input
is read in blocks. IR partitions and their spectra are precomputed. A temporary
double-precision stream permits deterministic second-pass peak protection without
retaining the complete source or output in memory.

Inputs may be mono or stereo, 16-bit PCM, 24-bit PCM, or 32-bit IEEE float WAV.
Output is mono or stereo 24-bit PCM. Sample rates must match. A mono dry source
with a stereo IR produces stereo output; stereo source and IR channels are paired;
a mono IR is applied independently to both dry channels. The full convolution
tail and optional pre-delay are retained.

Controls are wet level, dry level, pre-delay, IR peak normalization, output peak
protection, and safe headroom. No external executable, native DSP library, or
third-party FFT dependency is used.

## Future distributed decomposition

Version 1 intentionally assigns one complete convolution to one worker. A later
plan can decompose work without replacing the DSP primitives: partition the IR in
the frequency domain, assign independent partition groups or source-block ranges,
publish contribution blocks with absolute sample offsets, then perform a
lease-fenced overlap-add assembly. Any such plan must define boundary overlap,
floating-point summation order, tail ownership, and deterministic assembly before
claiming multi-worker equivalence.

Hardware sweep deconvolution is related helper tooling, not part of this plugin.
Scott's recorded hardware response must first be converted into a usable IR WAV.
