# Native effect benchmark contract

Every native real-time effect benchmark uses the same reporting contract so
performance remains comparable across releases and effect types.

- standard runs at 44.1, 48, 88.2, and 96 kHz stereo
- 128-sample host blocks, with the deadline recalculated for each sample rate
- deterministic fixtures embedded in the executable
- preparation measured separately from real-time processing
- per-cycle average, 95th-percentile, and maximum milliseconds per block
- each block time also reported as a percentage of its deadline
- overall processing time, real-time load, and real-time multiplier
- repeated cycles with a median-load summary
- a separated final report with minimum, arithmetic-average, median, and maximum
  deadline load across cycles for every architecture and sample rate

Benchmarks report performance; they do not impose a machine-specific pass/fail
threshold. Each effect supplies representative embedded input and its normal
production processing engine. New effects should add a `mechana_*_benchmark`
target accepting common `--cycles N`, `--seconds N`, and `--sample-rate HZ`
options. The packaged runner discovers every such executable automatically and
runs all four standard sample rates. The current suite reports Convolution
Reverb, Modeled Echo, and Modeled Leslie separately, including separate final
tables for each effect and architecture.

Architecture folders are discovered independently. An Intel-only package runs
natively on Intel Macs and through Rosetta on Apple Silicon Macs, identifying
the execution mode in its summary instead of requiring an ARM64 executable.
Downloaded release packages provide a signed and notarized **Run Benchmarks.app**
that displays the complete output in a scrollable window. The shell runner remains
an internal implementation resource rather than a Finder-launched `.command` file.
