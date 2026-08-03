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
