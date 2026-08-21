# Mechana Audio product architecture

Status: **extraction checkpoint prepared; repository creation and migration pending**

Last reviewed: 2026-08-21

Mechana Audio is the tentative name for a future repository and product in the
Mechana family. It will give the production audio subsystem a clean commercial
and potentially proprietary home while the Apache-licensed Mechana repository
remains the open, collaborative distributed-compute platform. No repository split
has occurred, and this document does not change the license of current source.

## Repository and product boundary

After the shared native audio core and Octave Fuzz work tracked by issue #63 is
finished and consolidated, production native audio work should be extracted into
`mechana-audio`. The extraction must preserve relevant path history, for example
with `git-filter-repo` or an equivalent path-history strategy, rather than starting
with a history-less file copy.

Every production source component must have one authoritative repository. After
migration, Mechana Audio owns:

- the proprietary/native DSP core and convolution, delay, modulation, and
  nonlinear DSP families;
- Reverb, Echo, Octave Fuzz, and future Rotary/Leslie, Chorus, Flanger, and other
  effects;
- AU, VST, and other native adapters;
- the standalone Mechana Audio application;
- audio benchmarks, packaging, signing, notarization, effect presets/models, and
  audio-specific product experience.

The original Mechana repository continues to own:

- the coordinator, server, scheduler, workers, and sandboxing;
- the storage and artifact fabric;
- the plugin SDK, job launcher, and distributed orchestration;
- a generic Java integration wrapper for external engines.

Historical, reference, or prototype pure-Java distributed audio plugins may stay
in Mechana when they genuinely demonstrate the platform. Production native DSP,
DAW adapters, and the standalone audio product move to Mechana Audio. The two
repositories must not actively maintain duplicate production implementations.

## Public integration contract

The DSP algorithms may remain proprietary, but Mechana Audio intentionally exposes
a stable, public, well-documented, language- and platform-neutral contract. Mechana
is one reference consumer, not a privileged integration path; other vendors and
frameworks can use the same contract.

The contract has its own version, independent of DSP and product releases. It
defines stable effect and parameter IDs, compatibility rules for additive fields
and unknown fields, and self-description including engine version, supported
effects, supported audio formats, and effect descriptors. Parameter descriptors
must be rich enough for automatic third-party UI generation, including:

- ID, display name, type, units, minimum, maximum, default, and step;
- linear or logarithmic behavior and enumeration values;
- automation support, grouping, and a human-readable description.

The contract must separately specify offline-render and real-time processing
semantics, including formats, channel layouts, sample rates, block/lifecycle
behavior, latency and tail reporting, state, errors, cancellation, and determinism
where applicable.

Candidate official surfaces are:

1. A native SDK with a stable C ABI and optional C++ wrapper for low-latency,
   real-time use.
2. A command-line/JSON renderer for automation, batch rendering, and Mechana jobs.
3. A future local HTTP/JSON or gRPC service for language-neutral integrations.

The schemas, interfaces, compatibility policy, examples, and documentation may be
open and public even when the engine implementation is proprietary.

## Mechana integration

Mechana should eventually provide one generic Java plugin, tentatively
`mechana-plugin-audio`, for the complete installed Mechana Audio effect set. It
does not implement DSP. It stages `ArtifactReference` inputs, orchestrates work,
invokes the released renderer or engine, publishes results, and maps engine
descriptors into the Client Job Launcher.

The wrapper discovers effects and schemas dynamically from the installed engine.
Adding Reverb, Echo, Octave Fuzz, Rotary, Chorus, Flanger, or a later effect should
ideally require no Java wrapper change. This plugin proves the public contract is
usable outside native DAW hosts without making the contract Mechana-specific.

## Product packaging

Publish individual DAW plugins per effect: **Mechana Reverb**, **Mechana Echo**,
**Mechana Octave Fuzz**, **Mechana Rotary**, and later effects. Each is a thin,
effect-specific adapter over the shared engine and contains no duplicated DSP.

Individual plugins improve DAW discovery, automation, presets, latency and tail
reporting, track templates, focused UX, and future packaging and pricing. A
combined multi-effect interface remains appropriate for the standalone Mechana
Audio application and the generic Mechana wrapper/API, where browsing or
orchestrating the complete installed effect set benefits the workflow.

The governing principle is:

`one shared engine + public integration contract + individual DAW plugins per effect + combined interfaces where workflow benefits`

## Licensing and commercialization checkpoint

Before extraction or commercialization, audit code ownership and contributions,
third-party licenses, JUCE licensing, copied or adapted DSP, bundled impulse
responses and samples, and Apache notices. Decide the new repository and product
license only after that audit; no final proprietary license is accepted yet.

Preserve the product-owned DSP versus thin JUCE-adapter boundary. It keeps the
engine testable without JUCE, supports alternate adapters, and reduces licensing
coupling during a future commercial transition.

## Migration checkpoint

The final monorepo checkpoint is recorded by annotated tag
`architecture-baseline-1.4`. It supersedes the earlier preparatory 1.3 checkpoint
by including PR #69 after native Intel release-artifact validation and repository-wide
verification completed. Production audio remains authoritative in this repository
until the private `mechana-dev/mechana-audio` repository exists, the history-preserving
extraction is pushed, and independent verification passes.

The shared native audio core and Octave Fuzz architecture are complete and consolidated.
At migration time:

1. Create and tag a clean Mechana checkpoint.
2. Extract the relevant path history into Mechana Audio.
3. Verify that the new repository builds, tests, packages, and documents its
   public contract independently.
4. Remove migrated production audio paths from Mechana in the coordinated
   checkpoint so no ongoing duplicate source remains.

Until then, current implementation facts remain in [current state](current-state.md)
and the existing [Reverb](audio-reverb-plugin.md) and
[Echo](audio-echo-engine.md) pages. This page records direction, not shipped
behavior.
