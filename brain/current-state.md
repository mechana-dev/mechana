# Current state

Verified: 2026-08-01 on branch `agent/distributed-execution-first-pass`

This file reports repository evidence, not desired future status.

## Present in the repository

- A multi-module Maven build with API, protocol, coordinator, worker, runtime,
  sleep-plugin, server, and client modules.
- The root POM currently compiles with release 21 and enforces Java 21+ and Maven
  3.9+; this differs from the accepted Java 25 target.
- An in-memory scheduler for sleep tasks with pull-based workers, renewable leases,
  expired-work requeueing, and stale-completion rejection.
- A preliminary HTTP/JSON server/client/worker distributed slice described in
  `../DEVELOPMENT.md`.
- Server-authoritative plugin JAR download, SHA-256 verification, per-execution
  loading, and temporary-file cleanup in the distributed slice.
- Spotless and SpotBugs checks bound to Maven `verify`.
- Apache License 2.0 text in `../LICENSE`.

## Not established by current repository evidence

- Java 25 build configuration.
- A generic plan/partition/assemble API or artifact abstraction.
- Scratch-space advertisement, reservations, or capacity-aware matching.
- A media plugin, FFmpeg/FFprobe integration, segmentation, or assembly.
- Durable scheduler/job persistence, authentication, or production isolation.

These are accepted direction where listed in [decisions](decisions.md) and planned
work where listed in [roadmap](roadmap.md); they must not be described as shipped.

The accepted plugin model is now documented as a complete computational contract
in [plugin model](plugin-model.md). This documentation decision does not change the
implementation facts above: the generic plan/work-unit/assemble API and its full
platform ownership boundary are not established by current repository evidence.
