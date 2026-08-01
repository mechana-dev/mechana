# Project notes

Append-only record of material Mechana project changes and accepted decisions.

## 2026-08-01 02:35:08 EDT — Adopt repository-local project brain

- Added root `AGENTS.md` as the agent entry point and canonical `brain/` context.
- Recorded accepted project direction, architecture boundaries, plugin/artifact/
  scheduler/media invariants, roadmap, conventions, and verified current state.
- Kept planned decisions distinct from the branch's existing implementation.
- Designated this file as the timestamped append-only project record.

## 2026-08-01 05:25:42 EDT — Accept complete plugin computational contract

- Accepted that a plugin owns the complete domain computation contract: supported
  input/output descriptions, processing options, authoritative validation,
  planning/decomposition, per-work-unit execution, resource estimation,
  assembly/reassembly, and final-result validation.
- Kept platform lifecycle and placement responsibilities in Mechana: IDs,
  persistence, scheduling, worker selection, scratch reservations, artifact
  transfer/integrity, leases, retries, attempt fencing, cancellation propagation,
  progress aggregation, invocation placement, cleanup, and retention.
- Constrained the initial execution model to
  `plan -> parallel work units -> assemble`; no generic DAG engine was accepted.
- Updated [plugin model](../brain/plugin-model.md),
  [architecture](../brain/architecture.md), [decisions](../brain/decisions.md),
  [current state](../brain/current-state.md), and
  [glossary](../brain/glossary.md) without claiming implementation progress.
