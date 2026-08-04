# Mechana™ repository guidance

## Read first

Before changing this repository, read in order:

1. `brain/README.md` for the brain map and maintenance rules.
2. `brain/current-state.md` to distinguish implemented behavior from accepted plans.
3. `brain/architecture.md` and the task-specific brain file it links to.
4. `brain/conventions.md` before editing code, tests, or documentation.
5. `docs/plugin-lifecycle.md` before changing plugin or execution contracts.

Use `brain/decisions.md` as the accepted-decision index. Do not infer implementation
status from a decision or roadmap item; verify the code and `brain/current-state.md`.

## Source file legal header

Add the repository's standard Apache License 2.0 header to every new hand-authored
source or script file, using `Copyright (c) 2026 Mark Vita`. Use the language's
normal comment syntax and place the header before the package declaration,
imports, shebang, or other source content (while preserving a required shebang as
the first line). Do not add the bulky header to generated files, binaries, SVGs,
JSON/YAML, assets, or formats where comments are unsupported or inappropriate.
Copy the exact wording and URL from an existing Java source file.

## Commands

Run from the repository root:

```shell
java --version
mvn --version
mvn verify
```

The accepted toolchain is Java 25, Maven, IntelliJ IDEA, and macOS. Use
`mvn spotless:apply` only when
formatting needs correction, then rerun `mvn verify`.

## Architectural boundaries

- Keep the core task-agnostic; media behavior belongs in a plugin.
- Keep the public plugin contract in `mechana-api` and all concrete plugin
  implementations under `plugins/`. Infrastructure modules must not depend on a
  concrete plugin except at an explicit composition or demo boundary.
- Preserve control-plane/data-plane separation. Scheduling and metadata must not
  become the path for large artifact bytes.
- Model partitioned work as `plan -> parallel partitions -> assemble`; assembly is
  an explicit stage, never an accidental scheduler side effect.
- Depend on the artifact abstraction, not local paths or one storage backend.
- Treat input, intermediate, and output providers as independent choices. Keep
  provider credentials and APIs outside plugins and core scheduling contracts.
- Keep the coordinator primarily a control plane. Prefer direct, lease-fenced
  worker publication to authorized requester storage over mandatory bulk-data
  relay when the provider and security model support it.
- Keep assembly storage- and location-independent: the plugin defines how;
  Mechana may choose client, coordinator, or worker placement.
- Keep transport concerns outside core scheduling and plugin contracts. HTTP+JSON
  is a later boundary, not the Milestone 1 domain model.
- Initial topology is server-side planning and assembly; retain contracts that can
  support later client-side or worker-side assembly.
- Workers advertise scratch capacity. Scheduling must reserve capacity and must
  not knowingly overcommit it.
- Media execution uses FFmpeg/FFprobe as external processes; do not embed media
  semantics or native process details in the scheduler.
- Preserve lease ownership: stale or superseded assignments cannot complete work.
- Treat plugins as computational contracts, not owners of distributed execution.
- Do not introduce a general DAG engine without a new explicit architecture decision.
- Distinguish plugin packages, plugin runtimes, and work-unit attempts. Do not
  claim sandbox guarantees until OS enforcement is implemented and verified.
- Keep plugin-authoring artifacts vendor-neutral and useful to both humans and AI.
- Keep curated AI authoring knowledge reviewable and portable in Git; do not add
  large base-model weights, private user material, or unsanitized generated code.
- Prefer the simplest design that preserves correctness, safety, and ownership.

## Keeping context current

Update the smallest relevant brain file whenever a change alters an accepted
decision, invariant, architecture boundary, roadmap, convention, or verified
implementation state. Update `brain/current-state.md` only from repository evidence.

Append a timestamped entry to `docs/PROJECT-NOTES.md` for material implementation
work, accepted decisions, milestone changes, or brain updates that affect future
work. Do not rewrite old entries. Link to brain files rather than duplicating long
explanations. Never store secrets, credentials, personal data, or transient logs.
