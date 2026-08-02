# Current state

Verified: 2026-08-02

This file reports repository evidence, not desired future status.

## Present in the repository

- A multi-module Maven build with API, protocol, coordinator, worker, runtime,
  server, and client modules plus a nested `plugins/` reactor containing the sleep
  and FFmpeg video plugin implementations.
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
- Concrete plugin source lives under `plugins/`; `mechana-api` remains outside
  that directory as the public contract. The server's video-plugin dependency is
  limited to the local video demo/composition entry points.
- A generic in-memory observable-job model and loopback-only HTTP dashboard for
  plugin identity, stages, weighted work-unit progress, configured/active workers,
  elapsed time, bounded event history, errors, and structured plugin-supplied
  display details. Scheduler-managed sleep jobs expose it at
  `/dashboard/jobs/<job-id>`; local video runners use the same contracts.
- A loopback-only server dashboard at `/dashboard` that retains worker
  registrations, advertised IP addresses, connection state, capabilities,
  server PID/date/time/uptime, live active-job rows, and disk-backed completed-job
  rows. Each job row links to its generic job dashboard, and
  terminal job/work-unit elapsed times stop advancing. Worker rows derive an
  `IDLE`, `WORKING`, or `OFFLINE` activity from live assignments and link active
  workers directly to their current job.
- Terminal job dashboard snapshots and server-owned artifacts persist beneath a
  configurable server data directory (default `.mechana/server`). Completed-job
  detail pages list downloadable artifacts; a loopback-only purge action removes
  the record and its owned artifact directory. The sleep slice publishes its
  terminal `job-summary.json` as the first generic downloadable artifact.
- Active jobs can be aborted from either dashboard. Abort fences current leases,
  marks unfinished work units and the job `CANCELLED`, rejects late worker updates,
  and archives the terminal snapshot like other completed jobs.
- A manual two-host video proof entry point can assign four of eight segments to
  the local host and four over SSH, aggregate both hosts' FFmpeg progress into the
  one-job dashboard, retrieve remote artifacts, and enforce a smaller-than-input
  final-size gate. It is not integrated with the scheduler.

## Not established by current repository evidence

- A generic plan/partition/assemble API or artifact abstraction.
- Scratch-space advertisement, reservations, or capacity-aware matching.
- Scheduler-managed media distribution, durable artifact transfer, or reserved
  worker scratch.
- Durable active scheduler state, authentication, or production isolation.
- Durable worker presence across restarts, richer fleet telemetry, authentication,
  or remote dashboard access.
- Pause/resume, revival of terminal jobs, resumable job lineage, reuse of completed
  work-unit artifacts, or plugin-defined mid-work-unit checkpoints.

These are accepted direction where listed in [decisions](decisions.md) and planned
work where listed in [roadmap](roadmap.md); they must not be described as shipped.

The accepted plugin model is now documented as a complete computational contract
in [plugin model](plugin-model.md). This documentation decision does not change the
implementation facts above: the generic plan/work-unit/assemble API and its full
platform ownership boundary are not established by current repository evidence.
