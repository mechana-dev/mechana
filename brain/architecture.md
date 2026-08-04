# Architecture

Last reviewed: 2026-08-04

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
preclude later client-side assembly. Milestone 1 remains in-process; later HTTP+JSON
transport must adapt the same domain boundaries rather than redefine them.

## Boundaries

- Mechana platform: IDs, persistence, scheduling, worker selection, scratch
  reservations, artifact transfer/integrity, leases, retries, attempt fencing,
  cancellation propagation, progress aggregation, invocation placement, cleanup,
  and retention.
- Control plane: execution state, scheduling, leases, capabilities, reservations,
  progress, retries, and artifact metadata.
- Data plane: input, partition, intermediate, and final artifact bytes.
- Scheduler: plugin-agnostic matching and lifecycle; no FFmpeg knowledge.
- Plugin: domain description, options, validation, planning, estimation, work-unit
  execution, assembly, and result validation; no ownership of platform lifecycle
  or placement.
- Module layout: the public contract remains in `mechana-api`; concrete
  implementations live beneath top-level `plugins/` and depend inward on that
  contract. Infrastructure does not depend on concrete plugins except at explicit
  composition/demo entry points.
- Artifact service: identity and transfer; no domain-specific transformation.
- The initial distributed-video data plane is server-mediated: the server
  stream-copies keyframe-aligned input chunks, each worker downloads only its
  assigned chunk and publishes its encoded segment under the live lease, and the
  server performs final assembly and validation. This is not yet the intended
  content-addressed, cache-aware artifact service.
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

See [plugins](plugin-model.md), [artifacts](artifacts.md), and
[scheduler](scheduler.md) for the contracts implied by these boundaries.

## Plugin runtime boundary

A plugin package is installed material; a plugin runtime is an isolated process
that may execute many work units; a work-unit attempt is one leased invocation.
The long-term path is `worker -> runtime manager -> sandbox launcher -> plugin
runtime -> plugin/native tools`. Runtime reuse amortizes startup while preserving
fault and resource isolation from the worker.

Three trust levels are accepted: trusted (potentially in-process), managed
(separate process), and sandboxed (OS-enforced isolation). Enforcement is
platform-specific and capabilities must be advertised honestly. See
[sandbox](sandbox.md); future guarantees may not be inferred from current loading.
