# Artifacts

Last reviewed: 2026-08-04

Artifacts abstract inputs and outputs from topology and storage. Core and plugins
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
  without changing artifact meaning.

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

The video-plugin local slice currently adapts artifacts to paths beneath an
attempt-specific scratch tree. Segment files, concat manifest, separated audio,
and assembled video are intermediates; the requested MP4/MKV path is the final
artifact. This proves the lifecycle locally but is not the storage-neutral artifact
provider or atomic publication contract described above.

The manual two-host proof copies its input to a fixed remote scratch directory and
copies completed remote Matroska segments back to the initiating host before
assembly. Those SCP operations are explicit test scaffolding: they do not provide
stable artifact identities, checksums, atomic publication, or provider-managed
retention.

The distributed sleep/server slice now has a small server-local retention adapter:
each terminal job owns a directory containing an atomically published dashboard
snapshot and an `artifacts/` subtree. The detailed dashboard enumerates regular
files in that subtree as downloads, and purge deletes the whole owned job directory.
This establishes restart persistence and ownership-aware cleanup, but it is not yet
the storage-neutral, checksum-addressed artifact provider accepted above.

The Blender slice uses a server-local packed `.blend` as immutable input,
server-mediated HTTP copies as task inputs, lease-fenced ZIP batches as partition
outputs, and a validated MP4 as the retained final artifact. The input is currently
downloaded once per work unit rather than addressed and cached once per worker.
