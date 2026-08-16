# Native effect benchmark contract

Every native real-time effect benchmark uses the same reporting contract so
performance remains comparable across releases and effect types.

- 48 kHz stereo processing
- 128-sample host blocks with a 2.667 ms deadline
- deterministic fixtures embedded in the executable
- preparation measured separately from real-time processing
- per-cycle average, 95th-percentile, and maximum milliseconds per block
- each block time also reported as a percentage of its deadline
- overall processing time, real-time load, and real-time multiplier
- repeated cycles with a median-load summary

Benchmarks report performance; they do not impose a machine-specific pass/fail
threshold. Each effect supplies representative embedded input and its normal
production processing engine. New effects should add a `mechana_*_benchmark`
target accepting common `--cycles N` and `--seconds N` options. The packaged
runner discovers every such executable automatically.
