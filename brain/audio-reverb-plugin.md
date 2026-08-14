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

IR inputs remain WAV-only. Dry inputs may be WAV/WAVE, M4A containing AAC or
Apple Lossless (ALAC), raw AAC, MP4 containing AAC audio, or AIFF. Before staging, dry audio
is decoded to 24-bit PCM WAV and converted to the IR sample rate; matching-rate
WAVs pass through unchanged. The Apache-2.0 `javasound-aac` library provides a
pure-Java AAC/M4A decoder, a BSD-3-Clause ALAC decoder, and a 32-tap windowed-sinc resampler performs sample-rate
conversion. The worker-side convolution remains pure Java and receives matching-rate WAVs.

Worker WAV inputs may be mono or stereo, 16-bit PCM, 24-bit PCM, or 32-bit IEEE float WAV.
Output is mono or stereo 24-bit PCM. A mono dry source
with a stereo IR produces stereo output; stereo source and IR channels are paired;
a mono IR is applied independently to both dry channels. The full convolution
tail and optional pre-delay are retained. The direct dry component receives a
10 ms end fade so a source cut off while still active transitions cleanly into
the wet-only tail; the samples sent through convolution are unchanged.

Controls are wet level, dry level, pre-delay, safe IR peak normalization, output
peak protection, and safe headroom. IR normalization is attenuation-only: peaks
above -1 dBFS are reduced to -1 dBFS, while quieter measured responses retain
their captured gain. This prevents hardware IRs from being unintentionally
amplified before convolution. No external executable, native DSP library, or
third-party FFT dependency is used. The only audio import dependency is
`com.tianscar.javasound:javasound-aac:0.9.8` (Apache-2.0),
`com.tianscar.javasound:javasound-alac:0.2.3` (BSD-3-Clause), JCodec 0.2.5
(BSD-2-Clause), and mp4parser 1.9.56 (Apache-2.0). The MP4 parsers provide a
fallback for fragmented MP4 files whose audio samples are stored in movie fragments.

## Future distributed decomposition

Version 1 intentionally assigns one complete convolution to one worker. A later
plan can decompose work without replacing the DSP primitives: partition the IR in
the frequency domain, assign independent partition groups or source-block ranges,
publish contribution blocks with absolute sample offsets, then perform a
lease-fenced overlap-add assembly. Any such plan must define boundary overlap,
floating-point summation order, tail ownership, and deterministic assembly before
claiming multi-worker equivalence.

The reusable pure-Java `SweepDeconvolver` helper converts an exact excitation
sweep plus its recorded 100%-wet return into a convolution-ready IR. It uses
regularized frequency-domain division, preserves the captured response gain,
aligns to the recovered impulse, estimates the audible tail, retains a short
pre-roll, and fades the final 50 ms. This is preparation tooling rather than a
distributed plugin capability.

## Standalone macOS composition

`standalone-reverb-app` invokes `AudioConvolutionReverbPlugin` directly through a
local `TaskContext`, so local and distributed execution share the plugin entry
point and DSP implementation. It runs one job at a time, publishes into a selected
`<artifact-root>/<job-id>/` directory, and retains `job.json`, the output WAV,
plugin result metadata, and `reverb-job-report.txt`. The packaged **Mechana
Reverb.app** opens no network listener and includes its Java runtime. This is an
explicit application composition, not a second reverb implementation or a new
general local plugin runtime contract.
The Apply Reverb tab can also stream the selected recording through the same
partitioned-convolution primitives to the default system audio output without
creating a job artifact. Preview supports play, pause/resume, and stop, performs
the same dry-audio decoding while preserving the recording's native sample rate,
and plays the complete IR tail. A content-addressed cache beneath the user's macOS
Library/Caches directory stores 24-bit IR variants resampled to each required dry
sample rate. Matching-rate IRs pass through unchanged, and changing the source IR
content selects a new cache entry automatically. Variants are created lazily so
the user manages only the selected master IR; the status bar reports a sample-rate
regeneration only on the first use of each IR/rate pair. Paired sliders and numeric override fields control wet level, dry level,
and pre-delay; the pre-delay slider spans 0–200 ms while its numeric override
continues to accept larger precise values. Those controls plus IR normalization, peak protection, and
headroom take effect during playback with a 20 ms transition that avoids
control-change clicks. Normalization is an exact linear gain change on the live
wet convolution result, so it does not restart or approximate the IR. Because
streaming cannot know the future global peak, preview peak
protection is an instantaneous ceiling at the selected headroom; offline export
retains its deterministic two-pass global gain.
Changing the selected IR during playback prepares a cached sample-rate-matched
variant and its FFT partitions away from the audio thread, then crossfades to it
over 50 ms. Mono and stereo profiles with any supported WAV sample rate may be
interchanged without restarting playback.
The application bundle carries the five synthetic development IRs previously used
for listening tests and exposes them through a dedicated chooser. It also bundles
the standardized 48 kHz/24-bit stereo Mechana sweep and provides a **Create IR
from Sweep** tab. A user selects a recorded wet return and output path; the app
generates the aligned, trimmed IR locally and selects it for immediate use in the
Apply Reverb tab. The unrestricted IR chooser continues to accept compatible WAVs
created elsewhere.

The companion `audio-ir-deconvolution` capability uses the same module and DSP
code as the standalone app. It accepts an original sweep WAV plus the hardware
unit's recorded wet return, requires matching sample rates, and emits a trimmed
24-bit convolution-ready IR. Both paths use regularized FFT division, preserve
captured gain, align the impulse, estimate the decay tail, and fade the trim edge.
