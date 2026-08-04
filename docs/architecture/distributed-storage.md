# Distributed storage and artifact flow

**Status:** Accepted architectural direction (pre-implementation)

Last reviewed: 2026-08-04

Mechana treats storage as a first-class abstraction while keeping the coordinator
primarily a control plane. This document describes the intended topology; it does
not claim that the generic provider contracts, direct publication, encryption, or
placement policies are implemented. Current evidence is recorded in
[`brain/current-state.md`](../../brain/current-state.md).

## Architectural commitments

### Accepted

- Jobs may independently choose input, intermediate, and output providers.
- Plugins see artifact references, handles, metadata, and verified staged files,
  never provider-specific credentials or transport details.
- The coordinator owns job authority and artifact metadata but is not the required
  repository or relay for bulk user data.
- Assembly uses one storage-neutral API regardless of placement.
- Transport security, provider encryption at rest, and end-to-end artifact
  encryption are distinct guarantees.

### Directional

- Workers publish completed outputs directly to requester-controlled storage when
  authorization and provider capabilities allow it.
- Mechana may place assembly on a client, coordinator, or capable worker based on
  policy, resources, and data locality.
- Workers may cache immutable verified artifacts, and scheduling may later prefer
  useful locality without depending on it for correctness.

### Proposed and deferred

- Optional end-to-end artifact encryption with requester-controlled keys is
  **Proposed**.
- Key custody, recovery, rotation, delegation, and revocation are **Deferred**.

## Topology

In a coordinator-relay topology, all result bytes converge on one server before
reaching their destination:

```text
workers -> coordinator -> requester storage
```

The preferred provider-based topology permits authorized direct publication:

```text
             +-> input provider
coordinator -+-> artifact metadata and job authority
             +-> leases, policy, and progress

worker A ----\
worker B -----+-> requester-controlled intermediate/output provider
worker C ----/
```

The second topology allows simultaneous uploads to aggregate independent worker
bandwidth. It is BitTorrent-like only in this throughput property: it is not an
anonymous peer network, and the coordinator remains authoritative.

## End-to-end lifecycle

1. Submission identifies input, intermediate, and output provider policies.
2. Mechana validates references and grants attempt-scoped access rather than
   exposing long-lived provider credentials to plugins.
3. Workers stage and verify only required artifacts.
4. The authoritative attempt publishes outputs atomically to the selected
   provider; stale attempts cannot publish accepted results.
5. Assembly receives the explicit ordered result set through the same artifact
   API wherever it runs.
6. Final validation precedes publication, retention, and scratch cleanup.

This model preserves a server-mediated adapter for simple deployments while
allowing distributed transfer and requester custody at larger scale.

Detailed durable context lives in [`brain/storage.md`](../../brain/storage.md).
