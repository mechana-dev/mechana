# Mechana repository guidance

## Read first

Before changing this repository, read in order:

1. `brain/README.md` for the brain map and maintenance rules.
2. `brain/current-state.md` to distinguish implemented behavior from accepted plans.
3. `brain/architecture.md` and the task-specific brain file it links to.
4. `brain/conventions.md` before editing code, tests, or documentation.

Use `brain/decisions.md` as the accepted-decision index. Do not infer implementation
status from a decision or roadmap item; verify the code and `brain/current-state.md`.

## Commands

Run from the repository root:

```shell
java --version
mvn --version
mvn verify
```

The accepted toolchain is Java 25, Maven, IntelliJ IDEA, and macOS. The current
`pom.xml` still targets Java 21; treat that as a recorded current-state mismatch,
not permission to silently change the build. Use `mvn spotless:apply` only when
formatting needs correction, then rerun `mvn verify`.

## Architectural boundaries

- Keep the core task-agnostic; media behavior belongs in a plugin.
- Preserve control-plane/data-plane separation. Scheduling and metadata must not
  become the path for large artifact bytes.
- Model partitioned work as `plan -> parallel partitions -> assemble`; assembly is
  an explicit stage, never an accidental scheduler side effect.
- Depend on the artifact abstraction, not local paths or one storage backend.
- Keep transport concerns outside core scheduling and plugin contracts. HTTP+JSON
  is a later boundary, not the Milestone 1 domain model.
- Initial topology is server-side planning and assembly; retain contracts that can
  support later client-side assembly.
- Workers advertise scratch capacity. Scheduling must reserve capacity and must
  not knowingly overcommit it.
- Media execution uses FFmpeg/FFprobe as external processes; do not embed media
  semantics or native process details in the scheduler.
- Preserve lease ownership: stale or superseded assignments cannot complete work.

## Keeping context current

Update the smallest relevant brain file whenever a change alters an accepted
decision, invariant, architecture boundary, roadmap, convention, or verified
implementation state. Update `brain/current-state.md` only from repository evidence.

Append a timestamped entry to `docs/PROJECT-NOTES.md` for material implementation
work, accepted decisions, milestone changes, or brain updates that affect future
work. Do not rewrite old entries. Link to brain files rather than duplicating long
explanations. Never store secrets, credentials, personal data, or transient logs.
