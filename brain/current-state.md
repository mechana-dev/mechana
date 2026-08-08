# Current state

Verified: 2026-08-07

This file reports repository evidence, not desired future status.

## 2026-08-06 long-running native-task reliability

- Worker-presence and task-lease heartbeats use dedicated daemon platform threads,
  isolating lease renewal from virtual-thread scheduling during CPU-heavy native jobs.
- Distributed Blender work units default to one render thread per worker. A positive
  explicit `threads` parameter can override that default when an operator deliberately
  wants more per-task parallelism.
- Blender reports native-process startup, frame-start, and Cycles sample progress, so
  single-frame work units no longer remain visually frozen at 0% until completion.
- Worker Control reaches remote host-agent APIs through authenticated SSH loopback
  forwarding instead of requiring a remotely exposed agent port. Saved system-wide
  Linux paths are migrated to user-writable defaults, macOS bootstrap retries boundedly,
  and Windows reinstall removes obsolete inbound agent firewall rules.
- A host-agent process restart currently does not recreate the previously requested
  workers automatically; the operator must press Start again. Desired-count persistence
  remains follow-up work.

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
- A separate `client-job-launcher` Swing leaf module connects to the development
  server, lists only capabilities advertised by connected workers, renders
  descriptor-defined forms for the five existing plugins, submits through their
  existing endpoints, refreshes live/completed job state, shows worker assignments
  and provider-aware server-local artifact references, aborts jobs, and purges
  selected or all completed server-owned history after confirmation. Descriptors
  currently come from a server-side
  composition catalog rather than plugin manifests; file fields are server-readable
  paths rather than uploads, and remote authenticated operation is not established.
- Launcher capability labels omit transient worker counts. All five submission
  forms expose `Tasks (0 = fleet)` consistently; zero resolves server-side to one
  task per currently compatible worker, capped by the job's finite work units.
  Descriptor forms scroll independently so every field remains reachable when
  the history divider leaves a short submission area.
- Submission descriptors can declare accepted extensions for file fields. The
  generic launcher applies those rules to its file chooser and pre-submit
  validation; OCR currently requires `.pdf` and Blender requires `.blend`.
  Server-side OCR submission independently checks the PDF extension and signature
  and reports invalid input as a client error instead of exposing a PDFBox failure.
- The Blender launcher defaults to a packed, lightweight two-second orbit sample
  at `samples/blender/mechana-camera-orbit-2s.blend`: frames 1–48 at 24 fps with
  continuous camera motion and development-sized 640×360/32-sample settings.
- The host-agent API and Swing controller can start a worker group in explicit
  `SANDBOXED` or `LEGACY` mode with a selected plugin capability set. Sandboxed
  launch is available on macOS, Linux, and Windows and uses an agent-configured root
  home, and is allowlisted to the sleep, FFmpeg video, fractal, Tesseract OCR, and
  Blender plugins. Blender's macOS profile grants I/O Kit device enumeration,
  which is required during Metal backend discovery even for CPU Cycles, and points
  Blender's temporary directories into attempt scratch. A live one-frame CPU
  Cycles render verified this profile on the MBA.
  Status reports the effective mode, plugins, and sandbox root; a running group
  must be stopped before changing its mode or capabilities.
- The desktop controller can provision the host-agent and worker JARs over
  existing batch-mode SSH to a macOS, Linux, or Windows user account, generate the secured
  agent configuration, install a per-user launchd or systemd service, wait for
  agent readiness, and start the requested worker group. It can also stop managed
  workers and unload/disable the remote agent service over SSH. This requires an
  existing Java 25 runtime and SSH trust; it does not install prerequisites, use
  sudo, modify firewalls, or enable Linux lingering. Windows persistence uses a
  per-user Scheduled Task and a worker-owned private Java runtime.
- SSH-provisioned agents bind only to remote loopback and are reached through the
  controller's authenticated SSH tunnel. Their bearer token is optional for
  development; a nonblank saved token retains the additional HTTP authentication.
- The controller treats authenticated agent status as authoritative: it mirrors
  live workers, counts, mode, and plugins and disables worker actions until the
  selected agent responds. It distinguishes an unreachable endpoint from a
  responding agent with rejected credentials. SSH recovery can restart an existing
  service without upload, while reinstall overwrites artifacts/configuration,
  reloads the service, and starts the requested workers. Reinstall discovers
  FFmpeg, FFprobe, Tesseract, and Blender through standard macOS/Linux locations,
  fails before deployment when the configured plugin set lacks a required tool,
  and persists verified absolute paths in the launchd/systemd definition.
- Worker Control persists connection, SSH, launch, plugin, and deployment settings
  independently for each host. Its four development-fleet host profiles are seeded
  with the established SSH users and ports and all five currently supported plugin
  capabilities and the MBA coordinator URL; saved per-host customizations remain
  authoritative. The packaged macOS app includes the current host-agent and worker
  deployment JARs and migrates only the old repository-relative default paths to
  those bundled artifacts; explicit custom paths remain unchanged.
- Windows SSH deployment uses the sandbox backend embedded in the worker JAR; it
  does not require or upload the retired external .NET sandbox-launcher executable.
  Existing saved launcher-path settings are retained only for profile migration
  compatibility and are not used. Windows reprovisioning also migrates the generic
  `~/.mechana/sandbox` default to `C:\ProgramData\Mechana\sandbox`, outside the SSH
  user's home as required by sandbox policy.
- Windows SSH reprovisioning resolves native plugin executables only from the
  sandbox runtime staging tree beneath `C:\ProgramData\Mechana\runtime`. It
  fails before deployment when a requested native capability has not been staged,
  rather than advertising a capability whose system-installed executable the
  AppContainer will reject.
- The server dashboard presents the verified `windows-appcontainer-job` backend
  as `Windows sandbox`, parallel to its macOS and Linux sandbox labels.
- Version-controlled macOS launchers under `scripts/macos` start or reveal the
  local server dashboard, Worker Control, and Client Job Launcher. The installer
  copies those `.command` shortcuts to the current user's Desktop.
- A `jpackage` workflow under `packaging/macos` builds Dock-ready **Mechana
  Server**, **Mechana Worker Control**, and **Mechana Job Launcher** app-image
  bundles with the Mechana icon and a bundled Java runtime. The GUI tools retain
  their existing settings and exit with their main windows. The Server app uses
  the loopback dashboard status API and a per-user, `KeepAlive` LaunchAgent to own
  exactly one background server without tying its lifecycle to the launcher or
  browser. It preserves the prior desktop launcher's data directory when present,
  logs beneath `~/.mechana/logs`, cooperates with dashboard restart, and provides
  explicit no-sudo stop/start/restart/status commands. The three apps use
  function-specific variants of the canonical mark: Worker Control has a blue
  connected-node symbol and Job Launcher has a warm paper-plane symbol. Server owns one dedicated WebKit
  dashboard window: Dock activation reveals the existing window, while closing
  it leaves the LaunchAgent-owned server alive. These local builds are not signed
  or notarized.
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
  runtime manager and separate plugin host on macOS and Linux. Linux selects a
  Bubblewrap namespace backend only after a live capability probe succeeds. The worker stages the host
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
- Abandoned-attempt reclamation reports filesystem access failures as ordinary
  cleanup diagnostics rather than leaking traversal exceptions that can terminate
  a replacement worker during startup.
- An in-memory scheduler for sleep tasks with pull-based workers, renewable leases,
  expired-work requeueing, stale-completion rejection, and a three-attempt ceiling
  for worker-reported failures and expired leases. Exhaustion fails the job and
  fences all unfinished work rather than creating an unbounded native-process
  crash loop.
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
- The master dashboard provides confirmed loopback-only server restart and stop
  actions. Restart inherits the current launch configuration; workers reconnect
  and completed history remains, while volatile active jobs do not survive. In
  the packaged macOS app, stop unloads the per-user LaunchAgent and closes the
  dashboard frontend; launching the Server app starts it again.
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

## Post-sandbox checkpoint status

Architecture Baseline 1 began as a documentation freeze, not an implementation
claim. The repository has since implemented the cross-platform sandbox/runtime
foundation, development-fleet worker management, and generic Client Job Launcher
described above. The active next implementation effort is the **Parallel Plugin
Execution Framework**: stable lifecycle and parallel-plan contracts,
storage-neutral artifact references, scratch/resource contracts, and a minimal
plugin template exercised locally before expanding the distributed path.

The accepted plugin model is now documented as a complete computational contract
in [plugin model](plugin-model.md). This documentation decision does not change the
implementation facts above: the generic plan/work-unit/assemble API and its full
platform ownership boundary are not established by current repository evidence.
The Windows sandbox runs the separate Java plugin host and declared native tools
under a transient AppContainer and Job Object. Hyperion passed the adversarial
enforcement probe, including Java security initialization, plus real sleep,
fractal, Tesseract OCR, FFmpeg compression, and Blender render jobs on attempt one
with Java 25.0.4, Tesseract 5.4.0, FFmpeg 8.1.1, and Blender 4.5.3 LTS. Native
runtime directories receive temporary read/execute Package SID ACLs. Cross-process
filesystem ACL changes are serialized so concurrent AppContainer attempts cannot
overwrite each other's runtime grants. After an attempt exits, the launcher resets
that private workspace's ACLs before Java cleanup, including protected temporary
directories created by Blender. Per-plugin process counts remain bounded.
The backend still reports full filesystem read restriction as absent.

The status-classified [sandbox architecture](sandbox.md) separates accepted
constraints from implemented evidence. It does not elevate the Windows foundation
into certification of full filesystem invisibility, GPU isolation, undeclared
native dependencies, or untested runtime versions and plugin features.
