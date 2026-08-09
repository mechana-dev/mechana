# Artifacts

Last reviewed: 2026-08-08

Artifacts abstract inputs, intermediates, and outputs from topology and storage.
Jobs may choose each provider independently. Core and plugins
exchange stable references plus metadata; a provider handles byte transfer.

An artifact reference must be sufficient to identify the object and validate what
was read (for example identity, role, size when known, and checksum/content digest).
Local files may back Milestone 1, but local paths are an adapter detail.

## Invariants

- Scheduler/control-plane messages carry references and metadata, not large bytes.
- Inputs are immutable for a job attempt; published outputs are content-verifiable.
- Partition and attempt identity prevent collisions and ambiguous retries.
- Publication is atomic from the consumer's perspective: incomplete output is not
  a completed artifact.
- Assembly receives an explicit ordered set and verifies required compatibility.
- Cleanup respects ownership: scratch copies may be removed after release, while
  authoritative artifacts follow provider retention rules.
- The API must support server-side assembly now and later client-side assembly
  or worker-side assembly without changing artifact meaning or plugin logic.
- Provider credentials and APIs remain outside plugin contracts. Access should be
  narrowly scoped to the relevant attempt and artifact operation.
- Authoritative direct worker publication is lease-fenced, content-verifiable,
  and atomic even when coordinator storage is bypassed.

The coordinator primarily carries artifact metadata and authority. Direct worker
publication to requester-controlled providers is directional and can aggregate
the independent bandwidth of the worker fleet rather than concentrating all
result bytes through one server. See [storage](storage.md) for provider roles,
security layers, assembly placement, and the limits of the BitTorrent analogy.

Accepted task completions also carry byte counters for staged inputs, published
outputs, and downloaded plugin packages. The server aggregates only lease-fenced
accepted attempts by worker and publishes `transfer-summary.json` with the completed
job, so stale or retried attempts cannot inflate authoritative job totals. The
summary identifies whether bulk artifacts followed server-worker or direct
client-worker routes; it does not put the artifact bytes on the control plane.

## Locality and caching direction

Immutable artifacts should have content-verifiable identities. A worker may keep
a bounded local cache after an attempt releases scratch. Entries are reusable
copies, never the only authoritative artifact, and eviction cannot affect
correctness. Workers may advertise cached identities or compact locality hints;
the scheduler may prefer a compatible worker that already has a large input while
still honoring capabilities, resources, fairness, and leases.

Caching is an infrastructure optimization. Plugins describe artifact requirements;
Mechana owns transfer, verification, cache placement, eviction, and locality-aware
scheduling. Cache storage and attempt scratch are accounted separately.

The scheduler-managed FFmpeg slice now addresses source ingest, per-worker input,
worker segment output, assembly input, and the retained final video through
`ArtifactReference`. Server-local writes are atomic and return size/SHA-256
metadata. Worker publication remains behind scheduler lease validation, and the
assembler stages each referenced segment into its private workspace while
verifying size and SHA-256 before invoking FFmpeg. Completed-job JSON retains the
existing download URL while adding provider, key, and SHA-256 metadata. For a
client-local FFmpeg job, the launcher instead stages verified segment references
into its selected scratch directory, assembles into its selected output directory,
and reports the client-owned final reference; the server retains metadata only.
The launcher itself creates and serves keyframe-aligned input chunks, and compatible
workers publish attempt outputs directly to its tokenized receiver. The server records
the hash of the accepted lease token for each segment, allowing the launcher to select
only accepted attempts even when stale attempt bytes reached client scratch.

The older manual two-host proof copies its input to a fixed remote scratch directory and
copies completed remote Matroska segments back to the initiating host before
assembly. Those SCP operations are explicit test scaffolding: they do not provide
stable artifact identities, checksums, atomic publication, or provider-managed
retention.

The distributed Sleep slice publishes its terminal `job-summary.json` through
`ArtifactStore`; completed history reports the same provider/key/size/SHA-256
shape as every other artifact while the dashboard snapshot remains control-plane
state. Purge still deletes the server-owned job directory.

Fractal, OCR, and Blender publish lease-fenced ZIP batches as artifact references,
stage and verify them into private assembly scratch, and publish final result trees
through the output store. OCR rasterized page inputs and the packed Blender scene
are immutable artifacts served with size/SHA metadata; workers verify those values
while staging local tool inputs. Inputs are still downloaded once per work unit
rather than cached once per worker.
For `client-local`, inputs and accepted ZIP batches travel directly between the
requester and capability-gated workers; the coordinator retains only task, lease,
and artifact metadata. Plugin-owned assemblers produce the same logical results
locally and completed history records provider/key/size/SHA-256.
# Launcher presentation

Client job history presents artifacts as provider, stable key, size, and an
optional provider action URL. UI code must not reconstruct local filesystem paths
from an artifact identity. The first implementation reports durable completed-job
files as `server-local` references and client-assembled results as
`client-local` references. Cloud references remain directional.
