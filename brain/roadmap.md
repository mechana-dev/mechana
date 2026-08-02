# Roadmap

Last reviewed: 2026-08-01

This sequence expresses intent, not completion. Check [current state](current-state.md)
before reporting progress.

## Milestone 1 — in-process foundation

- Align the build with Java 25.
- Define task-agnostic plugin lifecycle and artifact contracts.
- Model `plan -> parallel partitions -> assemble` in one process.
- Implement deterministic state transitions, retries, and tests without transport.

## Milestone 2 — media reference plugin

- Add FFprobe inspection and keyframe/time-based planning.
- Execute FFmpeg partitions as external processes under one per-job runtime
  signature.
- Assemble ordered, compatible outputs and expose failure diagnostics.

## Milestone 3 — distributed control plane

- Add HTTP+JSON adapters for clients and workers around the core contracts.
- Advertise capabilities and scratch capacity; reserve/release scratch atomically.
- Move artifact bytes through the data plane, not scheduler payloads.
- Preserve leases, retry safety, and stale-result rejection.

## Later evolution

- Support client-side assembly without breaking artifact or job contracts.
- Add durable state, authentication/authorization, stronger plugin isolation, and
  production operations based on explicit future decisions.

## Implemented first slice — pause and resumable execution

- Add cooperative `PAUSED` state that stops new assignments, fences running
  leases, and retains completed work.
- Resume paused jobs by queuing only unfinished work units.
- Resume cancelled or failed work as a new job with explicit `resumedFromJobId`
  lineage rather than mutating immutable terminal history.
- Reuse completed work-unit outputs only after validating input identity, plugin
  version, options, runtime signature, checksums, and artifact availability.
- Treat mid-work-unit checkpointing as an optional plugin capability; the first
  slice reuses whole completed work units and reruns incomplete ones.

The sleep scheduler now implements this first slice. Generic input/plugin/runtime
identity validation, reusable artifact manifests, and plugin-defined mid-work-unit
checkpoints remain follow-up work.
