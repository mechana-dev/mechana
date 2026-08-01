# Architecture

Last reviewed: 2026-08-01

## Stable shape

Mechana separates the control plane from the data plane and keeps the execution
core task-agnostic. A plugin encapsulates the complete computational contract for
its domain: supported input/output descriptions, processing options, authoritative
validation, planning/decomposition, per-work-unit execution, resource estimation,
assembly/reassembly, and final-result validation. Artifacts cross stages through
an abstraction rather than assumed local paths.

Partitioned jobs follow one explicit graph:

`plan -> parallel work units -> assemble`

- **Plan** consumes validated input and options and emits deterministic work-unit
  descriptions, resource estimates, and explicit assembly requirements.
- **Execute** runs independent work units in parallel where resources allow.
- **Assemble** validates and consumes the complete required output set, creates the
  final artifact, and validates that result; it is a first-class stage with
  observable failure.

This constrained execution model is intentional and is not a generic DAG engine.
Plugins describe what must happen. Mechana determines where, when, and under which
attempt it happens.

Initial topology performs planning and assembly server-side. Contracts must not
preclude later client-side assembly. Milestone 1 remains in-process; later HTTP+JSON
transport must adapt the same domain boundaries rather than redefine them.

## Boundaries

- Mechana platform: IDs, persistence, scheduling, worker selection, scratch
  reservations, artifact transfer/integrity, leases, retries, attempt fencing,
  cancellation propagation, progress aggregation, invocation placement, cleanup,
  and retention.
- Control plane: execution state, scheduling, leases, capabilities, reservations,
  progress, retries, and artifact metadata.
- Data plane: input, partition, intermediate, and final artifact bytes.
- Scheduler: plugin-agnostic matching and lifecycle; no FFmpeg knowledge.
- Plugin: domain description, options, validation, planning, estimation, work-unit
  execution, assembly, and result validation; no ownership of platform lifecycle
  or placement.
- Artifact service: identity and transfer; no domain-specific transformation.

See [plugins](plugin-model.md), [artifacts](artifacts.md), and
[scheduler](scheduler.md) for the contracts implied by these boundaries.
