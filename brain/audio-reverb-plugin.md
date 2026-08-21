# Distributed audio reverb reference plugin

The `plugins/audio-reverb-plugin` module is intentionally retained in Mechana as
a pure-Java example of audio work executed through the distributed platform. It
is not the production Mechana Audio implementation.

The plugin accepts staged dry-audio and impulse-response WAV artifacts, performs
single-worker partitioned FFT convolution, and publishes a 24-bit WAV through the
same artifact and task lifecycle used by other Mechana plugins. Its descriptor
demonstrates typed audio inputs and effect parameters in the Job Launcher.

Version 1 assigns one complete convolution to one worker. A later platform
experiment may partition frequency-domain contributions, but it must define
overlap, floating-point summation order, tail ownership, lease fencing, and
deterministic assembly before claiming multi-worker equivalence.

Production native DSP, Audio Units, standalone audio applications, benchmarks,
and product packaging are authoritative only in
[`mechana-dev/mechana-audio`](https://github.com/mechana-dev/mechana-audio).
Future integration should replace effect-specific coupling with the generic
descriptor-driven adapter described in [Mechana Audio](mechana-audio.md).
