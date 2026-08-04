# Plugin lifecycle specification

Status: Architecture Baseline 1  
Last reviewed: 2026-08-04

This specification defines the first common lifecycle for atomic and partitioned
Mechana computations. The platform may optimize placement and transport but must
preserve the ordering, ownership, and authority rules below.

## Lifecycle

1. **Discover and describe.** Mechana loads package identity, plugin descriptor,
   schemas, declared runtimes, permissions, and capabilities.
2. **Advertise capability.** A worker reports a healthy compatible plugin/runtime
   signature and enforceable trust features. Advertisement is not assignment.
3. **Validate submission.** The plugin validates input/output descriptions,
   options, and combinations before work becomes runnable.
4. **Plan.** The plugin deterministically emits independent work units, artifact
   requirements, resource estimates, ordering, and assembly requirements.
5. **Reserve and stage.** Mechana selects a compatible worker, atomically reserves
   resources, creates an attempt workspace, and stages verified input artifacts.
6. **Execute work units.** The plugin processes one opaque unit per invocation.
   Mechana owns leases, retries, cancellation delivery, timeout, and placement.
7. **Report progress.** Plugins report bounded attempt progress and optional opaque
   display details; Mechana aggregates job-level state.
8. **Publish attempt outputs.** Mechana accepts content-verifiable artifacts only
   from the current authoritative lease. Stale or partial output is rejected.
9. **Retry or complete.** A retry creates a new attempt without changing logical
   work-unit identity. Retry policy and attempt fencing are platform-owned.
10. **Assemble.** After every required authoritative output exists, the plugin
    validates compatibility and assembles the explicit ordered set.
11. **Validate final result.** The plugin authoritatively validates requested
    output semantics; Mechana then publishes the final artifact(s).
12. **Retain and clean up.** Mechana applies retention policy, releases resources,
    removes attempt scratch, and records terminal state and diagnostics.

Atomic computations use the same lifecycle with a one-unit plan and trivial or
identity assembly. This avoids a separate distributed-systems model.

## Invariants

- The graph is `plan -> parallel work units -> assemble`, not an arbitrary DAG.
- Planning is deterministic for the same validated inputs, options, plugin
  version, and relevant runtime profile.
- Control-plane messages carry state and artifact metadata, not large artifact bytes.
- Plugins estimate resources but never reserve them or choose workers.
- One logical work unit has at most one authoritative active attempt.
- Assembly never consumes missing, stale, unordered, or incompatible outputs.
- Cancellation and cleanup are platform obligations even when plugin code fails.
- Current implementation status is reported only by
  [current state](../brain/current-state.md), not by this specification.

See [plugin model](../brain/plugin-model.md), [artifacts](../brain/artifacts.md),
[scheduler](../brain/scheduler.md), and [sandbox](../brain/sandbox.md).
