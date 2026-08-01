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

## 2026-08-01 06:04:00 EDT — Implement local distributed-video proof slice

- Added `mechana-plugin-video` with modular discovery/validation, runtime probing,
  scratch estimation, keyframe-aware planning, segment execution, assembly, and
  final-result validation.
- Added bounded local parallel segment transcoding through external FFmpeg, robust
  progress parsing, cancellation/timeouts, and child-process termination.
- Chose video-only Matroska intermediates and one whole-stream audio-copy step,
  followed by concat/remux into MP4 or Matroska and authoritative FFprobe checks.
- Added a local CLI, unit tests independent of FFmpeg, and an integration test that
  generates its own fixture and skips unless FFmpeg, FFprobe, and libx265 exist.
- Moved the build to the accepted Java 25 release and enforcement range.
- This increment does not add cluster scheduling, uploads, object storage, remote
  artifact movement, or client-side assembly.

## 2026-08-01 08:20:00 EDT — Allow newer JDKs to build Java 25 target

- Kept the compiler target at Java release 25 while changing build-environment
  enforcement from exactly JDK 25 to JDK 25 or newer.
- This permits the normal Homebrew Maven environment running on JDK 26 without
  changing the produced Java 25 bytecode target.

## 2026-08-01 11:20:00 EDT — Harden sparse-keyframe video planning

- A real 20-second Ring-camera test exposed clustered keyframes near the end of
  the source, which previously produced one long segment followed by tiny tails.
- Planning now accepts a boundary only near its target and only when both adjacent
  segments remain useful sizes; sparse inputs may honestly remain one work unit.
- Added regression coverage using the observed sparse-keyframe shape.

## 2026-08-01 14:15:00 EDT — Add live local video-job monitoring

- Added an observer contract for video workflow stages and per-segment FFmpeg
  progress without coupling media execution to the HTTP server.
- Added a thread-safe one-job status model and loopback HTTP dashboard with
  duration-weighted overall progress, configured/active workers, a segment table,
  elapsed time, recent events, and a machine-readable JSON endpoint.
- Added a monitored local entry point and unit/HTTP coverage that does not require
  FFmpeg. The page intentionally monitors only the bounded in-process executor;
  distributed worker telemetry and durable job history remain future work.
