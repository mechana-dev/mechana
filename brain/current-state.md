# Current state

Verified: 2026-08-04

This file reports repository evidence, not desired future status.

## Present in the repository

- A multi-module Maven build with API, protocol, coordinator, worker, runtime,
  server, and client modules plus a nested `plugins/` reactor containing sleep,
  FFmpeg video, fractal-render, Tesseract OCR, and Blender render plugins.
- Optional `worker-host-agent` and `worker-control-app` leaf modules. The agent
  exposes bearer-token-protected status/start/stop HTTP endpoints by default, with
  an explicit unauthenticated-development opt-in, enforces a
  configured worker limit, and tracks only child workers it launches. The compact
  Swing controller persists known hosts and last settings locally and performs
  network actions away from the event-dispatch thread.
- The host-agent API and Swing controller can start a worker group in explicit
  `SANDBOXED` or `LEGACY` mode with a selected plugin capability set. Sandboxed
  launch is currently macOS-only, uses an agent-configured root outside the user
  home, and is allowlisted to the current sleep, FFmpeg video, fractal, Tesseract
  OCR, and Blender plugins.
  Status reports the effective mode, plugins, and sandbox root; a running group
  must be stopped before changing its mode or capabilities.
- The desktop controller can provision the host-agent and worker JARs over
  existing batch-mode SSH to a macOS or Linux user account, generate the secured
  agent configuration, install a per-user launchd or systemd service, wait for
  agent readiness, and start the requested worker group. It can also stop managed
  workers and unload/disable the remote agent service over SSH. This requires an
  existing Java 25 runtime and SSH trust; it does not install prerequisites, use
  sudo, modify firewalls, enable Linux lingering, or support Windows services.
- The controller treats authenticated agent status as authoritative: it mirrors
  live workers, counts, mode, and plugins and disables worker actions until the
  selected agent responds. It distinguishes an unreachable endpoint from a
  responding agent with rejected credentials. SSH recovery can restart an existing
  service without upload, while reinstall overwrites artifacts/configuration,
  reloads the service, and starts the requested workers. Reinstall discovers
  FFmpeg, FFprobe, Tesseract, and Blender through standard macOS/Linux locations,
  fails before deployment when the configured plugin set lacks a required tool,
  and persists verified absolute paths in the launchd/systemd definition.
- The root POM compiles with Java release 25 and accepts JDK 25 or newer plus
  Maven 3.9+.
- A first plugin-runtime foundation defines trust modes, immutable policy/request/
  result/capability contracts, fixed attempt workspaces, managed-process timeout
  and log capture, and fail-closed platform selection. A separate plugin host
  accepts one NDJSON request, verifies and loads one `TaskPlugin`, and emits
  lifecycle events. The experimental macOS adapter reports workspace write
  restriction, user-home read denial, and network denial after a live
  `sandbox-exec` probe, but does not claim workspace-only read isolation.
- Sandboxed distributed workers route all current concrete plugins through that
  runtime manager and separate plugin host on macOS. The worker stages the host
  runtime, plugin JAR, and remote task inputs under attempt `input/`, streams NDJSON
  progress and artifact events, uploads
  staged outputs with the existing lease fencing, enforces timeout/cancellation at
  the child-process boundary, and cleans the attempt workspace. Sleep and fractal
  require no native runtime; video, OCR, and Blender fail closed unless their
  required absolute executable paths are configured. Attempt ownership
  metadata and OS locks protect active workspaces; graceful worker shutdown waits
  for active cancellation and cleanup, while worker startup reclaims marked,
  unlocked attempts abandoned by a crash. Legacy workers retain the existing
  in-process execution paths for compatibility.
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
- Server startup automatically registers all five current plugin artifacts from
  their standard build outputs. Its positional arguments are limited to port,
  public URL, and data directory; optional packaged-deployment overrides use JVM
  properties rather than plugin-specific command-line positions.
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
- A loopback-only server dashboard at `/dashboard` that removes gracefully disconnected workers immediately and retains heartbeat-timed-out worker
  registrations, advertised IP addresses, connection state, capabilities,
  server PID/date/time/uptime, live active-job rows, and disk-backed completed-job
  rows. Each job row links to its generic job dashboard, and terminal job/work-unit
  elapsed times stop advancing. Worker rows show `IDLE`, `OFFLINE`, or the active
  plugin name plus that worker's current work-unit progress and job link. Workers
  entries for ten seconds after timeout before removing them.
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
- The scheduler-managed video path has been exercised as a 12-segment job across
  four heterogeneous hosts (macOS, Windows, and Linux). This is an operational
  proof of the existing HTTP worker path, not durable cluster orchestration.
- A scheduler-managed pure-Java `fractal-render` path accepts no input artifact,
  deterministically divides a requested image count across an explicit or
  fleet-derived task count, renders batched Mandelbrot/Julia PNGs on compatible
  workers, and reports scanline/image progress through the generic dashboard.
  Lease-fenced batch ZIPs are assembled and validated server-side through plugin
  code. Completed jobs publish every PNG plus a manifest, contact sheet, and
  complete collection ZIP as durable downloadable artifacts.
- A scheduler-managed `ocr-tesseract` path accepts a server-local PDF, rasterizes
  grayscale pages server-side with PDFBox, deterministically batches pages, sends
  workers only their assigned images, invokes external Tesseract with cancellation
  and timeout handling, and assembles ordered Markdown, Unicode LaTeX source,
  and raw page text as durable artifacts.
- A scheduler-managed `blender-render` path accepts one server-local packed
  `.blend`, deterministically batches an explicit frame range, renders PNG frames
  through headless CPU-only Cycles on workers, validates the complete ordered frame
  set, assembles an H.265 MP4 with FFmpeg, and archives the final movie. A
  three-worker MBA proof rendered one Junkshop frame per worker and completed
  upload, assembly, and publication. Blender 4.5 LTS is installed on all four
  fleet hosts. A subsequent 12-frame smoke job assigned one CPU Cycles frame to
  each of twelve workers across the MBA, Rocinante, Hyperion, and Linux host,
  then validated and assembled all distinct frames into a QuickTime-compatible
  HEVC MP4. The host-agent-managed development fleet has also completed a
  96-frame, 48-work-unit render across twelve workers on the MBA, Rocinante, and
  Linux host, producing the validated HEVC movie in 6 minutes 42 seconds.

## Not established by current repository evidence

- A generic plan/partition/assemble API or artifact abstraction.
- Scratch-space advertisement, reservations, or capacity-aware matching.
- Content-addressed/cached media input transfer, durable intermediate transfer,
  or reserved worker scratch. Distributed video currently creates and serves
  ephemeral keyframe-aligned chunks from server-local scratch.
- Durable active scheduler state, authentication, or production isolation.
- Durable worker presence across restarts, richer fleet telemetry, authentication,
  or remote dashboard access.
- A runtime manifest, bounded log sizes, guaranteed descendant cleanup after abrupt worker death,
  periodic stale-attempt scavenging, hard CPU/RAM/
  scratch/process limits, dedicated identities, production sandboxing, or
  certification. `sandbox-exec` is deprecated and unavailable beneath the MBA's
  current Codex containment. Native FFmpeg/FFprobe, Tesseract, and Blender
  executables require explicit absolute system properties before sandbox launch.
- Durable host-agent child-process adoption after an agent restart, TLS, token
  rotation, role-based access, Windows/system-wide service installers, Java
  runtime deployment, or firewall/SSH bootstrap automation.
- Generic cross-plugin resume validation, reusable completed-work artifact
  manifests, or plugin-defined mid-work-unit checkpoints. Resume-as-new is
  currently limited to scheduler-managed sleep jobs and reuses their logical
  completion state rather than a generic artifact contract.

These are accepted direction where listed in [decisions](decisions.md) and planned
work where listed in [roadmap](roadmap.md); they must not be described as shipped.

## Architecture Baseline 1 status

Architecture Baseline 1 is a documentation freeze, not an implementation claim.
Implementation is intentionally paused at this checkpoint. The next implementation
iteration is the **Parallel Plugin Execution Framework**: stable lifecycle and
parallel-plan contracts, storage-neutral artifact references, scratch/resource
contracts, and a minimal plugin template exercised locally before expanding the
distributed path.

The accepted plugin model is now documented as a complete computational contract
in [plugin model](plugin-model.md). This documentation decision does not change the
implementation facts above: the generic plan/work-unit/assemble API and its full
platform ownership boundary are not established by current repository evidence.
The status-classified [sandbox architecture](sandbox.md) likewise records design
constraints only; none of its planned isolation guarantees are established by
this repository today.
