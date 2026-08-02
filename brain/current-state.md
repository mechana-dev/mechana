# Current state

Verified: 2026-08-02

This file reports repository evidence, not desired future status.

## Present in the repository

- A multi-module Maven build with API, protocol, coordinator, worker, runtime,
  server, and client modules plus a nested `plugins/` reactor containing the sleep
  FFmpeg video, and fractal-render plugin implementations.
- The root POM compiles with Java release 25 and accepts JDK 25 or newer plus
  Maven 3.9+.
- An in-memory scheduler for sleep tasks with pull-based workers, renewable leases,
  expired-work requeueing, and stale-completion rejection.
- Worker presence and task ownership use independent heartbeats. Workers emit a
  three-second presence heartbeat while idle or busy; the server uses a
  fifteen-second offline threshold. A separate lease-token heartbeat renews a
  running task without changing its reported progress.
- Sleep jobs may declare a distinct duration for each work unit.
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
  rows. Each job row links to its generic job dashboard, and terminal job/work-unit
  elapsed times stop advancing. Worker rows show `IDLE`, `OFFLINE`, or the active
  plugin name plus that worker's current work-unit progress and job link.
- The master dashboard provides a confirmed loopback-only server restart action.
  The replacement inherits the current launch configuration; workers reconnect
  and completed history remains, while volatile active jobs do not survive.
- Terminal job dashboard snapshots and server-owned artifacts persist beneath a
  configurable server data directory (default `.mechana/server`). Completed and
  cancelled rows show their terminal timestamp. Detail pages list downloadable
  artifacts and provide a loopback-only **Show in Finder** action; purge removes
  the record and its owned artifact directory. The sleep slice publishes its
  terminal `job-summary.json` as the first generic downloadable artifact.
- Active jobs can be aborted from either dashboard. Abort fences current leases,
  marks unfinished work units and the job `CANCELLED`, rejects late worker updates,
  and archives the terminal snapshot like other completed jobs.
- Active sleep jobs can be paused and resumed under the same job ID. Pause fences
  current leases, retains succeeded work units, marks unfinished work `PAUSED`,
  and excludes paused time from elapsed duration. Resume queues only unfinished
  work units, which restart from zero because no partial-task checkpoint exists.
- Cancelled or failed sleep jobs can be resumed as a new job with immutable source
  history, explicit `resumedFromJobId` lineage, and whole-work-unit reuse. The new
  job queues only work units that had not succeeded in the source job.
- A manual two-host video proof entry point can assign four of eight segments to
  the local host and four over SSH, aggregate both hosts' FFmpeg progress into the
  one-job dashboard, retrieve remote artifacts, and enforce a smaller-than-input
  final-size gate. It is not integrated with the scheduler.
- The continuously running server also has an initial scheduler-managed video
  path: loopback submission clips a server-local source, plans keyframe-aligned
  segments, stream-copies one input chunk per planned range, leases `video-ffmpeg`
  work units through the normal capability queue, transfers only each assigned
  chunk through the server, accepts lease-fenced
  segment uploads, assembles and validates a smaller HEVC result, and archives it
  as a downloadable job artifact.
- A scheduler-managed pure-Java `fractal-render` path accepts no input artifact,
  deterministically divides a requested image count across an explicit or
  fleet-derived task count, renders batched Mandelbrot/Julia PNGs on compatible
  workers, and reports scanline/image progress through the generic dashboard.
  Lease-fenced batch ZIPs are assembled and validated server-side through plugin
  code. Completed jobs publish every PNG plus a manifest, contact sheet, and
  complete collection ZIP as durable downloadable artifacts.

## Not established by current repository evidence

- A generic plan/partition/assemble API or artifact abstraction.
- Scratch-space advertisement, reservations, or capacity-aware matching.
- Content-addressed/cached media input transfer, durable intermediate transfer,
  or reserved worker scratch. Distributed video currently creates and serves
  ephemeral keyframe-aligned chunks from server-local scratch.
- Durable active scheduler state, authentication, or production isolation.
- Durable worker presence across restarts, richer fleet telemetry, authentication,
  or remote dashboard access.
- Generic cross-plugin resume validation, reusable completed-work artifact
  manifests, or plugin-defined mid-work-unit checkpoints. Resume-as-new is
  currently limited to scheduler-managed sleep jobs and reuses their logical
  completion state rather than a generic artifact contract.

These are accepted direction where listed in [decisions](decisions.md) and planned
work where listed in [roadmap](roadmap.md); they must not be described as shipped.

The accepted plugin model is now documented as a complete computational contract
in [plugin model](plugin-model.md). This documentation decision does not change the
implementation facts above: the generic plan/work-unit/assemble API and its full
platform ownership boundary are not established by current repository evidence.
