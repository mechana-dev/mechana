# Current state

Verified: 2026-08-01 on branch `agent/add-project-brain`

This file reports repository evidence, not desired future status.

## Present in the repository

- A multi-module Maven build with API, protocol, coordinator, worker, runtime,
  sleep-plugin, server, and client modules.
- The root POM compiles with Java release 25 and accepts JDK 25 or newer plus
  Maven 3.9+.
- An in-memory scheduler for sleep tasks with pull-based workers, renewable leases,
  expired-work requeueing, and stale-completion rejection.
- A preliminary HTTP/JSON server/client/worker distributed slice described in
  `../DEVELOPMENT.md`.
- Server-authoritative plugin JAR download, SHA-256 verification, per-execution
  loading, and temporary-file cleanup in the distributed slice.
- Spotless and SpotBugs checks bound to Maven `verify`.
- Apache License 2.0 text in `../LICENSE`.
- A modular FFmpeg/FFprobe video plugin local slice with H.264 MP4/MKV validation,
  keyframe-aware deterministic planning, bounded parallel H.265 segment execution,
  separate whole-stream audio handling, concat/remux assembly, final validation,
  scratch estimation, runtime probing, process cancellation/timeouts, and a CLI.
- A loopback-only live HTTP dashboard for one local video job, backed by the
  plugin's lifecycle/progress observer. It shows weighted overall progress,
  configured/active local workers, segment status, elapsed time, and recent events.

## Not established by current repository evidence

- A generic plan/partition/assemble API or artifact abstraction.
- Scratch-space advertisement, reservations, or capacity-aware matching.
- Distributed media scheduling, artifact transfer, or reserved worker scratch.
- Durable scheduler/job persistence, authentication, or production isolation.
- Distributed job monitoring, worker telemetry, durable monitoring history, or
  authenticated/remote dashboard access.

These are accepted direction where listed in [decisions](decisions.md) and planned
work where listed in [roadmap](roadmap.md); they must not be described as shipped.

The accepted plugin model is now documented as a complete computational contract
in [plugin model](plugin-model.md). This documentation decision does not change the
implementation facts above: the generic plan/work-unit/assemble API and its full
platform ownership boundary are not established by current repository evidence.
