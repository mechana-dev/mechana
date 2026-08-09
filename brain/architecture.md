# Architecture

## Implemented storage boundary (2026-08-08)

The server registers `server-local` behind the storage-neutral artifact-store
contract. Scheduler-managed video uses references at platform boundaries and
verified local staging at FFmpeg boundaries. HTTP worker transfer remains the
compatible byte path; control-plane task messages continue to carry URLs and
metadata rather than embedding large artifacts. FFmpeg also has an initial
client-local option: launcher-side input chunking, tokenized direct worker transfer,
lease-identified direct worker output publication, verified launcher-side FFmpeg
assembly, and a client-owned completed reference. The scheduler requires an explicit
direct-video worker capability, so old workers cannot accidentally lease these tasks.
This remains one workload with two placement modes, not yet the general
distributed-storage topology below.

Last reviewed: 2026-08-06

## Stable shape

Mechana separates the control plane from the data plane and keeps the execution
core task-agnostic. A plugin encapsulates the complete computational contract for
its domain: supported input/output descriptions, processing options, authoritative
validation, planning/decomposition, per-work-unit execution, resource estimation,
assembly/reassembly, and final-result validation. Artifacts cross stages through
an abstraction rather than assumed local paths.

Partitioned jobs follow one explicit graph:

`plan -> parallel work units -> assemble`

- **Plan** consumes validated input and options and emits deterministic work-unit
  descriptions, resource estimates, and explicit assembly requirements.
- **Execute** runs independent work units in parallel where resources allow.
- **Assemble** validates and consumes the complete required output set, creates the
  final artifact, and validates that result; it is a first-class stage with
  observable failure.

This constrained execution model is intentional and is not a generic DAG engine.
Plugins describe what must happen. Mechana determines where, when, and under which
attempt it happens.

Initial topology performs planning and assembly server-side. Contracts must not
preclude later client-side or worker-side assembly. The plugin defines how to
assemble while Mechana chooses placement through the same artifact-based API.
Milestone 1 remains in-process; later HTTP+JSON
transport must adapt the same domain boundaries rather than redefine them.

## Boundaries

- Mechana platform: IDs, persistence, scheduling, worker selection, scratch
  reservations, artifact transfer/integrity, leases, retries, attempt fencing,
  cancellation propagation, progress aggregation, invocation placement, cleanup,
  and retention.
- Control plane: execution state, scheduling, leases, capabilities, reservations,
  progress, retries, and artifact metadata.
- Data plane: input, partition, intermediate, and final artifact bytes.
- Storage providers: independent input, intermediate, and output destinations
  hidden behind artifact references and handles. The coordinator is not the
  mandatory bulk-data repository or relay.
- Publication topology: directional support for workers to publish directly to
  authorized requester-controlled providers. Parallel publication naturally
  aggregates worker bandwidth while lease fencing and the coordinator preserve
  authoritative job state.
- Scheduler: plugin-agnostic matching and lifecycle; no FFmpeg knowledge.
- Plugin: domain description, options, validation, planning, estimation, work-unit
  execution, assembly, and result validation; no ownership of platform lifecycle
  or placement.
- Module layout: the public contract remains in `mechana-api`; concrete
  implementations live beneath top-level `plugins/` and depend inward on that
  contract. Infrastructure does not depend on concrete plugins except at explicit
  composition/demo entry points.
- Artifact service: identity and transfer; no domain-specific transformation.
- The default distributed-video data plane is server-mediated: the server
  stream-copies keyframe-aligned input chunks, each worker downloads only its
  assigned chunk and publishes its encoded segment under the live lease, and the
  server performs final assembly and validation. Client-local selection preserves
  that relay through segment publication, then the launcher downloads verified
  segments and performs final assembly into its chosen output directory. Direct
  worker-to-requester publication is not yet implemented.
- The fractal reference path has no input data plane. Planning creates immutable,
  deterministic image-index ranges; workers publish one batch artifact per work
  unit, and plugin-owned server composition validates and collects those batches
  into individual images, a manifest, a contact sheet, and a collection archive.
- The OCR reference path rasterizes PDFs during server-side planning, transfers
  immutable per-page PNGs to capable workers, receives lease-fenced page-text
  batches, and performs ordered Markdown and Unicode LaTeX source assembly on the
  server. PDF parsing does not occur on workers and Tesseract-specific behavior
  remains in the plugin.
- The Blender reference path transfers one immutable packed scene to each frame
  batch, executes independent contiguous ranges, and collects lease-fenced PNG
  archives for server-side validation and movie assembly. Blender command and
  frame semantics remain plugin-owned.
- Observability: the platform owns job/work-unit state, weighted progress,
  attempts, workers, events, and dashboard presentation. Plugins emit the generic
  `JobObserver` lifecycle and may attach bounded string display fields; dashboard
  and scheduler code do not interpret plugin-specific semantics.
- The running server exposes a stable master dashboard that composes generic job
  snapshots with its worker-presence registry. Active scheduling and worker
  presence remain in memory. Terminal dashboard snapshots and server-owned
  artifacts are archived beneath the server data directory, loaded after restart,
  linked from job-specific views, and removed together only through explicit purge.
- Pause is a non-terminal scheduler transition: it fences live attempts, preserves
  completed work, and resumes unfinished units under the same job identity.
  Resuming terminal history creates a new job with explicit source lineage rather
  than mutating the archived terminal record. Cross-plugin artifact reuse requires
  a future platform validation contract; the implemented first slice is sleep-only.
- Workers advertise their host IP address with capabilities during registration
  and lease polling. The server retains that address as worker presentation
  metadata; it does not use it as proof of identity or trust.
- Fleet presence and task ownership are distinct control-plane signals. A worker
  heartbeat indicates process/server reachability even while plugin code is busy;
  a lease-token heartbeat independently renews one authoritative task attempt.
  Neither signal fabricates plugin progress.
- Remote worker process lifecycle is a separate operational boundary. The optional
  Worker Host Agent launches and tracks only its own child worker processes and
  exposes an HTTP/JSON API that is authenticated by default. The Swing Worker Control App is an API
  client; it never turns a hostname into an implicit shell command and neither
  module is a dependency of the scheduler, worker, or plugin infrastructure.
- User job lifecycle is a separate client boundary. The Client Job Launcher
  consumes server-provided submission descriptors and scheduling availability;
  it does not manage workers or embed plugin computation semantics. Its initial
  Swing implementation follows Worker Control conventions and adapts the existing
  HTTP endpoints while the future plugin manifest becomes authoritative.

See [plugins](plugin-model.md), [artifacts](artifacts.md), and
[storage](storage.md) and [scheduler](scheduler.md) for the contracts implied by
these boundaries.

## Plugin runtime boundary

**Accepted:** plugin packages, reusable runtimes, and leased work-unit attempts
are distinct. Managed and sandboxed execution passes through a worker-owned plugin
runtime manager and platform-specific sandbox runtime; native tools remain inside
that boundary. **Directional:** Linux, Windows, and macOS provide one policy API
through different enforcement implementations and guarantee matrices.

The canonical [sandbox architecture](sandbox.md) defines the trust levels,
filesystem and resource model, manifest proposal, author contract, native-runtime
isolation, and certification direction. Future guarantees may not be inferred
from current plugin loading.

The implementation foundation lives in `mechana-plugin-runtime` and `plugin-host`.
It uses one-request NDJSON over standard input/output because the current work-unit
contract is request/response-shaped and does not need a network listener inside
the attempt. Explicitly sandboxed macOS and Linux workers route every current
concrete plugin through this boundary, stage network inputs before launch, and
require absolute operator-declared native executable paths where applicable.
Windows now uses a verified AppContainer and Job Object backend for the pure-Java
plugin-host path. Native Windows plugin runtimes still require individual
certification.
