# Artifacts

Last reviewed: 2026-08-01

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
