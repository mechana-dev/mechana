# Architecture

Last reviewed: 2026-08-01

## Stable shape

Mechana separates the control plane from the data plane and keeps the execution
core task-agnostic. Plugins define how a domain workload is planned, executed, and
assembled. Artifacts cross stages through an abstraction rather than assumed local
paths.

Partitioned jobs follow one explicit graph:

`plan -> parallel partition executions -> assemble`

- **Plan** validates input and emits deterministic partition descriptions.
- **Execute** runs independent partitions in parallel where resources allow.
- **Assemble** consumes the complete required output set and creates the final
  artifact; it is a first-class stage with observable failure.

Initial topology performs planning and assembly server-side. Contracts must not
preclude later client-side assembly. Milestone 1 remains in-process; later HTTP+JSON
transport must adapt the same domain boundaries rather than redefine them.

## Boundaries

- Control plane: job graph, state, scheduling, leases, capabilities, reservations,
  progress, retries, and artifact metadata.
- Data plane: input, partition, intermediate, and final artifact bytes.
- Scheduler: plugin-agnostic matching and lifecycle; no FFmpeg knowledge.
- Plugin: domain planning/execution/assembly; no ownership of global scheduling.
- Artifact service: identity and transfer; no domain-specific transformation.

See [plugins](plugin-model.md), [artifacts](artifacts.md), and
[scheduler](scheduler.md) for the contracts implied by these boundaries.
