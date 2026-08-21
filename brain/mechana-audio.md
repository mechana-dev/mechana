# Mechana Audio repository boundary

Status: **extracted and independently verified**

Last reviewed: 2026-08-21

Production audio is authoritative in the private
[`mechana-dev/mechana-audio`](https://github.com/mechana-dev/mechana-audio)
repository. The history-preserving extraction started from Mechana commit
`670459b41b1a861cdd25763c3e9db9afa4b01576`, annotated tag
`architecture-baseline-1.4`.

Mechana Audio owns shared native DSP, convolution, delay, modulation and
nonlinear engines; Reverb, Echo, Leslie and Octave Fuzz products; Audio Unit and
future native adapters; standalone audio applications; benchmarks; and
audio-specific build, signing, notarization, and packaging work.

Mechana owns the coordinator, scheduler, workers, sandbox, storage and artifact
fabric, plugin SDK, Job Launcher, and distributed orchestration. The pure-Java
distributed reverb plugin remains here as an intentional platform reference, not
as production DSP.

## Future generic adapter

One future Java plugin, tentatively `mechana-plugin-audio`, should discover the
installed Mechana Audio engine and effects through the public integration
contract. It should map self-described effect and parameter schemas into the Job
Launcher, stage input artifacts, invoke offline rendering, and publish results.
It must not implement or duplicate DSP, and adding an effect should not require a
new Java plugin.

Mechana is one external integration consumer, not a privileged internal
dependency. The same stable effect IDs, parameter IDs, capability discovery,
offline JSON model, and future native ABI must be usable by other integrations.

Existing Apache-2.0 grants remain valid for source already published in Mechana.
Repository privacy does not revoke them. Mechana Audio documents the required
contribution, dependency, JUCE, impulse-response, and sample audit before any
future proprietary/commercial license choice.
