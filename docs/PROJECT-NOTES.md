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

## 2026-08-01 15:45:00 EDT — Identify workers in video monitoring

- Extended segment-start monitoring events with a worker address and exposed it
  in both status JSON and the live dashboard segment table.
- Local execution reports its resolved host address by default, with an explicit
  `MECHANA_WORKER_ADDRESS` override for stable LAN or tailnet identity.
- This labels observed execution locations without claiming distributed scheduler
  assignment, which remains future work.

## 2026-08-01 19:05:00 EDT — Add monitored two-host size-constrained proof

- Added a manual two-host runner that plans eight keyframe-aligned video segments,
  executes four locally and four through SSH, and aggregates machine-readable
  progress and worker addresses into the existing live dashboard.
- Added bitrate-constrained HEVC segment commands and a final validation gate that
  rejects output unless it is smaller than the input.
- Kept audio as a separate whole-stream copy and assembly on the initiating host.
  The SSH runner and direct artifact copy are proof scaffolding, not production
  scheduling, leases, transport, authentication, or retry behavior.
- Verified the proof with a five-minute H.264/AAC source: four segments ran on an
  Apple-silicon MacBook Air and four on an Intel Mac Mini. The assembled HEVC/AAC
  Matroska result retained 1920x800 dimensions and 300.094-second duration while
  shrinking from 119,175,998 bytes to 71,778,716 bytes (39.8 percent smaller).
- The full Maven verification suite, including the generated-media FFmpeg
  integration test, passed before the proof run.

## 2026-08-02 01:57:45 EDT — Group concrete plugins under top-level directory

- Added a nested `plugins/` Maven aggregator and moved the sleep implementation to
  `plugins/sleep-plugin` and the FFmpeg video implementation to
  `plugins/video-ffmpeg-plugin`, preserving their artifact IDs and Java packages.
- Kept `mechana-api` outside `plugins/` as the public plugin contract and left the
  coordinator, worker, runtime, protocol, server, and client modules at the root.
- Updated the root reactor, nested POM parent paths, server default plugin-JAR
  lookup, development instructions, README, repository guidance, and brain files.
- Preserved the dependency direction from concrete plugins to `mechana-api`; the
  server's concrete video-plugin dependency remains an explicit local demo and
  composition boundary.

## 2026-08-02 02:49:36 EDT — Generalize live job dashboard

- Added public `JobObserver` and `WorkUnit` monitoring contracts with normalized
  lifecycle events, weighted progress, worker identity, and opaque display fields.
- Replaced the video-specific monitor and dashboard with a generic in-memory
  coordinator read model and generic server UI/JSON representation.
- Wired scheduler-managed sleep jobs into monitoring across submission, leasing,
  progress, completion, failure, lease expiry, and retry; each submitted job now
  has a loopback-only `/dashboard/jobs/<job-id>` page printed by the client.
- Adapted local and two-host video execution to emit generic events while keeping
  FFmpeg parsing, segment ranges, and duration weights inside the video plugin or
  explicit demo composition layer.
- Kept monitoring in memory and dashboard access loopback-only; durable history,
  fleet/multi-job views, authentication, and remote access remain future work.

## 2026-08-02 03:10 EDT — Add server-wide dashboard and terminal elapsed times

- Added a stable loopback master dashboard at `/dashboard` with connected and
  registered worker counts, worker capabilities/state, and active/completed job
  history linking to the existing per-job dashboards.
- Retain disconnected worker registrations and completed jobs for the lifetime of
  the server process; this is intentionally not durable across restarts yet.
- Freeze job and work-unit elapsed durations at terminal transitions.
- Added deterministic elapsed-time coverage and HTTP coverage for the master
  dashboard read model and page.

## 2026-08-02 03:16 EDT — Establish Mechana development port 8787

- Changed the default server, worker, and client endpoint from generic port 8080
  to Mechana's development port 8787.
- Updated development commands and dashboard examples to use port 8787 while
  preserving explicit port overrides.

## 2026-08-02 03:21 EDT — Add worker address and server runtime metadata

- Extended worker registration and lease polling to advertise the worker's local
  IPv4 address alongside capabilities.
- Added worker IP addresses plus server PID, local date/time, and uptime to the
  master dashboard and JSON read model.
- Treat the advertised address as display metadata rather than authenticated
  worker identity.

## 2026-08-02 03:40 EDT — Show worker activity and current job

- Added `IDLE`, `WORKING`, and `OFFLINE` activity to master-dashboard worker rows.
- Derive current assignments from generic running work-unit snapshots without
  adding plugin-specific knowledge to the server dashboard.
- Link working workers directly to the detailed dashboard for their current job.

## 2026-08-02 04:00 EDT — Persist completed jobs and owned artifacts

- Split the master dashboard into active and completed job sections while keeping
  every row linked to its generic job dashboard.
- Archive terminal job snapshots atomically beneath a configurable server data
  directory and reload them across server restarts.
- Added generic downloadable artifact enumeration; the sleep slice publishes a
  `job-summary.json` artifact for every terminal job.
- Added loopback-only purge controls that delete both the durable job record and
  its server-owned artifact tree, with restart/download/purge HTTP coverage.

## 2026-08-02 04:10 EDT — Add dashboard job abort

- Added confirmed Abort actions to active rows on the master dashboard and to
  active job detail pages.
- Aborting transitions queued and running work units to `CANCELLED`, fences their
  lease tokens so late progress/completion is rejected, and archives the job into
  durable completed history.
- Added scheduler and HTTP coverage for cancellation, lease fencing, archival,
  and terminal dashboard behavior.

## 2026-08-02 04:16 EDT — Dashboard milestone PR checkpoint

- Verified the complete reactor with `mvn verify`, including formatting, SpotBugs,
  dependency convergence, scheduler tests, and dashboard HTTP tests.
- Live-tested an eight-task abort across four MBA and four Mini workers: all work
  units became `CANCELLED`, late leases were fenced, workers returned idle, and
  the terminal record and summary artifact persisted.
- Recorded pause/resume and resumable execution as the next planned slice:
  preserve terminal history, create lineage for resumed terminal jobs, reuse only
  verified completed work units initially, and defer partial-work-unit reuse to an
  explicit plugin checkpoint capability.
