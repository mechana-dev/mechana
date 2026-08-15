# Project notes

## 2026-08-05 05:05:00 EDT — Recover from stale manually launched host agents

- Made macOS reinstall detect a stale Mechana host-agent listener left outside the
  managed launchd service, terminate it gracefully, and force it down after timeout.
- Restricted cleanup to commands identifying `mechana-worker-host-agent.jar`; an
  unrelated owner of the configured port fails safely with an explicit diagnostic.

## 2026-08-05 04:50:00 EDT — Render ports without grouping separators

- Formatted the agent HTTP and SSH port spinners as plain integer identifiers so
  values display as `8790` and `21012`, never `8,790` or `21,012`.
- Added a Swing formatting regression test for the custom SSH port.

## 2026-08-05 04:40:00 EDT — Keep SSH diagnostics out of command results

- Separated SSH/SCP stderr from stdout so OpenSSH security warnings no longer
  corrupt remote `uname`, home-directory, or Java-path detection.
- Preserved stderr in nonzero-exit diagnostics and added process-level regression
  tests for successful warnings and failed commands.

## 2026-08-05 04:25:00 EDT — Support custom remote SSH ports

- Added a persisted SSH port field to Worker Control, separate from the host-agent
  HTTP port.
- Applied the selected port with the correct OpenSSH syntax for both `ssh -p` and
  `scp -P`, with regression coverage for custom-port deployment commands.

## 2026-08-05 03:50:00 EDT — Stabilize agent restart and desired worker count

- Kept the desired worker spinner independent from the agent's current requested
  count so an online agent with zero workers no longer erases the user's selection.
- Changed macOS SSH restart to use launchd `kickstart -k` first and bootstrap only
  as recovery, avoiding both an unload/reload race and inconsistent launchd
  presence checks during transient service states.

## 2026-08-05 03:35:00 EDT — Agent-aware controls and SSH recovery

- Made authenticated agent status authoritative for the Worker Control display,
  including live worker records, counts, execution mode, and plugin capabilities.
- Disabled worker start/stop actions until the selected agent responds successfully;
  unreachable and HTTP-rejected agents now have distinct visible states.
- Added SSH-based clean agent restart for hung or unreachable HTTP endpoints and
  clarified that deployment reinstalls files/configuration, reloads the service,
  and starts the requested worker group.
- Added macOS restart-path regression coverage using the resolved remote home.

## 2026-08-05 02:35:00 EDT — Provision worker hosts over SSH

- Added an SSH provisioning workflow to the desktop controller for macOS and
  Linux user accounts. It uses the local OpenSSH client and existing SSH agent or
  identity file, retains strict host-key checking by default, and never stores an
  SSH password or requests sudo.
- The controller detects the remote OS and Java path, uploads the host-agent and
  worker JARs plus generated secured configuration, and installs a per-user
  launchd or systemd service. After the agent becomes reachable it starts the
  requested worker count, execution mode, and plugin selection.
- Added a remote stop action that requests graceful worker shutdown when possible
  and then unloads/disables the agent service over SSH. Install files remain for
  restart or upgrade.
- Added deterministic tests for SSH/scp command construction, batch and host-key
  options, platform rejection, generated configuration, and launchd/systemd
  templates. Java installation, SSH bootstrap, firewalls, Linux lingering,
  Windows services, and system-wide/root installation remain out of scope.

## 2026-08-06 14:05:00 EDT — Validate the Windows AppContainer sandbox foundation

- Added a Windows backend using a transient AppContainer Package SID, scoped NTFS
  ACLs, inherited-handle allowlisting, and a Job Object with memory, CPU-rate,
  process-count, and kill-on-close controls.
- Installed and selected a private Java 25 runtime under
  `C:\ProgramData\Mechana\runtime\java-25`; the enhanced Hyperion probe verified
  workspace writes, outside-write and home-read denial, live network denial,
  Java security initialization, timeout, cancellation, crash recovery, and the
  declared resource controls.
- Confirmed coordinator-to-worker-to-AppContainer-to-plugin-host execution with
  fractal job `bb773a9a-80d4-4fce-b4d3-6100e3b7c186`, which succeeded on attempt
  one. Full filesystem read invisibility and native Windows plugin certification
  are not claimed.

## 2026-08-06 14:39:00 EDT — Certify all current plugins on Hyperion

- Staged the existing FFmpeg 8.1.1, Tesseract 5.4.0, and Blender 4.5.3 LTS
  installations under `C:\ProgramData\Mechana\runtime` so AppContainer grants do
  not weaken user-home denial or expose broad installation trees.
- Added explicit read-only native-runtime paths to `SandboxRequest`; the Windows
  launcher grants their directories read/execute access to the transient Package
  SID and removes every grant after the attempt.
- Replaced the single hardcoded Job Object process limit with bounded plugin
  profiles: one process for Java-only work, four for FFmpeg/Tesseract, and sixteen
  for Blender. The original OCR test correctly failed at the one-process limit;
  the corrected job succeeded on attempt one.
- Verified executable probes and real distributed jobs for sleep
  (`604b8e29-77ca-4cf2-818c-6f3a5963b9dd`), fractal
  (`2de64c00-7eb0-4068-bc36-ba6d94b7fde2`), OCR
  (`3029b1b0-43c3-4f00-ba11-0876a9e51093`), FFmpeg
  (`e22e011f-ddf5-40c5-b2aa-547764dd5222`), and Blender
  (`5af68039-8115-4527-aa3f-9a5ea9f06478`). All final certification jobs
  succeeded on attempt one in sandboxed mode.
- Audited all private runtime ACLs after completion: no transient AppContainer
  Package SID remained. Attempt workspaces were cleaned and stale probe artifacts
  were removed.

## 2026-08-06 14:48:00 EDT — Keep Windows worker plugin display compact

- Filtered internal `sandbox.backend.*` and `sandbox.control.*` capability markers
  from the dashboard's Plugins column, matching the concise plugin-only display
  used for workers before the Windows capability matrix was introduced.
- Preserved the complete capability set in the dashboard API and scheduler state;
  this is a presentation-only change.

## 2026-08-05 02:25:00 EDT — Control sandboxed workers from the desktop app

- Extended the host-agent start API with explicit `SANDBOXED` and `LEGACY`
  launch modes plus a per-group plugin capability selection.
- Added agent configuration for the sandbox root and the allowlist of migrated
  sandbox plugins. Sandboxed launch validates macOS, rejects roots beneath the
  user home, rejects capabilities outside that allowlist, and passes the root to
  every worker JVM.
- Updated the desktop controller with mode and plugin controls and with status
  reporting for the effective mode, plugins, and sandbox root. Changing a running
  group's mode or plugin set requires stopping it first.
- Kept non-migrated plugins available only through the explicitly labeled legacy
  mode; the app does not claim they are sandboxed.

## 2026-08-05 02:10:00 EDT — Harden sandbox cleanup and crash recovery

- Added per-attempt ownership metadata and an operating-system file lock so
  multiple workers sharing one sandbox root can distinguish live attempts from
  abandoned ones without trusting stale PID data alone.
- Normal attempt close now removes the complete attempt directory and its empty
  job parent. Worker startup reclaims only marked attempts whose ownership lock
  can be acquired; active and unmarked directories are preserved.
- Graceful worker disconnect now signals the active sandbox cancellation token
  and waits up to ten seconds for host termination and attempt cleanup before
  reporting the worker disconnected.
- Added tests for normal recursive cleanup, active-lock protection, and abandoned
  workspace recovery. Abrupt host-process descendant containment and periodic
  scavenging remain future hardening work.

## 2026-08-05 01:10:00 EDT — Run fractal work through the macOS sandbox host

- Wired distributed `fractal-render` assignments through the runtime manager,
  separate plugin-host JVM, and experimental macOS backend while retaining the
  existing in-process path for plugins not yet migrated.
- Added framed stdin delivery and live stdout event consumption to the managed
  process runtime. Plugin stdout is separated from the NDJSON protocol, and the
  worker translates progress and artifact events into the existing lease-fenced
  server calls.
- Staged the worker host runtime and plugin JAR under each attempt's `input/`
  directory so Tahoe can deny reads beneath the user's home without granting an
  exception for the repository or Maven cache. Explicitly bound Java temporary
  files to `work/`.
- Verified `mvn verify` on the MBA, including four macOS policy integration tests.
  Then completed job `94185b72-47c7-4335-982d-47133177ee42` with eight images in
  four tasks: four distinct sandbox workers each succeeded on attempt one and the
  server assembled the job at 100%.
- The verified macOS controls remain home-directory read denial, network denial,
  workspace write restriction, and wall-clock timeout. General system/runtime
  reads remain available; CPU, memory, scratch-size, process-count, and guaranteed
  descendant-tree limits are not enforced.

Append-only record of material Mechana project changes and accepted decisions.

## 2026-08-04 11:52:29 EDT — Complete Architecture Baseline 1 sandbox design

- Established [sandbox architecture](../brain/sandbox.md) as the canonical,
  status-classified design for plugin runtime isolation and explicitly kept it a
  documentation contract rather than an implementation claim.
- Accepted the trusted, managed, and sandboxed trust levels; the worker-owned
  plugin runtime manager and platform-specific sandbox runtime boundary; logical
  workspace/input/output/work/log access; platform-owned resource enforcement;
  native-tool containment; and sandboxed plugin-author expectations.
- Reaffirmed that Mechana claims only named, verified OS-enforced guarantees and
  uses distinct Linux, Windows, and macOS implementations behind a common API,
  without promising identical guarantees.
- Proposed a versioned runtime manifest for network, filesystem, CPU, memory,
  scratch, timeout, process, native-runtime, GPU, environment, and reuse needs.
- Set direction for adversarial compliance testing and multi-dimensional,
  platform-specific certification. Deferred production sandbox code, arbitrary
  host paths, runtime distribution/signing, certification services, and a plugin
  marketplace.
- Updated the README, repository guidance, brain index, architecture, decisions,
  plugin model, author guide, roadmap, current state, and glossary to link to the
  canonical document rather than duplicate its detailed contract.

## 2026-08-04 08:25:31 EDT — Freeze Architecture Baseline 1

- Defined Mechana as a **plugin-driven distributed computation platform**, not
  merely a job runner. Plugins describe complete computations; Mechana supplies
  distributed execution.
- Fixed ownership: plugins define inputs, outputs, options, validation,
  deterministic planning, resource estimates, one-work-unit execution, ordered
  assembly, and final validation. Mechana owns IDs, persistence, scheduling,
  worker selection, networking, artifact transfer/integrity, scratch reservation,
  leases, retries, attempt fencing, cancellation propagation, progress aggregation,
  cleanup, and retention.
- Froze `plan -> parallel work units -> assemble`, including one-unit atomic work,
  and rejected a general DAG for this baseline. Planning and assembly start
  server-side while contracts preserve a later client-side option.
- Reaffirmed control-plane/data-plane separation. Scheduling carries metadata and
  references; artifact bytes move through providers. Artifacts have stable,
  content-verifiable identity, immutable attempt inputs, atomic publication,
  explicit ordering, and ownership-aware cleanup.
- Accepted bounded worker caching and locality-aware scheduling as optimizations.
  Cached copies are non-authoritative and evictable. Plugins declare requirements;
  Mechana owns transfer, verification, cache accounting/eviction, and placement.
- Defined worker resources as CPU, RAM, scratch, plugin capabilities, runtime
  signatures/health, enforceable trust features, and later cache hints. Plugins
  estimate; the scheduler matches and atomically reserves. Attempt scratch covers
  inputs, intermediates, pending outputs, and safety allowance and is always
  released/cleaned. Cache is accounted separately.
- Preserved external FFmpeg/FFprobe media execution: validate H.264 input, plan
  deterministic keyframe-aligned time segments, advertise runtime capabilities,
  execute normalized H.265 quality profiles, assemble ordered compatible outputs
  with the explicit audio strategy, and validate using FFprobe. The scheduler has
  no media semantics.
- Reframed OCR as document processing. Future page layout, figures, captions,
  tables, confidence/warnings, Markdown, HTML, searchable PDF, and structured
  multi-artifact output remain plugin-owned within the common lifecycle.
- Distinguished installed plugin package, reusable plugin runtime, and leased
  work-unit attempt. Accepted a runtime-manager/sandbox-launcher boundary so
  managed and sandboxed code runs outside the worker while attempt authority,
  resources, artifacts, and scratch remain isolated.
- Defined **trusted**, **managed**, and **sandboxed** modes. Sandboxed means verified
  OS enforcement, not process separation. Linux, Windows, and macOS use
  platform-specific mechanisms and truthful guarantee matrices; network is denied
  by default and unimplemented protection is never advertised.
- Elevated third-party authoring to a first-class subsystem: small SDK contracts,
  buildable templates, simulator, verification/certification, documentation,
  packaging, and vendor-neutral `plugin-definition.yaml` and `plugin-context.md`.
  These support direct and AI-assisted work with ChatGPT, Claude, Gemini, Copilot,
  or future tools. Generated code receives normal review, tests, and trust policy.
- Recorded permanent values: simplicity before sophistication; plugins describe
  computation; infrastructure owns distributed systems; responsibilities stay
  cognitively separate; complexity earns its place; architecture is readable by
  humans and AI; operators understand contributed resources and real guarantees.
- Added and cross-linked the author guide, sandbox/runtime architecture,
  worker-management model, and normative lifecycle. Updated the README, agent
  guidance, decisions, artifacts, scheduler, OCR direction, current state, and
  roadmap without adding implementation or claiming planned contracts are shipped.
- Set the next implementation iteration to the **Parallel Plugin Execution
  Framework**: lifecycle/plan contracts, artifact references, scratch/resource
  contracts, and a minimal local plugin template.

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

## 2026-08-02 04:35 EDT — Add pause/resume and sleep-job revival

- Added cooperative pause/resume for active sleep jobs. Pause stops assignment,
  fences active leases, retains succeeded units, and freezes elapsed time; resume
  keeps the job ID and queues only unfinished units.
- Added **Resume as new** for cancelled and failed sleep jobs. The source terminal
  record remains immutable, the new job records `resumedFromJobId`, succeeded
  units are reused, and only incomplete units run again.
- Added master and job-dashboard controls, linked source lineage on resumed job
  details, HTTP endpoints, lease-fencing coverage, and deterministic monitor and
  scheduler tests.
- Kept the scope honest: interrupted work units restart from zero, and generic
  artifact-backed reuse plus plugin checkpoints remain future platform contracts.

## 2026-08-02 04:45 EDT — Add dashboard server restart

- Added a confirmed **Restart server** control to the master dashboard and a
  loopback-only restart endpoint.
- The replacement process uses the same Java runtime, server artifact, port,
  plugin path, public URL, and durable data directory.
- Workers reconnect automatically and completed history remains on disk; active
  scheduling state remains volatile and is explicitly described as lost.
- Added HTTP coverage for the configured restart action without spawning a real
  replacement process during tests.

## 2026-08-02 05:27 EDT — Add variable sleep work and scheduled video segments

- Extended sleep submission with per-work-unit durations while preserving the
  existing uniform-duration request and CLI.
- Added generic leased-task parameters and worker artifact publication to the
  public execution context and HTTP protocol.
- Added an initial server-managed distributed video path: server-side leading
  clip/probe/keyframe planning, eight capability-matched `video-ffmpeg` leases,
  server-mediated input download, lease-fenced segment upload, server assembly,
  smaller-than-input validation, and durable final artifact publication.
- Kept the older two-host runner as a separate manual proof. The new scheduled
  path can genuinely queue video segments behind occupied workers.
- Recorded current limitations: each segment downloads the whole clipped input;
  active and intermediate state remains volatile; scratch is not reserved; input
  transfer is not yet content-addressed or cached.

## 2026-08-02 05:54 EDT — Separate worker presence from task leases

- Added a dedicated worker-presence heartbeat that runs while the worker is idle,
  staging data, executing plugin code, or publishing artifacts.
- Increased the dashboard offline threshold from three to fifteen seconds while
  retaining the scheduler's separate five-second task-lease policy.
- Added lease-token heartbeats that renew an active attempt without changing its
  reported progress; stale tokens remain fenced.
- Removed artifact-upload progress calls as a substitute for liveness and added
  scheduler plus HTTP coverage for the two heartbeat paths.

## 2026-08-02 06:38 EDT — Send only assigned video chunks to workers

- Diagnosed remote video tasks that appeared alive at zero percent: they were
  slowly downloading the entire three-minute clipped input before FFmpeg could
  start, once independently for every segment.
- Changed server-side video planning to stream-copy every keyframe-aligned range
  into its own temporary MP4 without re-encoding.
- Each scheduled work unit now receives a unique URL for only its input chunk and
  encodes that local chunk from timestamp zero. Encoded outputs retain their
  planned indices for deterministic server-side assembly.
- Registered chunk URLs and scratch files are removed on planning failure, job
  completion, abort, or purge through the existing video-job cleanup path.
- Added command-construction coverage proving input partitioning uses stream copy
  rather than HEVC encoding. Content-addressed caching remains future work.

## 2026-08-02 06:53 EDT — Enrich worker and completed-job dashboards

- Replaced the generic `WORKING` worker activity label with the active plugin ID
  and added the assigned work unit's live percent complete while retaining its
  job-dashboard link.
- Added an ISO terminal timestamp to generic job snapshots and displayed it for
  successful, failed, and cancelled jobs. Older snapshots fall back to their
  durable snapshot-file timestamp when loaded.
- Added a loopback-only **Show in Finder** action to completed-job artifact
  sections while retaining browser download links and purge controls.
- Kept all worker activity/progress derivation in the generic platform read model;
  no concrete plugin logic was added to the dashboard.

## 2026-08-02 07:05 EDT — Verify distributed video and prepare dashboard PR

- Verified a 12-task encode of the first two minutes of the reference movie across
  four MBA and four Mini workers through the scheduler-managed video path.
- Confirmed workers on both hosts received server-created input chunks, reported
  live encode progress, and returned to the shared queue for later segments.
- Confirmed the generic master and job dashboards exposed plugin type, per-worker
  progress, segment ranges, terminal timestamps, downloadable artifacts, and the
  loopback-only local-folder action without video-specific presentation logic.
- Reconciled the brain with the configurable scheduled segment count and prepared
  the cumulative dashboard, lifecycle, liveness, and distributed-video changes for
  reactor verification and pull-request review.

## 2026-08-02 07:22 EDT — Add distributed fractal collection plugin

- Added a pure-Java `fractal-render` plugin for deterministic, no-input,
  CPU-intensive Mandelbrot and Julia collection jobs.
- Added explicit or fleet-derived task counts, deterministic batched image ranges,
  scanline progress, cancellation, and lease-fenced batch artifact publication.
- Added plugin-owned final assembly that validates the complete PNG set and
  publishes individual images, a JSON manifest, contact sheet, and collection ZIP
  through the durable generic artifact view.
- Added client submission support, server/plugin composition wiring, focused
  renderer/assembler coverage, server-level artifact-flow coverage, development
  instructions, and the durable design summary in `brain/fractal-plugin.md`.
- Process-smoke-tested the dynamically downloaded plugin on an isolated server and
  worker: two batches produced four PNGs, live progress, a manifest, contact sheet,
  collection ZIP, and durable completed-job artifact links.

## 2026-08-02 08:04 EDT — Validate fractal plugin across the two-host fleet

- Completed a 120-image 1080p run across 12 ten-image tasks and all eight MBA/Mini
  workers, including four queued follow-on tasks and durable final collection.
- Completed a 160-image 4K run across 16 ten-image tasks in 12 minutes 34 seconds.
  The terminal job contained 163 artifacts: every PNG, manifest, contact sheet,
  job summary, and a 476,201,792-byte collection ZIP.
- Corrected the live server's advertised plugin URL from loopback to its LAN address
  before the accepted runs so Mini workers downloaded the authoritative plugin from
  the MBA server; the invalid configuration attempt was cancelled and not treated
  as validation evidence.

## 2026-08-02 08:40 EDT — Add initial distributed Tesseract OCR plugin

- Added the `ocr-tesseract` plugin with external-process execution, runtime/language
  probing, cancellation, timeouts, per-page progress, and batch publication.
- Added server-side PDFBox rasterization and deterministic batching so workers
  receive only assigned PNG pages and need no PDF tools or source PDF access.
- Added Markdown/raw-text assembly, durable artifacts, client and server wiring,
  focused tests, an optional real-Tesseract integration test, and server workflow
  coverage.
- Made Maven test JVMs explicitly headless to match non-GUI worker operation and
  prevent macOS AWT startup crashes.

## 2026-08-02 09:50 EDT — Add LaTeX OCR output

- Extended OCR assembly to publish both `document.md` and Unicode `document.tex`
  while retaining every raw page text artifact.
- Added deterministic LaTeX escaping and page boundaries without adding a TeX
  runtime requirement to servers or workers; compilation remains optional and
  external to the job.
- Added focused escaping/assembly coverage and server artifact-flow verification.
- Added a TeXShop engine directive after live compilation exposed that TeXShop
  otherwise defaulted to incompatible pdfLaTeX; the 50-page NASA artifact was
  then verified to compile successfully with XeLaTeX.

## 2026-08-03 03:13 EDT — Prune stale workers from the server dashboard

- Kept disconnected workers visible briefly for operational context, then removed
  them from the server presence registry after two minutes without contact.
- Added deterministic server-dashboard coverage for the retention boundary and
  documented the distinction between the 15-second offline threshold and the
  two-minute removal threshold.

## 2026-08-03 04:36 EDT — Remove synthetic page headings from OCR PDF output

- Removed the bold `Page N` section heading from assembled OCR LaTeX while
  retaining source-page boundaries with explicit page breaks.
- Kept Markdown page headings for navigation and added focused assembly coverage
  that prevents the visible LaTeX headings from returning.

## 2026-08-03 04:51 EDT — Add persistent Linux cloud worker

- Provisioned Ubuntu 24.04 host `srv959600` with OpenJDK 25, FFmpeg/libx265,
  Tesseract 5, and English trained data, then deployed the current worker JAR.
- Added and enabled a restart-on-failure `systemd` service for worker
  `srv959600-1`, advertising all four current plugin capabilities and connecting
  to the MBA server over Tailscale.
- Verified the service running and heartbeating on the server dashboard. Ubuntu's
  hostname resolution currently makes its presentation address `127.0.1.1`; task
  and artifact traffic still uses the configured Tailscale server endpoint.

## 2026-08-03 04:55 EDT — Normalize the live fleet to three workers per host

- Replaced the existing worker processes with three workers each on the MBA,
  Rocinante, Hyperion, and `srv959600`, all using the MBA Tailscale endpoint.
- Converted the Linux worker service to a three-instance `systemd` template and
  verified exactly 12 connected workers across the four hosts.

## 2026-08-03 05:10 EDT — Verify four-host distributed video execution

- Completed a scheduler-managed, 12-segment HEVC job across three workers on each
  of four heterogeneous hosts spanning macOS, Windows, and Linux.
- Diagnosed repeated zero-progress retries on both macOS hosts as service-launch
  environments that omitted the Homebrew FFmpeg directory from `PATH`; restarted
  those workers with explicit executable paths and confirmed their segments made
  progress and completed.
- Submitted the following two-minute slice with the corrected fleet and verified
  all 12 tasks were leased on their first attempt across all four hosts.
- Recorded the remaining hardening gap: advertised plugin capability does not yet
  include a live runtime preflight for required external executables.

## 2026-08-03 06:05 EDT — Add distributed Blender animation rendering

- Added a `blender-render` plugin with deterministic contiguous frame planning,
  safe headless CPU Cycles command construction, external-process progress,
  cancellation/timeouts, PNG validation, and lease-fenced frame archives.
- Added server/client wiring for packed `.blend` submission, server-mediated scene
  transfer, generic scheduling and dashboards, complete-frame validation, FFmpeg
  H.265 movie assembly, and durable final MP4 publication.
- Added command/planner/assembly tests that do not require Blender and documented
  the narrow first contract and remaining runtime-preflight, caching, GPU, audio,
  simulation, and per-frame-checkpoint limitations in `brain/blender-plugin.md`.
- Prepared a 240-frame, ten-second camera fly-through from Blender's CC0 Junkshop
  benchmark as a single packed test input and verified representative Cycles frames
  locally with Blender 4.5.3 LTS.
- Completed an end-to-end proof as job
  `75cf4854-29c4-4c02-a593-a49297d34b19`: three MBA workers each rendered one
  frame at 640x360 and 8 samples, uploaded lease-fenced archives, and the server
  validated and assembled a 24 fps HEVC MP4 in 2 minutes 44 seconds.
- Confirmed the resulting MP4 as HEVC, 640x360, 24 fps, three frames, and 29,472
  bytes. The Mini and Linux fleet nodes did not have Blender installed; the
  Windows runtime was not established because its Tailscale alias had a saved
  host-key mismatch.
- Corrected FFmpeg movie assembly to tag HEVC MP4 output as `hvc1`; the original
  `hev1` sample entry was valid to FFprobe but rejected by QuickTime Player.
- Enabled Blender persistent render data within each frame batch so camera-only
  animation does not unnecessarily rebuild the heavy Cycles scene for every frame.
- Completed a visible one-second preview as job
  `5ffe9df0-864d-4368-a567-891975586316`: three MBA workers rendered eight frames
  each from a 24-frame packed Junkshop camera move at 640x360 and 8 samples. The
  job completed in 18 minutes 34 seconds and published a 24 fps, 24-frame, `hvc1`
  HEVC MP4 with 24 distinct decoded frames.
- Provisioned Blender 4.5 LTS across the complete four-host worker fleet: MBA
  4.5.3, Intel Rocinante 4.5.12 through Homebrew, Hyperion 4.5.3 from the official
  Windows installer, and `srv959600` 4.5.3 from the official self-contained Linux
  archive. Added the minimal Linux headless shared libraries and explicit Blender
  executable paths to each worker environment.
- Restarted three workers per host against the MBA Tailscale endpoint and verified
  all twelve connected workers advertise `blender-render` alongside the existing
  sleep, video, fractal, and OCR capabilities. A four-host Blender render remains
  the next operational proof.
- Replaced Hyperion's SSH-child worker launches with three persistent SYSTEM
  Scheduled Tasks that start at boot and restart on failure; the initial transient
  processes correctly connected but were terminated when their SSH session closed.

## 2026-08-03 07:30 EDT — Complete twelve-worker Blender smoke proof

- Made the worker render command explicitly select CPU Cycles, independent of the
  render engine saved in the submitted `.blend`, and added command-level coverage.
- Created a small packed 12-frame animated scene and submitted job
  `accf1dd0-95f7-4f9d-8b16-83ba74dbfc9e` as twelve one-frame work units.
- Verified one first-attempt work unit ran on every connected worker: three each
  on MBA, Rocinante, Hyperion, and `srv959600`.
- The job succeeded in 19 seconds and published a 54,212-byte, 640x360, 12 fps,
  one-second HEVC MP4 tagged `hvc1`. FFprobe reported all 12 frames, and decoded
  frame hashes confirmed all 12 frames were distinct.

## 2026-08-03 08:14 EDT — Compare one-worker and twelve-worker Blender renders

- Rendered the same packed 96-frame orbital scene twice at 960x540, 24 fps, and
  16 CPU Cycles samples, measuring server wall-clock time through final assembly.
- Job `14f80899-03e3-4329-9e33-57b8eb5dad9f` used only `mba-1` and completed in
  7 minutes 29 seconds. Job `5fb71e47-5f1c-4765-9e02-9d604675f68b` used twelve
  eight-frame batches, one per fleet worker, and completed in 6 minutes 19 seconds.
- The heterogeneous fleet saved 70 seconds, a 15.6% elapsed-time reduction and
  approximately 1.18x speedup. MBA batches finished in 2:15–2:23, while the
  terminal Hyperion batch took 6:14; static equal-size partitioning therefore
  made the slowest worker the makespan bottleneck.
- Both outputs validated as 96-frame, four-second, 960x540, 24 fps, `hvc1` HEVC
  movies. This establishes a need for smaller dynamic batches or capability-aware
  frame allocation before expecting strong heterogeneous-fleet scaling.

## 2026-08-03 08:22 EDT — Measure dynamic Blender frame batching

- Repeated the 96-frame benchmark as job
  `be8d1e76-44c2-428a-968c-6bfa9e43e2af` using 48 two-frame work units across
  the same twelve workers. All 48 work units succeeded on attempt one and final
  server wall-clock time was 5 minutes 38 seconds.
- Dynamic pickup reduced elapsed time by 41 seconds (10.8%) versus twelve static
  eight-frame batches and by 1 minute 51 seconds (24.7%) versus one MBA worker;
  the corresponding speedups were approximately 1.12x and 1.33x.
- Faster workers naturally accepted more work: the three MBA workers completed
  eight batches each, the Rocinante and Linux workers three each, and Hyperion
  workers two each. This confirms fine-grained pull scheduling improves balance
  without requiring a priori host performance weights.
- The final artifact validated as a 96-frame, four-second, 960x540, 24 fps,
  `hvc1` HEVC movie. Per-batch Blender startup and scene loading still limit
  scaling, so batch size remains a throughput/latency tradeoff.

## 2026-08-04 03:50 EDT — Add optional worker host management

- Added independent `worker-host-agent` and `worker-control-app` Maven leaf
  modules without changing scheduler, worker, protocol, or plugin dependencies.
- Implemented bearer-token-protected HTTP status/start/stop management for only
  agent-launched child workers, including max-count enforcement, graceful then
  forced shutdown, per-worker PID/start-time status, diagnostics, and log files.
- Added a compact Swing controller with editable persisted hosts, port/token/count
  settings, off-event-thread actions, aggregate state, and per-worker display.
- Added fake-process and HTTP client/authentication tests plus Windows and
  macOS/Linux setup guidance. Remaining security and durability limits are
  recorded in [worker management](../brain/worker-management.md).

## 2026-08-04 04:20 EDT — Correct worker-status time serialization

- Added Jackson's Java-time module to both shaded worker-management applications
  so non-empty worker status responses serialize and deserialize `Instant` start
  times correctly.
- Extended the authenticated host-agent HTTP test to start a fake worker and
  require its timestamp-bearing response, closing the gap that allowed empty
  status responses to pass while live-worker responses failed.
- Deployed the corrected agent to Rocinante and verified a real authenticated
  start, status, and stop cycle; the test worker was stopped afterward.

## 2026-08-04 04:30 EDT — Identify managed workers by host

- Changed host-agent worker IDs from opaque `managed-<UUID>` values to
  `<machine-name>-<UUID>`, with a configurable machine name and normalized local
  hostname default, plus manager and HTTP regression coverage.
- Reduced server worker-presence retention from two minutes to ten seconds and
  updated deterministic dashboard coverage and current architecture notes. The
  independent 15-second offline threshold remains unchanged.

## 2026-08-04 04:38 EDT — Deploy host agents across the active development fleet

- Added `allow-unauthenticated=true` as an explicit development-only escape hatch
  for blank-token non-loopback agents; secure-by-default validation remains and is
  covered by a configuration test.
- Replaced the MBA's three transient keepalive launchd workers with one
  `dev.mechana.worker-host-agent` job and replaced Linux's three worker template
  services with one enabled `mechana-worker-host-agent.service`.
- Updated Rocinante to the same no-token development configuration. Verified all
  three APIs without authorization headers and completed temporary start/stop
  cycles producing `mba-...` and `srv959600-...` worker IDs.

## 2026-08-04 04:42 EDT — Separate graceful and timed-out worker removal

- Changed the server presence registry to remove a worker immediately when it
  sends its graceful disconnect request.
- Preserved failure detection as a separate path: fifteen seconds without a
  heartbeat marks the worker disconnected, and its offline row remains for ten
  additional seconds before removal.
- Added deterministic dashboard coverage for both immediate graceful removal and
  delayed heartbeat-timeout retention.

## 2026-08-04 04:56 EDT — Auto-refresh the worker controller

- Made the Swing worker controller check the selected host automatically after
  startup and whenever the editable host selector changes, while retaining the
  manual Refresh button.
- Suppressed initialization/list-maintenance events and added request-generation
  fencing so a slower response from a previously selected host cannot overwrite
  the newer host's status.

## 2026-08-04 05:14 EDT — Validate host-agent-managed distributed Blender render

- Corrected development-host Blender runtime configuration and installed the
  Linux EGL runtime required by headless Blender; these are fleet deployment
  changes rather than repository dependencies.
- Verified a 12-frame smoke job used one worker on each of the twelve active
  workers across the MBA, Rocinante, and Linux host.
- Completed a 96-frame, 48-work-unit CPU Cycles render at 960x540 and 24 samples
  across the same three hosts. All work units succeeded without retries or
  failures, server-side assembly produced `animation.mp4`, and total elapsed time
  was 6 minutes 42 seconds.
- Dynamic scheduling assigned 30 work units to the MBA, 8 to Rocinante, and 10 to
  the Linux host, demonstrating that all nodes participated while faster workers
  accepted additional batches.

## 2026-08-04 11:35:01 EDT — Record distributed storage and AI-authoring direction

- **Accepted** storage as a first-class abstraction with independent input,
  intermediate, and output providers hidden from plugins behind stable artifact
  references, handles, metadata, and verified staging.
- **Accepted** the coordinator as primarily a control plane rather than the
  mandatory bulk storage server or relay: it owns scheduling, job authority,
  leases, retries, policy, progress, and artifact metadata.
- Added **Directional** direct worker publication to requester-controlled storage.
  Atomic, content-verifiable, lease-fenced parallel uploads preserve centralized
  authority while providing BitTorrent-like aggregate bandwidth without adopting
  an anonymous peer-to-peer protocol.
- **Accepted** a location-independent assembly contract: plugins define how to
  assemble through artifact abstractions, while Mechana may eventually choose
  coordinator-side, client-side, or worker-side placement.
- Separated security layers: authenticated TLS is **Accepted** beyond explicit
  local development, provider encryption at rest is an optional provider
  capability, end-to-end artifact encryption with requester-controlled keys is
  **Proposed**, and concrete key management is **Deferred**.
- Reaffirmed **Directional** immutable artifact caching and locality-aware
  scheduling as optimizations that never determine correctness.
- **Accepted** vendor-neutral, repository-owned plugin definition/context
  artifacts usable by humans and any capable coding assistant.
- Added a **Directional** built-in Mechana assistant for plugin creation, testing,
  explanation, refinement, and reuse.
- Added **Directional** Git-backed AI knowledge: reviewed architecture context,
  examples, prompts, evaluation/certification cases, known failures, and reusable
  plugin patterns travel with every clone. Large base-model weights stay outside
  ordinary Git; optional small adapters remain **Proposed**.
- Reaffirmed simplicity and audience separation: operators, plugin authors,
  domain experts, and infrastructure contributors should need only the concepts
  relevant to their responsibilities.
- Added focused [storage](../brain/storage.md),
  [AI plugin authoring](../brain/ai-plugin-authoring.md), and
  [distributed-storage design](architecture/distributed-storage.md) documents and
  linked them from the README, repository guidance, decision index, roadmap, and
  relevant brain files. This documentation update makes no implementation claim.

## 2026-08-04 12:36:27 EDT — Standardize legal and brand notices

- Added the standard Apache License 2.0 copyright header to hand-authored Java
  and HTML source files, with Mark Vita as the 2026 copyright holder.
- Verified the full Apache License 2.0 text and added repository NOTICE and
  contributor guidance covering contribution licensing and retained copyright.
- Documented Mechana naming, logo, color, acceptable-use, and prohibited-use
  guidance, including the separation between Apache-licensed software and
  trademark rights.
- Updated prominent project, website, repository-guidance, development, and
  brand references to use Mechana™ once where appropriate without changing any
  runtime behavior or architectural contract.

## 2026-08-04 19:15:00 EDT — Begin macOS sandbox and plugin-host foundation

- Added common trust, policy, request, result, capability, workspace, launcher,
  sandbox, and runtime-manager contracts without plugin-specific dependencies.
- Added managed process execution with an isolated environment, attempt layout,
  log capture, timeout/cancellation, best-effort cleanup, and a one-request NDJSON
  host for existing `TaskPlugin` packages.
- Added an experimental macOS backend using Apple-deprecated `sandbox-exec`. It
  fails closed and reports filesystem/network enforcement only after a live probe.
  MBA Codex containment rejects nested profiles, so adversarial tests skip here.
- Split concrete plugin migration into sequential PR B, starting with sleep.

## 2026-08-05 12:00:00 EDT — Migrate concrete plugins to the macOS sandbox runtime

- Routed sleep, FFmpeg video, fractal rendering, Tesseract OCR, and Blender
  rendering through the separate plugin host when a worker is launched in
  explicit sandboxed mode; legacy mode remains available and is clearly labeled.
- Moved video chunks, OCR pages, and Blender scenes into worker-owned input
  staging before sandbox launch so plugin execution retains network denial.
- Required absolute operator-declared FFmpeg, FFprobe, Tesseract, and Blender
  executable paths. Sandboxed workers receive only the declared runtime grants;
  missing or non-executable paths fail closed before plugin launch.
- Preserved the existing task parameters and legacy download behavior for
  non-sandboxed workers while adding workspace-local input parameters for the
  sandbox host.
- Kept the macOS guarantee matrix honest: home reads, plugin network access, and
  writes outside work/output/logs are denied after a successful live probe;
  workspace-only system reads and hard CPU, memory, scratch, process-count, and
  descendant-tree guarantees remain unavailable.

## 2026-08-05 05:31:22 EDT — Verify remaining sandbox plugin adapters

- Added focused tests proving the FFmpeg, Tesseract, and Blender plugin adapters
  consume worker-staged local inputs without performing plugin-side network
  downloads. Sleep remains pure Java and requires no input adapter.
- Corrected architecture, plugin-model, and current-state text that still described
  the four remaining plugin migrations as pending after their implementation.
- Verified the full 18-module reactor with Java 25.0.4 and Maven 3.9.16 on macOS
  26.5.2 arm64. All four live macOS sandbox integration checks passed, along with
  unit tests, Spotless, and SpotBugs.

## 2026-08-05 05:45:00 EDT — Provision native runtimes for all plugins

- Extended SSH reinstall to discover FFmpeg, FFprobe, Tesseract, and Blender on
  each macOS or Linux target using the command path and standard installation
  locations.
- Deployment now fails early with the exact missing prerequisite when the selected
  sandbox capability set requires an unavailable tool.
- Generated launchd and systemd definitions persist verified absolute runtime
  paths as JVM properties, ensuring sandboxed workers inherit deliberate native
  runtime grants without depending on an interactive shell environment.

## 2026-08-05 06:05:00 EDT — Remove plugin JARs from server arguments

- Simplified server startup to `[port] [public-server-url] [data-directory]` and
  automatically registered all current plugin artifacts from standard build
  outputs.
- Simplified dashboard-driven server restart to preserve only those generic server
  settings; plugin-specific paths no longer leak into the process command line.
- Retained optional `mechana.plugin.<id>.jar` JVM properties for packaged layouts
  whose plugin artifacts do not use repository build-output locations.

## 2026-08-05 06:45:00 EDT — Bound native-plugin retries and disable sandboxed Blender

- Diagnosed a distributed Blender job whose Blender 4.5.3 processes repeatedly
  exited with status 139 under the experimental macOS `sandbox-exec` backend.
- Added a platform-owned three-attempt ceiling for worker failures and expired
  leases. Exhaustion now fails the job and fences all unfinished work units instead
  of indefinitely respawning a crashing native runtime.
- Removed `blender-render` from the macOS sandbox worker allowlist and added an
  explicit operator error directing Blender workloads to legacy workers. Blender
  still executes as a separate cancellable native child there, but no OS sandbox
  guarantee is claimed.
- Added coordinator and host-agent regression coverage for retry exhaustion and
  sandboxed-Blender rejection.

## 2026-08-05 13:20:00 EDT — Restore Blender in the verified MBA sandbox profile

- Traced Blender's sandbox-only status-139 crash to Metal device discovery in
  `MTLDevice`; Blender performs this startup path even for explicit CPU Cycles.
- Added the narrow sandbox `iokit-open` operation required for device enumeration
  and redirected Blender `TMPDIR`, `TMP`, and `TEMP` into attempt scratch.
- Proved actual sandbox execution with a one-frame CPU Cycles render at 160×90,
  then restored `blender-render` to the host-agent sandbox allowlist.
- Retained the three-attempt scheduler ceiling so a future native regression fails
  the job without creating an unbounded crash loop.

## 2026-08-06 02:15:00 EDT — Support sandboxed Blender on Intel macOS 12

- Verified the corrected sandbox profile across eight workers: twelve frame batches
  completed on the Apple-silicon MBA while Blender 4.5.12 exited with status 139 on
  the Intel Mac Mini running macOS 12.7.6.
- Reproduced the Mini failure with a standalone restricted `sandbox-exec` probe and
  proved that adding only local IPC access allows headless Blender startup.
- Added `(allow ipc*)` to the macOS profile. This permits local synchronization and
  shared-memory IPC but does not grant network or filesystem access.

## 2026-08-06 02:35:00 EDT — Harden remote plugin and staged-input downloads

- Reproduced an eight-page OCR smoke test where all MBA pages completed but all
  Rocinante attempts failed with `ConnectException` before Tesseract execution.
- Added four bounded download attempts with exponential backoff for plugin JARs and
  worker-staged native inputs. Retryable HTTP 408, 429, and 5xx responses use the same
  path; permanent 4xx responses still fail immediately.
- Download failures now identify whether plugin or sandbox-input transfer failed,
  include the attempt ceiling and coordinator authority, and retain the original
  exception as the cause without logging opaque input tokens.
- Added a worker regression test proving recovery after two transient server failures.
- Improved diagnostics then exposed the persistent cause: a coordinator restarted
  without its public-URL argument advertised plugin downloads at `localhost`, even
  though remote workers registered through the configured coordinator hostname.
- Workers now rebase only coordinator-issued loopback download URLs onto their
  configured coordinator origin. Non-loopback and external URLs remain unchanged;
  the behavior covers plugin JARs and staged OCR, video, and Blender inputs.

## 2026-08-06 07:12:24 EDT — Update the public homepage

- Reworked the GitHub Pages homepage around the current description of Mechana as
  a plugin-driven distributed computation platform rather than a Java-specific
  execution system.
- Added concise sections for the execution model, platform services, controlled
  worker environments, storage abstraction, planned AI-assisted plugin authoring,
  and representative workloads.
- Applied the canonical Mechana mark and wordmark from `assets/brand`, retained
  the dark brand treatment, and updated responsive navigation and project links.
- Kept planned capabilities explicitly labeled and avoided changing runtime code
  or architectural contracts.

## 2026-08-06 07:50:00 EDT — Add the portable Linux sandbox backend

- Extended the existing plugin runtime manager with a fail-closed Linux backend;
  all five current plugins continue to use the same separate plugin-host protocol.
- Added live Bubblewrap discovery and namespace probing plus honest worker
  advertisements for the active backend and each enforced control.
- Added Linux tests for workspace access, forbidden host writes, hidden home
  access, network-namespace policy, timeout/cancellation infrastructure, and
  capability reporting.
- Kept the implementation distro-neutral and service-manager-neutral. Bubblewrap
  is the runtime dependency; package installation remains an operator concern.
- Captured Ubuntu 24.04, kernel 6.8, x86-64, Java 25, cgroup v2, enabled kernel
  user namespaces, and Ubuntu AppArmor's additional unprivileged-user-namespace
  restriction on `srv959600`.
- Installed Bubblewrap 0.9.0 as the only new worker runtime dependency. Built all
  Java artifacts on the development machine and deployed them to the target; the
  Linux worker does not require Maven or another build tool.
- The prebuilt host probe passed workspace access, forbidden filesystem access,
  private temporary storage, child-process crash isolation, timeout, cancellation,
  cleanup, and post-failure recovery in the same root-owned service context as the
  existing agent. Two sandboxed Linux workers registered their actual guarantee
  set. Job `61c44154-443f-4009-9898-46b261cdcf56` completed four Linux sleep work
  units on attempt one; fractal job `ec7770e0-9743-4fdf-b8fa-8a4012694ae3`
  completed one first-attempt image on each Linux worker and assembled the full
  ten-image artifact collection.
- CPU, RAM, scratch-byte, process-count, cgroup, seccomp, dedicated-identity, and
  bounded-log enforcement remain explicitly unclaimed.
# 2026-08-06 — Implement and validate the Windows sandbox backend

- Added the Windows `PluginSandbox` backend behind the existing runtime manager.
  A self-contained native launcher creates an AppContainer process and assigns it
  to a Job Object; plugins remain outside the worker JVM.
- Enforced read-only `input`, writable `work`/`output`/`logs`, home denial,
  default network denial, CPU rate, process memory, active-process count,
  timeout/cancellation, and kill-on-close descendant cleanup. Scratch-byte and
  log-byte quotas remain planned.
- Extended Worker Control SSH deployment to Windows OpenSSH, per-host Windows
  launcher settings, runtime discovery, a per-user Scheduled Task, and a private
  Java runtime beneath the configured Mechana directory.
- Validated Hyperion (Windows ARM64 build 26200, Java 25.0.4): direct attempts to
  modify input, read the user home, and reach the network were denied; writable
  workspace access succeeded. Distributed job `2ebe40c5-ae0b-4efc-8b43-5dc83a5bb356`
  completed all 20 tasks, including Hyperion task 4 on its first attempt.

## 2026-08-06 — Keep long native renders leased under host saturation

- Moved worker-presence and active-task lease heartbeats from virtual threads to
  dedicated daemon platform threads with elevated scheduling priority. Long-running
  native tools can saturate a host without starving the control-plane heartbeat path.
- Changed Blender's distributed default from all available CPU threads per work unit
  to one thread per worker. Operators can still explicitly set a larger positive
  `threads` value, but the default no longer multiplies host-wide parallelism by the
  number of worker processes.
- Preserved compatibility with the server's legacy `threads=0` assignment by mapping
  that value to the new safe one-thread distributed default.
- Added focused tests for the dedicated heartbeat thread and Blender thread default.
- Blender now publishes 1% immediately after native-process launch, at least 5% when
  a frame begins, and sample-based progress when Cycles exposes sample counts. A
  single-frame distributed work unit therefore no longer appears frozen at 0% until
  artifact publication.

## 2026-08-06 — Harden Worker Control access and cross-platform reprovisioning

- Routed Worker Control status/start/stop traffic through an on-demand authenticated
  SSH tunnel to each host agent's loopback API. Remote agents no longer need a broadly
  reachable management port, and custom SSH ports and identities remain honored.
- Added bounded one-second readiness probes, tunnel lifecycle cleanup on host changes,
  and disabled host selection while an operation is active.
- Migrated saved `/opt/mechana/host-agent` and `/var/lib/mechana-sandbox` defaults to
  user-writable `~/.mechana` locations for non-root remote installs.
- Added bounded macOS `launchctl bootstrap` retries to tolerate teardown races and
  removed legacy Windows inbound host-agent firewall rules during reinstall.
- Added focused tests for SSH forward construction, settings migration, macOS retry
  generation, and Windows firewall-rule cleanup.
- Verified 16 sandboxed workers across the MBA, Rocinante, Hyperion, and the Linux host
  after correcting duplicate Linux host-agent services and routing Hyperion directly
  to the MBA over the VM network. Host-agent restart does not yet persist and restore
  the requested worker count; this is documented as follow-up rather than claimed.

## 2026-08-07 — Seed development-fleet Worker Control profiles

- Added migration-safe defaults for the MBA, Rocinante, Linux, and Hyperion test
  hosts, including each host's established SSH username and port and the shared
  `http://marks-macbook-air-m4:8787` coordinator URL.
- Defaulted new host profiles and the plugin field to the complete current set:
  sleep, FFmpeg video, fractal rendering, Tesseract OCR, and Blender rendering.
- Preserved existing per-host customizations and other legacy fields while migrating
  global settings into independent versioned profiles on the next save.
- Added focused settings migration, customization-preservation, and host-switching
  regression tests. No SSH password storage or password authentication was added.
- Made the bearer token optional for SSH-tunneled development management. Blank
  tokens now remain blank, and generated agents bind only to remote loopback;
  nonblank tokens continue to enable bearer authentication.
- Linux reinstall now explicitly restarts an already-running systemd user service
  so rewritten token and coordinator settings take effect immediately.
- Linux reinstall also removes a verified stale Mechana agent holding the selected
  port, including legacy `/opt` deployments, while refusing to kill an unrelated
  listener.
- Root-targeted Linux reinstall disables the specifically verified legacy
  `/etc/systemd/system/mechana-worker-host-agent.service` supervisor before port
  cleanup so the obsolete `/opt` agent cannot respawn.
- Moved the remote SSH action buttons to a dedicated row so Worker Control packs
  into a narrower window without shrinking its path fields.

## 2026-08-07 04:30:00 EDT

Added the generic Client Job Launcher foundation: schedulable-capability discovery,
descriptor-rendered forms for the five current plugins, generic submission,
live/completed job monitoring, abort, provider-aware server-local artifacts, and
owned-history purge. The new Swing module follows Worker Control conventions.
Plugin-manifest descriptors, uploads, authentication, client-local storage, and
cloud providers remain future direction; see `brain/current-state.md` and
`docs/client-job-launcher.md`.

## 2026-08-07 — Remove obsolete Windows launcher deployment requirement

- Updated Worker Control's Windows SSH provisioning to use the PowerShell sandbox
  launcher embedded in the worker JAR.
- Removed the external Windows sandbox EXE field from the visible controller UI and
  stopped uploading or forwarding the retired launcher property.
- Kept the old saved profile value readable for migration compatibility; it no
  longer affects deployment.
- Added a regression assertion that generated Windows agent scripts do not contain
  the obsolete launcher property.
- Migrated the generic saved Windows sandbox default from the SSH user's home to
  `C:\ProgramData\Mechana\sandbox` during reprovisioning, while preserving explicit
  absolute operator paths.
- Replaced the raw missing-task failure from Windows agent restart with an
  actionable instruction to reinstall when the managed scheduled task is absent.
- Labeled the verified `windows-appcontainer-job` worker backend as `Windows
  sandbox` in the server dashboard, consistently with the existing macOS and Linux
  labels.
- Added a confirmed `Purge all` action to the server dashboard and Client Job
  Launcher. The loopback-only bulk endpoint removes every durable completed-job
  record and its server-local artifacts while leaving active jobs untouched.
- Added version-controlled macOS `.command` launchers for the server, Worker
  Control, and Client Job Launcher plus an installer under `scripts/macos`.
- Added a packed two-second Blender geometry/orbit sample and made it the Client
  Job Launcher's Blender default: frames 1–48 at 24 fps, with visible camera
  movement throughout and development-sized 640×360/32-sample rendering.
- Corrected Windows native-runtime discovery to select only executables staged
  beneath `C:\ProgramData\Mechana\runtime`. Worker Control now fails
  reprovisioning when a requested native sandbox runtime is absent instead of
  configuring an installed path that the AppContainer must reject.
- Made abandoned sandbox workspace deletion propagate filesystem traversal
  failures as checked cleanup errors, preventing inaccessible stale Windows
  AppContainer scratch from crashing replacement workers during startup.
- Standardized Client Job Launcher capability presentation and task selection:
  dropdown entries no longer show worker counts, every plugin uses
  `Tasks (0 = fleet)`, and zero now means one task per compatible connected
  worker rather than a plugin-specific multiplier or rejection.
- Made descriptor forms vertically scrollable so longer plugin forms cannot be
  clipped by the jobs-history divider, and moved Blender's Tasks control directly
  below its source picker.

## 2026-08-07 12:25:15 EDT — Consolidate launcher and Windows recovery work for review

- Audited all six branch commits against `origin/main` before publication and
  confirmed that the PR contains Windows reprovisioning recovery, removal of the
  obsolete external sandbox launcher dependency, the animated Blender sample,
  all three macOS shortcuts, fleet-consistent task selection, and scrollable
  descriptor forms.
- Updated `brain/current-state.md` with the checked-in Mac launcher suite, the
  Blender orbit sample defaults, and the server dashboard's verified Windows
  sandbox label; implementation limits remain separated from future direction.

## 2026-08-07 12:42:36 EDT — Fix concurrent Windows sandbox runtime access

- Diagnosed Blender job `9c8a97ae-aaee-423d-b69e-14cdc5950c12`: both Hyperion
  workers intermittently lost access to the shared private Java and Blender
  runtimes while concurrent AppContainer attempts edited their Package SID ACLs.
- Serialized filesystem ACL grant/removal operations with a named cross-process
  mutex. The lock covers only each short ACL mutation, so sandboxed render
  processes still execute concurrently.
- Reset each private attempt workspace ACL after the AppContainer exits and before
  worker-side deletion. This handles Blender-created protected temporary
  directories without broadening runtime or host filesystem access.
- Added focused launcher-resource regression tests. A live eight-frame/eight-task
  Blender job launched both fixed Hyperion workers concurrently without the prior
  Java-security or Blender `Access is denied` startup failures and assembled its
  final movie successfully.

## 2026-08-07 16:59:45 EDT — Validate descriptor-driven file types

- Diagnosed an OCR submission of a `.docx` file that reached PDFBox and surfaced
  as an opaque HTTP 500 parser error even though the plugin accepts PDF input only.
- Extended generic submission fields with accepted-extension metadata. The Client
  Job Launcher now filters file selection and rejects mismatched extensions before
  submission without embedding OCR or Blender semantics in the client.
- Declared `.pdf` for OCR and `.blend` for Blender. Server-side OCR submission also
  verifies the PDF extension and `%PDF-` signature so alternate clients receive a
  clear client-input error instead of an internal parser failure.
- Added protocol-compatible constructor behavior plus launcher and server catalog
  regression coverage.

## 2026-08-08 04:23:15 EDT — Audit the post-sandbox checkpoint

- Audited `main`, the active worktrees, all remote development branches, and all
  pull requests through PR #37. Confirmed that the completed sandbox/runtime,
  Worker Control host-profile defaults, Client Job Launcher, homepage, legal and
  branding, and subsequent Windows/launcher hardening work are present on `main`.
- Confirmed there are no open pull requests. Historical and stacked development
  branches are merged or superseded by their integration PRs; they contain no
  additional completed implementation that should be applied to `main`.
- Kept `agent/storage-abstraction-foundation` intentionally outstanding. Its seven
  storage-foundation commits are the only unique unmerged implementation work and
  are not part of this checkpoint.
- Corrected stale roadmap, current-state, and worker-management wording so the
  durable brain reflects the completed sandbox phase and implemented host controls.
- Established annotated tag `architecture-baseline-1.2` as the post-sandbox,
  pre-storage-abstraction checkpoint.

## 2026-08-08 04:55:00 EDT — Add Dock-ready macOS application bundles

- Added Java 25 `jpackage` app-image builds for Mechana Server, Worker Control,
  and Job Launcher with stable bundle identifiers, the canonical Mechana app
  icon, bundled runtimes, Finder-safe paths, and no Terminal windows.
- Made Mechana Server.app an idempotent status launcher backed by the loopback
  dashboard API and a per-user `launchd` agent. The background server survives
  launcher and browser exit, uses the packaged runtime and plugin artifacts,
  retains existing desktop-launcher data when present, and writes user-local logs.
- Made dashboard restart supervisor-aware and added explicit no-sudo server
  status/start/stop/restart commands. Worker Control and Job Launcher retain their
  existing settings and normal quit-on-window-close behavior.
- Documented build, install, lifecycle, verification, and the unsigned/unnotarized
  limitation in [macOS apps](macos-apps.md); updated `brain/current-state.md` from
  repository evidence.

## 2026-08-08 05:48:00 EDT — Distinguish Dock apps and dedicate the server window

- Added subtle dark, blue, and rose variants of the canonical Mechana app icon so
  Server, Worker Control, and Job Launcher remain visibly related but are easy to
  distinguish in the Dock.
- Replaced Server's ordinary-browser handoff with a native macOS WebKit window.
  Dock activation now reveals the one existing dashboard window instead of
  creating duplicate browser windows; closing that UI continues to leave the
  LaunchAgent-owned server running.

## 2026-08-08 06:04:00 EDT — Restore packaged dashboard controls and add server stop

- Added native WebKit confirmation-dialog handling so the dashboard's restart,
  individual purge, and bulk purge controls send their requests from the packaged
  Server app.
- Added a confirmed, loopback-only **Stop server** action. Packaged shutdown
  unloads the per-user LaunchAgent and closes the dashboard frontend; the next
  Server app launch bootstraps the service again.
- Added server endpoint coverage and documented the packaged lifecycle in
  [macOS apps](macos-apps.md) and `brain/current-state.md`.

## 2026-08-08 06:12:00 EDT — Differentiate Worker Control and Job Launcher icons

- Replaced the similar color-only app variants with distinct functional symbols:
  Worker Control now uses a cool-blue connected-node mark, while Job Launcher uses
  a warm amber paper-plane mark.
- Retained the shared Mechana hexagonal visual language while making the two apps
  recognizable by silhouette as well as color at Dock sizes.

## 2026-08-08 06:23:00 EDT — Make packaged Worker Control self-contained for deployment

- Diagnosed Finder-launched **Reinstall + start via SSH** failures caused by saved
  repository-relative host-agent and worker JAR paths being resolved outside the
  Git worktree.
- Added the current deployable host-agent and worker JARs to the Worker Control app
  bundle and resolved packaged defaults relative to the bundle's own executable.
- Migrated only the known repository-default paths to bundled artifacts, retained
  explicit custom paths, and added settings migration coverage.

## 2026-08-08 06:40:00 EDT — Tolerate macOS agent-unload listener races

- Diagnosed a local MacBook Air reinstall that unloaded the existing LaunchAgent
  but then classified its short-lived, commandless port listener as a non-Mechana
  process.
- Changed macOS and Linux stale-listener cleanup to wait boundedly when a listener
  PID temporarily has no readable command. The safety boundary remains strict:
  only a visible command containing `mechana-worker-host-agent.jar` may be killed,
  and unrelated listeners still abort deployment.
- Rebuilt the apps and verified the installed Worker's **Reinstall + start via
  SSH** action end to end on `marks-macbook-air-m4`, restoring two sandboxed
  workers.

## 2026-08-08 06:49:00 EDT — Restore native tools to the packaged server environment

- Diagnosed Blender job `79604b28-612d-4bfa-85b4-0bde2aacaf66`: all eight frame
  batches succeeded, but coordinator-side movie assembly failed because the
  Finder-launched server's LaunchAgent could not resolve Homebrew `ffmpeg`.
- Added Apple Silicon Homebrew, Intel Homebrew, and system binary directories to
  the packaged server LaunchAgent's deterministic `PATH`, covering Blender and
  video coordinator-side FFmpeg/FFprobe work without depending on shell startup
  files.
- Added generated-plist regression coverage and documented the packaged native-tool
  environment.

## 2026-08-08 07:25:00 EDT — Finalize the macOS application packaging change set

- Consolidated the Dock-ready Server, Worker Control, and Job Launcher bundles,
  their distinct icons, the dedicated single-window server dashboard, standard
  `/Applications` installation, LaunchAgent lifecycle, server controls, and
  self-contained Worker Control deployment artifacts into one reviewable change set.
- Included the follow-up reliability fixes found during installed-app testing:
  bounded host-agent listener teardown handling and a deterministic native-tool
  path for coordinator-side FFmpeg/FFprobe operations.
- Rebuilt and reinstalled all three application bundles, verified the live server
  LaunchAgent environment, and completed the full 20-module Maven verification
  reactor successfully before submitting PR #39 for final review.
- Local app images remain unsigned development builds. Distribution outside the
  owner's Macs will require Apple Developer signing and notarization.

## 2026-08-08 20:45:00 EDT — Migrate server-local video to artifact references

- Replayed the seven storage-foundation commits cleanly onto current `main`,
  preserving server-local defaults for input, intermediate, and output roles.
- Migrated scheduler-managed FFmpeg video source ingest, worker input staging,
  lease-fenced segment publication, verified assembly staging, final publication,
  and completed-job metadata to `ArtifactReference`/`ArtifactStore` boundaries.
- Preserved the existing HTTP transfer path and FFmpeg local-path interface while
  adding provider/key/size/SHA-256 ownership and integrity semantics.
- This is intentionally limited to server-local FFmpeg video. Client-local,
  Google Drive, S3, direct worker-to-requester publication, client-side assembly,
  and migration of the other workloads remain future work.

## 2026-08-08 21:25:00 EDT — Add client-local FFmpeg assembly

- Added a Client Job Launcher storage choice for FFmpeg jobs. `server-local`
  remains the zero-configuration default; `client-local` adds input upload plus
  client scratch and output directory selectors.
- Client-local jobs retain the existing worker protocol: the server temporarily
  stages the input, workers publish lease-fenced segments, and the launcher
  downloads each segment by artifact reference with size/SHA-256 verification.
- The launcher assembles locally with FFmpeg, writes the final Matroska artifact
  into the selected output directory, and reports client-local provider/key/size/
  SHA-256 metadata. Completed history persists that metadata without copying the
  client-owned bytes into server storage.
- This option is FFmpeg-only. The initial topology still relays artifact bytes
  through temporary server-local staging; restart-resumable launcher assembly,
  Google Drive, S3, direct worker-to-requester publication, and client-local
  support for other workloads remain future work.

## 2026-08-08 22:45:37 EDT — Make client-local FFmpeg a direct data plane

- Changed client-local FFmpeg submission so the launcher probes and splits the
  source into keyframe-aligned chunks in its selected scratch directory rather
  than uploading the source to temporary server storage.
- Added a tokenized launcher data endpoint: compatible workers fetch their chunks
  directly into worker scratch and publish lease-identified output attempts directly
  into client scratch. The server coordinates URLs, integrity metadata, scheduling,
  and accepted lease identities without relaying the large artifact bytes.
- Added `storage.client-direct-video.v1` capability matching so only updated workers
  can lease direct client-local video tasks. Worker publication is restricted to the
  same tokenized client-video origin and output namespace as the corresponding input.
- The launcher verifies the server-selected attempt outputs by size/SHA-256, assembles
  the final video in the selected client output directory, and reports client-local
  completed-artifact metadata. Server-local video behavior remains unchanged.
- This direct topology remains FFmpeg-only. Restart-resumable client assembly,
  client-local support for other workloads, generalized external providers, Google
  Drive, and S3 remain future work.

## 2026-08-08 23:26:08 EDT — Clarify and clean client-local scratch

- Made the Client Job Launcher output summary follow the selected FFmpeg placement,
  showing the client-selected output directory for `client-local` instead of the
  static server-artifact description.
- Made client scratch optional. A blank value creates a temporary launcher-side
  staging directory; an explicit value uses an owned per-transfer subdirectory.
  Neither choice changes worker placement: every worker still downloads, processes,
  and cleans its attempt in scratch local to that worker machine.
- Added best-effort cleanup of launcher transfer files after success, failure, or
  cancellation and deterministic cleanup of assembly intermediates. The selected
  final output and completed-artifact metadata are retained.

## 2026-08-08 23:34:00 EDT — Isolate client-local final outputs by job

- Changed client-local FFmpeg assembly to create a job-ID subdirectory beneath
  the selected output directory and publish the final video there. Final paths
  now have the form `<selected-output>/<job-id>/compressed-<job-id>.mkv`.
- Applied the same layout to both direct and legacy client-local assembly paths,
  and added regression coverage for the layout and traversal rejection.

## 2026-08-09 03:35:00 EDT — Migrate remaining plugins to artifact storage

- Extracted the requester-hosted direct transfer service from the FFmpeg-specific
  class into a generic client artifact data plane. Updated workers advertise
  `storage.client-direct-artifacts.v1`, restrict destinations to the authorized
  origin and safe artifact namespace, and retain the legacy video capability.
- Migrated Fractal batches and result trees, OCR page inputs/batches/results, and
  Blender scene inputs/frame batches/results through `ArtifactStore` and
  `ArtifactReference` boundaries. Assembly stages verified size/SHA-256 bytes into
  private scratch before plugin or native-tool use.
- Published Sleep's completed `job-summary.json` through the same artifact store
  so completed history consistently records provider/key/size/SHA-256 metadata.
- Preserved `server-local` as the default and existing-worker compatibility for
  those jobs. Direct client-local assembly remains implemented only for FFmpeg;
  Fractal, OCR, and Blender launcher assembly adapters are explicitly deferred.

## 2026-08-09 04:20 EDT — Add universal client-local plugin assembly

- Extended the generic requester-hosted artifact data plane to Fractal, OCR, and
  Blender without changing the default server-local workflows.
- Reused plugin-owned planning and assembly on both placement paths. OCR PDF
  rasterization now lives in the OCR plugin and runs at the selected assembly
  host; Fractal needs no input; Blender serves its packed scene directly.
- Capability-gated workers publish lease-fenced ZIP batches directly to client
  scratch. The coordinator records accepted attempt identities and final
  provider/key/size/SHA-256 metadata without relaying intermediate bytes.
- Common launcher controls expose placement, scratch, output, and transfer host;
  the launcher displays SPLITTING during local preparation and ASSEMBLING while
  producing the final artifact. Sleep keeps irrelevant controls disabled.

## 2026-08-09 05:45 EDT — Add per-worker transfer accounting

- Added backward-compatible completion counters for bytes staged into worker
  scratch, bytes successfully published by workers, and plugin-package downloads.
- Aggregate only accepted lease completions, preventing stale attempts from
  becoming part of authoritative job telemetry.
- Log one terminal transfer total and publish a provider-backed
  `transfer-summary.json` completed artifact with topology, directional routes,
  totals, and per-worker breakdowns. Existing workers continue to run but report
  zero counters until reinstalled.

## 2026-08-09 05:52 EDT — Verify transfer accounting on an eight-worker video job

- Job `462fa0ee-b8f3-417b-a368-228ed24fd77e` completed all eight FFmpeg work
  units through the `client-worker-direct` topology and retained an exact
  per-worker `transfer-summary.json`.
- Measured 29,633,829 input bytes from client to workers, 16,717,070 output bytes
  from workers to client, and 438,616 plugin-package bytes from server to workers:
  46,789,515 application-payload bytes in total.
- The 119,175,998-byte source was prepared into worker chunks totaling 24.9% of
  the original source size. Client-local assembly produced a 19,118,980-byte MKV;
  the coordinator relayed none of the video input or compressed-segment bytes.
- Counters intentionally exclude HTTP/TLS framing, small control messages, and
  local preparation/assembly disk I/O. They include only accepted task attempts,
  so stale completion reports cannot affect the durable totals.

## 2026-08-09 06:25 EDT — Prevent distributed HEVC assembly corruption

- Diagnosed three nominally successful client-local FFmpeg outputs containing
  1,201 video packets but only 217, 892, and 788 decodable frames. FFmpeg reported
  invalid HEVC alignment bits and missing reference-picture state.
- Replaced stream-copy concatenation of independently encoded worker HEVC streams
  with assembly that decodes each partition through its own input context and
  emits one coherent, size-constrained HEVC stream. The same safe assembly command
  is used by coordinator and client placement.
- Added full-frame final decode validation. Decoder diagnostics now fail assembly
  even when FFmpeg returns a successful process exit code, closing the validation
  gap that allowed corrupt outputs into completed history.
- Added a nonnegative `Start offset in seconds` video option, default `0`. The
  plugin-owned preparation command applies the range consistently for server-local
  and client-local splitting, while client-local final audio selection uses the
  same offset.

## 2026-08-09 07:00 EDT — Measure client-direct bandwidth and storage efficiency

- Analyzed successful client-direct FFmpeg job
  `6bc759c1-0a24-45dd-a4a3-944a995e423c`: eight workers processed 52.142 seconds
  beginning at a 50-second offset from a 119,175,998-byte source.
- Client-side preparation transferred 26,019,987 bytes of assigned input chunks
  instead of the whole source. Workers returned 16,944,666 bytes of compressed
  video partitions, and the server supplied 459,192 bytes of plugin packages, for
  43,423,845 bytes of measured accepted-attempt payload.
- Two launcher-local workers accounted for 4,531,442 bytes over the local direct
  endpoint. Approximately 38.9 MB crossed machines. The server relayed no media
  and retained only metadata; client assembly produced an 18,883,570-byte MKV.
- Compared with the original uncached server-centered flow—whole-source upload,
  chunk distribution, worker-result collection, and final download—the observed
  direct path reduced estimated cross-machine application payload from about
  181 MB to 38.9 MB, roughly 78%. This estimate excludes protocol framing,
  control messages, and local scratch I/O, matching `transfer-summary.json`.
- Confirmed why client-local assembly copies audio once from the original source:
  workers encode video-only partitions, avoiding independently cut audio gaps,
  overlaps, duplicate transfer, and re-encoding. The final file exceeds returned
  partition bytes because local assembly adds that source audio and container data.
- Documented that simultaneous worker publication can aggregate independent
  worker upload bandwidth, but remains bounded by the requester's connection,
  disk, HTTP handling, and any shared worker uplinks. Server-local can remain more
  efficient for already-resident/cached inputs, repeated reuse, weak client links,
  or jobs that must survive launcher disconnection.

## 2026-08-12 11:35 EDT — Add pure-Java convolution reverb proof of concept

- Added `audio-convolution-reverb` with dependency-free radix-2 FFT, uniform
  partitioned overlap-add convolution, streaming source blocks, precomputed IR
  spectra, full-tail output, wet/dry and pre-delay controls, optional IR
  normalization, and deterministic peak protection with configurable headroom.
- Added 16/24-bit PCM and 32-bit IEEE-float WAV input, mono/stereo routing,
  24-bit PCM output, and explicit matching-sample-rate validation. No native
  program or third-party DSP dependency is used.
- Integrated staged dry/IR artifacts, lease-fenced worker publication,
  server-local final artifact metadata, the sandbox allowlist, macOS packaging,
  worker controls, and the generic Client Job Launcher descriptor.
- Verified a live MacBook Air job through one sandboxed worker. A two-second mono
  synthetic source and two-second stereo decaying IR produced a 4.024979-second,
  stereo 48 kHz/24-bit WAV at the configured -1 dBFS headroom.
- Documented future frequency-domain contribution/overlap-add decomposition.
  Hardware sweep deconvolution remains separate future/helper tooling.

## 2026-08-13 05:35 EDT — Improve launcher job selection and artifact access

- Preserved the selected job by stable job ID while the launcher's periodic job
  refresh rebuilds its table, fixing the selection disappearing after each poll.
- Added an **Open artifacts folder** action for the selected completed job. The
  server reveals the correct Finder folder through its loopback-only endpoint.
- Added an optional Reverb **Shared artifacts folder** descriptor field. Successful
  jobs retain their normal durable server artifacts and are additionally mirrored
  into `<selected root>/<job ID>/`; failed or missing mirrors fall back to the
  normal completed-job artifact directory.
- Added launcher selection and descriptor coverage and verified the affected
  server, protocol, plugin, and launcher modules.

## 2026-08-13 05:45 EDT — Suggest descriptive Reverb output filenames

- Made the Reverb output artifact name follow the selected dry WAV stem plus wet
  level, dry level, pre-delay, and IR-normalization controls. Decimal points are
  encoded as `p` so the result remains valid under the existing artifact-name
  contract; peak protection and safe headroom are intentionally omitted.
- Kept the field editable: the live suggestion stops changing once the user types
  an explicit output name. Remembered generated names remain recognizable as
  suggestions when the launcher is reopened.
- Added coverage for initial generation, parameter-driven updates, normalization,
  filename sanitization, and manual override preservation.

## 2026-08-13 06:00 EDT — Include the IR name in Reverb output filenames

- Extended the live Reverb output suggestion with the selected impulse-response
  WAV stem: `<dry>-reverb-ir-<ir>-wet<wet>-dry<dry>-pre<ms>ms-norm-<on|off>.wav`.
- Both input names are extension-free and filename-safe. Changing either input
  updates the suggestion unless the user has supplied an explicit override.

## 2026-08-13 06:10 EDT — Add a human-readable Reverb job report

- Added `reverb-job-report.txt` to every terminal Reverb job's durable artifacts.
  The report captures job identity/status, submission and completion timestamps,
  processing and wall-clock duration, worker assignments, input names/paths/sizes,
  every Reverb control, storage selection, and output artifact metadata including
  sizes, providers, and SHA-256 values.
- Successful jobs configured with a shared artifact root mirror the report beside
  the output WAV and existing JSON summaries. Failed Reverb jobs retain the report
  in normal server-owned completed history for diagnosis.
- Kept the established machine-readable `job-summary.json` and
  `transfer-summary.json`; this plain-text report is the first plugin-specific
  provenance slice and a model for a later generalized reporting contract.

## 2026-08-13 06:25 EDT — Record actual Reverb peak-protection gain

- Propagated the convolution processor's measured `appliedGain` from the sandboxed
  worker through a small result-metadata artifact into completed Reverb state.
- Added the linear multiplier, dB equivalent, and explicit peak-protection-engaged
  result to `reverb-job-report.txt`. Failed jobs that never reach output gain
  calculation report the value as unavailable rather than inferring it.
- Added numerical coverage showing a 1.6 peak is reduced exactly to the configured
  -1 dBFS target and report coverage for the corresponding 0.5 / -6.021 dB result.

## 2026-08-13 07:05 EDT — Add standalone macOS Reverb application

- Added a server-free Swing application that invokes the production
  `AudioConvolutionReverbPlugin` class locally, one job at a time. It retains the
  same controls and descriptive output naming without exposing server, worker,
  task-count, or network settings.
- Added local cancellation, progress, reloadable job history, Finder artifact
  reveal, per-job JSON state, result metadata, and a human-readable report with
  plugin version, parameters, input provenance, output size, and SHA-256.
- Extended macOS packaging with **Mechana Reverb.app**, its bundled Java runtime,
  Applications-folder installation, and a transfer-safe Apple Silicon ZIP. The
  development bundle remains unsigned and unnotarized.
- Bundled the five existing synthetic room/plate IR profiles with an in-app
  profile chooser and instructions for selecting arbitrary deconvolved hardware
  IR WAVs. Raw hardware sweep recordings remain inputs to future deconvolution
  tooling rather than valid direct plugin inputs.
- Added a focused architecture-aware Reverb packaging entry point. It can use an
  Intel Java 25 JDK under Rosetta to produce a separately named `x86_64` app ZIP
  for Intel Macs while retaining the Apple Silicon package.

## 2026-08-13 07:19 EDT — Make Reverb IR normalization safe for measured responses

- Changed IR normalization to attenuation-only behavior: peaks above -1 dBFS are
  reduced to -1 dBFS, while quieter captured hardware responses keep their
  measured gain instead of being amplified to full scale.
- Added numerical coverage for both quiet-response preservation and full-scale
  attenuation. This allows the default Normalize IR control to remain enabled
  without causing extreme wet gain and whole-mix peak-protection reduction.

## 2026-08-13 07:28 EDT — Generate hardware IRs in the standalone Reverb app

- Added a reusable pure-Java sweep deconvolver using the plugin's internal radix-2
  FFT. It performs regularized frequency-domain division, preserves captured
  response gain, aligns the recovered impulse, estimates and trims the decay, and
  applies a short tail fade.
- Added a **Create IR from Sweep** tab to the standalone app with source-sweep,
  recorded-return, and output-IR selectors. A successful conversion selects the
  new IR automatically for an immediate listening test.
- Bundled the exact standardized 48 kHz/24-bit stereo Mechana capture sweep and
  concise capture instructions in the macOS package.
- Added synthetic numerical recovery coverage and bundled-sweep validation.

## 2026-08-13 08:10 EDT — Unify sweep-to-IR generation across distributed and standalone apps

- Added the `audio-ir-deconvolution` worker capability to the existing pure-Java
  audio plugin module. It stages an original sweep and recorded wet return and
  invokes the identical `SweepDeconvolver` class used by Mechana Reverb.app.
- Added a schema-driven Client Job Launcher form with an optional shared IR
  library folder and overridable output profile name. Completed jobs include the
  generated 24-bit WAV, machine metadata, and human-readable provenance.
- Added worker sandbox approval, host-agent validation, Worker Control defaults
  and migration so existing standard capability sets gain the new feature after
  worker reinstall/restart.
- The standalone generator now writes a matching human-readable sidecar beside
  each generated IR. No third-party FFT or native audio dependency was added.

## 2026-08-13 08:25 EDT — Make blank Worker Control plugin selection mean all

- Worker Control now labels the field **Plugins (blank = all)**. Blank startup
  requests expand on the host agent to every plugin supported and allowed by that
  installed worker build; an explicit comma-separated list still restricts it.
- New profiles default to blank, and profiles holding the former complete default
  list migrate to blank. Worker status shows the concrete expanded capability set.

## 2026-08-13 11:05 EDT — Bundle Scott's first measured hardware IR

- Added Scott's 48 kHz, stereo, 24-bit, 1.13-second RVB first-pass IR to the
  standalone profile library as `scott-rvb-first-pass-ir.wav`.
- Updated bundled capture guidance to point directly to the app's Create IR from
  Sweep workflow and extended package validation to require all six profiles.

## 2026-08-13 11:15 EDT — Add completed-output actions to standalone Reverb

- Added bottom-line **Play Output** and **Show in Finder** actions. They enable
  after a successful job, target the newest generated WAV, use the configured
  default WAV player, and reveal the exact file selected in Finder.

## 2026-08-13 11:25 EDT — Refresh standalone descriptive names after input changes

- Fixed the standalone app retaining its prior generated output name after the
  dry WAV or impulse-response WAV changed. Either input change now regenerates
  the descriptive output name; a manual override remains intact while adjusting
  mix parameters, but resets when selecting a different source or IR.

## Future — Package standalone Reverb for Windows 11

- The pure-Java convolution and sweep-deconvolution code is portable to Windows,
  but no Windows bundle is implemented in this change.
- Build a self-contained Windows 11 x64 app on Windows using a Java 25 JDK and
  `jpackage`; include the Java runtime, standardized capture sweep, and bundled IR
  library just as the macOS application does.
- Adapt **Show in Finder** to reveal the exact output with Windows File Explorer,
  retain default-player WAV launch behavior, add a Windows icon, and validate
  paths containing spaces plus both Reverb and Create IR workflows.
- Prefer a portable ZIP for early testing. A later installer and public release
  should add Windows code signing to reduce Microsoft Defender SmartScreen
  warnings. A Windows GitHub Actions runner can automate the native package.

## 2026-08-13 15:20 EDT — Accept flexible dry audio for Reverb

- Reverb now converts dry WAV files to the selected IR's sample rate before
  staging, so 44.1 kHz voice recordings can be used directly with 48 kHz IRs.
- The server and standalone app also accept M4A/AAC and AIFF dry sources. An
  Apache-2.0 pure-Java AAC decoder and 32-tap windowed-sinc resampler produce the
  worker-ready 24-bit WAV; no external converter is required. IR inputs and
  outputs remain WAV, and worker-side convolution remains pure Java.
- Output naming and job provenance retain the original dry source filename.

## 2026-08-13 16:13 EDT — Decode ALAC M4A and fragmented audio MP4

- Diagnosed `musta.m4a` as 16-bit stereo Apple Lossless rather than AAC and
  corrected decoded-stream length handling so its full 346.4 seconds import.
- Added BSD-3-Clause pure-Java ALAC decoding and `.mp4` audio-track selection.
- Added permissively licensed pure-Java MP4 parsing for fragmented AAC files such
  as `jack1.mp4`, whose samples reside in `moof`/`mdat` fragments rather than the
  conventional sample table. IR selection remains WAV-only.

## 2026-08-13 16:28 EDT — Smooth the dry-to-reverb-tail boundary

- Diagnosed a small abrupt level change in a 720 ms source that ended while its
  last 20–50 ms still contained audible signal. The full convolution tail was
  already present and decayed cleanly to zero.
- Added a 10 ms fade to only the direct dry component at the source boundary.
  Wet convolution input, IR response, pre-delay, normalization, and the complete
  output-tail length remain unchanged.
- Added numerical coverage for the dry boundary envelope and for an unmodified,
  full-length wet tail.

## 2026-08-13 17:08 EDT — Add streaming reverb preview

- Added Play Preview, Pause/Resume, and Stop Preview controls to the standalone
  Reverb app. Preview streams the chosen recording through the existing
  partitioned convolution primitives to the default system output and creates no
  job or output artifact.
- Preview uses the existing dry-audio decoder and sample-rate converter, preserves
  mono/stereo IR routing, pre-delay, wet/dry mix, direct-signal end smoothing, and
  the complete reverb tail.
- Streaming peak protection uses an instantaneous ceiling at the configured
  headroom. Offline jobs retain their deterministic two-pass global gain.

## 2026-08-13 17:20 EDT — Make preview controls live

- Wet level, dry level, pre-delay, IR normalization, peak protection, and safe
  headroom now update an active standalone-app preview without restarting it.
- Added 20 ms parameter smoothing and an interpolated variable pre-delay line to
  prevent control changes from producing clicks. Increasing pre-delay also
  extends preview playback so the delayed tail remains complete.
- Live normalization applies the exact attenuation-only IR normalization factor
  to the ongoing wet result, which is mathematically equivalent to rebuilding
  the convolver with the scaled IR but avoids interrupting playback.
- Added synchronized sliders plus numeric override fields for wet level, dry
  level, and pre-delay so preview parameters can be explored continuously or
  entered precisely.

## 2026-08-13 17:46 EDT — Switch reverb profiles during preview

- Limited the pre-delay slider to the practical 0–200 ms range while preserving
  the numeric field for larger explicit values.
- Changing the IR path or choosing a bundled profile during playback now prepares
  the replacement partitioned convolver away from the playback thread and
  crossfades to it over 50 ms without restarting the recording.
- Preview uses a stable stereo output so mono and stereo IRs can be interchanged.
  A replacement IR must match the active preview sample rate; restarting preview
  prepares the dry recording for an IR with a different rate.
- Added numerical coverage proving that the old response is heard before the
  switch and the new response after the crossfade.

## 2026-08-14 15:15 EDT — Match preview IRs to native dry sample rates

- Diagnosed long startup for a 44.1 kHz/16-bit WAV against the bundled 48 kHz IRs:
  preview was resampling the entire four-minute dry recording before playback,
  while a larger 48 kHz WAV bypassed conversion.
- Preview now preserves the dry input's native sample rate and resamples the much
  shorter IR instead. Decodable compressed dry inputs are converted to PCM at
  their native rate without an additional sample-rate conversion.
- Added a persistent content-addressed IR cache under the user's macOS cache
  directory. Entries include the source IR digest, target rate, and resampler
  version, so repeated previews reuse the conversion and changed IR content
  invalidates it automatically.
- Switching back and forth among previously prepared IRs at the same playback
  rate selects their existing cached variants; only each profile's first use at
  that rate incurs resampling.
- Kept IR generation and profile selection centered on one user-visible master
  file. Every rate variant is created lazily, and the status bar displays
  **Regenerating IR to match sample rate…** only on the first use of an IR/rate
  pair; later selections silently reuse the cached variant.
- Live IR changes can now crossfade profiles of different source sample rates;
  the replacement is matched to the already-running preview rate in the
  background.
- Added tests for 44.1 kHz dry/48 kHz IR playback, cache reuse, source-content
  invalidation, and cached WAV sample-rate correctness.

## 2026-08-14 15:29 EDT — Reset preview IR cache after app updates

- Added a packaged-build fingerprint marker to the standalone Reverb app's owned
  IR cache directory. The first launch after installing a changed app bundle
  deletes regular cache entries and records the new fingerprint; ordinary launches
  of the same build keep cached rate variants.
- Cleanup remains tightly scoped to regular files immediately inside
  `~/Library/Caches/Mechana Reverb/ir`; it does not recursively remove directories
  or touch user-visible master IR profiles.

## 2026-08-14 16:55 EDT — Add wet-path EQ controls to convolution reverb

- Confirmed that pre-delay was already supported by distributed jobs, offline
  standalone rendering, and live preview; retained its current behavior.
- Added optional pure-Java second-order Butterworth low-cut and high-cut filters
  to the wet signal after convolution and pre-delay. Zero disables each filter,
  and both default to zero for backward-compatible sound.
- Exposed both controls through the generic server descriptor and standalone app,
  included them in job JSON and human-readable reports, and allowed changes to
  take effect during live preview without restarting playback.
- Kept decay, room size, diffusion, and modulation under control of the captured
  IR rather than adding algorithmic-reverb approximations to the convolution POC.
- Added numerical frequency-response, descriptor, validation, and reporting
  coverage for the new controls.

## 2026-08-15 — Add captured-response shaping and a canonical IR library

- Added neutral-by-default early-reflection level, late-tail level, attack, and
  decay-length controls to the production plugin, server descriptor, reports,
  standalone renderer, and live preview.
- Implemented shaping as IR preparation before FFT partitioning. Neutral values
  bypass sample transformation exactly; decay shortening fades and truncates the
  captured tail rather than inventing new response material.
- Reorganized the standalone Apply Reverb UI into scrolling Mix and timing,
  Captured-response shaping, Wet EQ, and Output sections with sliders and numeric
  overrides. Added Reset to Captured Response while preserving wet/dry choices.
- Replaced direct bundled-file selection with a unified IR selector backed by
  `~/Library/Application Support/Mechana Reverb/IR Profiles`, including factory
  installation, validated Add/import, generated-profile registration, and basic
  Manage actions.
- Added DSP bypass/shaping, shortened-tail preview, descriptor, and durable IR
  library tests.

## 2026-08-15 03:11 EDT — Streamline standalone Reverb controls and history

- Moved one offline Apply action and conventional icon-based preview play,
  pause/resume, and stop controls beside the selected input/output settings.
- Added a live A/B bypass that smoothly transitions to the unprocessed source and
  restores the current effect settings when switched off.
- Split neutral resets by section: mix/timing resets to wet 0, dry 1, and zero
  pre-delay; captured-response shaping resets to its exact bypass values; wet EQ
  resets both filters to off.
- Simplified local history to date/time, output filename, and a compact parameter
  summary. Selecting an available output enables Play Output and Show in Finder.
- Added preview bypass coverage while retaining the existing complete-tail and
  live-parameter tests.
- Replaced the reused Job Launcher artwork with a dedicated Mechana Reverb icon:
  the Mechana hexagon now contains an impulse waveform followed by diminishing
  reflection arcs, reflecting the app's captured-IR convolution workflow.
- Moved history output actions above the table so split-pane sizing cannot hide
  them, enlarged the preview transport buttons and glyphs, and made an active
  preview automatically restart with a newly selected dry-audio file.
- Added a lightweight copy of the dedicated Reverb icon to the application header
  so the window and Dock share the same visual identity.
- Simplified Create IR from Sweep by removing its permanent output-path field.
  Generation now finishes in temporary storage and offers Add to Library, Save to
  File, or Cancel. Library addition proposes a return-derived name, allows a rename,
  and offers Replace Existing or Keep Both for duplicate names while protecting
  factory profiles from replacement.
- Expanded Manage for imported and generated profiles with Rename and confirmed
  Delete actions. Matching generation reports move or delete with their WAV, name
  conflicts are rejected, and factory profiles remain read-only.
- Replaced the per-profile Manage prompt with a dedicated library window whose
  scrolling list shows every IR. Added context-sensitive Rename/Delete, WAV Export, and
  factory-profile protection.
- Combined preview Play and Pause into one stateful control, enlarged Stop and
  Apply, and visually separated offline Apply from preview transport.
- Renamed the lower panel to History and the artifact root to Output folder,
  simplified the product subtitle, and added confirmed deletion of a selected
  history job and all files in its validated job folder.
- Reversed the action-row emphasis so Preview is on the left and Apply is isolated
  on the right, enlarged the Stop-square glyph, and added optional full-clip preview
  looping. Each iteration includes the complete reverb tail and continues until
  Stop; disabling Loop allows the current iteration to finish normally.
- Matched the Play/Pause and Stop button dimensions while enlarging the Stop square,
  and disabled Show in Finder for factory-protected IRs without disabling Export.

## 2026-08-15 05:48 EDT — Prevent preview-only clipping on energetic IRs

- Replaced the live preview's instantaneous hard ceiling with a stereo-linked gain
  limiter that reduces over-range peaks immediately and releases smoothly over
  250 ms.
- Preserved the offline renderer's deterministic two-pass peak protection while
  preventing long, energetic room IRs from turning high-wetness preview playback
  into flat-topped crackling.
- Added regression coverage proving protected preview retains relative waveform
  amplitude rather than independently clamping successive samples.
- Follow-up analysis of a lossless Audio Hijack preview capture found no recurring
  buffer gaps or flat-topped clipping, but did show that zero-look-ahead gain
  changes remained audible under heavy reduction. Added 10 ms stereo-linked
  look-ahead so attenuation begins smoothly before each over-range peak, plus a
  regression test for the pre-peak gain ramp.
