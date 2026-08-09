# Storage and artifact fabric

Last reviewed: 2026-08-08

## Status

- **Accepted:** storage is a first-class platform abstraction; the coordinator is
  primarily a control plane; jobs may use independent input, intermediate, and
  output providers; plugins and assembly depend only on artifact abstractions.
- **Directional:** workers publish directly to requester-controlled providers,
  assembly placement is location-independent, and scheduling becomes
  locality-aware as trustworthy artifact identity and cache reporting mature.
- **Proposed:** optional platform-level end-to-end artifact encryption with
  requester-controlled keys.
- **Deferred:** concrete key management, recovery, rotation, delegation, and
  revocation protocols.

## Implemented slice

`server-local` is now the zero-configuration provider for input, intermediate,
and output roles, backed by `ArtifactStore`, `ArtifactReference`,
`StorageSelection`, and `ArtifactStoreRegistry`. The scheduler-managed FFmpeg
video workload uses this abstraction end to end: source ingest, worker input
segments, lease-fenced worker publications, verified assembly staging, final
publication, and completed-job presentation all retain provider/key/size/SHA-256
identity. Native FFmpeg still receives private staged paths inside server or
worker workspaces.

Only the FFmpeg workload is migrated end to end. Other distributed workloads
still use their existing server-owned path adapters. Client-local, Google Drive,
S3, direct worker-to-requester publication, and client-side assembly remain
future work.

## First-class storage model

A job may select different providers for each artifact role:

```text
input provider -> staged work-unit artifacts -> intermediate provider
                                               -> output provider
```

Input, intermediate, and final-output storage are independent choices. A local
workstation, NAS, cloud object store, user drive, or Mechana-managed provider may
occupy any role. Core contracts and plugins do not receive provider-specific
paths, credentials, or APIs. They use stable `ArtifactReference` values,
`ArtifactHandle`-style access, and verified local staging supplied by Mechana.

The larger responsibility is an artifact fabric: Mechana identifies, locates,
stages, validates, transfers, caches, assembles, retains, and eventually cleans up
artifacts while storage providers implement byte persistence and transport.

## Coordinator philosophy

The coordinator owns scheduling, orchestration, job state, leases, retries,
progress, worker coordination, policy, and artifact metadata. It is not intended
to be the default bulk storage server or mandatory data relay.

> The ideal Mechana coordinator moves metadata whenever possible and bulk data
> only when necessary.

Server-mediated transfer remains a valid adapter and is the shape of current
distributed reference paths. It is not the long-term requirement for every byte.

## Direct publication and aggregate bandwidth

Workers should be able to publish authoritative work-unit outputs directly to the
requester's chosen intermediate or final provider. Publication remains atomic,
content-verifiable, lease-fenced, and governed by platform policy.

```text
Worker A ----\
Worker B -----+----> requester-controlled storage
Worker C ----/
```

This yields a BitTorrent-like bandwidth property: independent producers transfer
concurrently, so aggregate worker upload capacity can replace a single
coordinator-to-requester pipe as the bottleneck. Unlike BitTorrent, the
coordinator retains authoritative scheduling and job state, workers compute
results rather than anonymously replicate them, and destinations are explicitly
authorized. The comparison describes aggregate transfer topology, not a
peer-to-peer protocol commitment.

Direct publication also lets workers release attempt scratch after verified
upload and places results at the destination needed for final use or assembly.

## Location-independent assembly

The plugin defines **how** to assemble; Mechana decides **where** assembly runs.
The same assembly contract must support client-side, coordinator-side, or
worker-side execution without changing artifact identity or plugin logic.
Assembly consumes explicit artifact references/handles and publishes artifact
references; it must not assume shared paths or a particular provider.

Coordinator-side assembly is the initial topology. Alternative placement is
directional and may depend on authorization, runtime capability, artifact
locality, output destination, cost, and resource availability.

## Security model

- **Accepted:** artifact transport uses authenticated TLS outside explicitly
  identified local-development environments.
- **Accepted:** providers may offer encryption at rest; Mechana records and
  honors relevant provider policy without claiming every provider implements the
  same guarantee.
- **Proposed:** optional end-to-end artifact encryption can protect bytes from the
  storage provider and coordinator, using requester-controlled keys and
  decrypting only in authorized execution or assembly contexts.
- **Deferred:** key generation, storage, recovery, rotation, sharing, delegation,
  revocation, hardware-backed custody, and multi-user policy. No end-to-end
  encryption guarantee exists until those protocols and threat models are
  explicitly designed and verified.

Transport security, provider encryption at rest, and end-to-end artifact
encryption are separate layers and must not be presented as interchangeable.

## Locality and caching

Immutable, content-verifiable identities make future caching safe. Workers may
retain bounded non-authoritative copies and advertise locality hints. A future
scheduler may prefer a compatible worker near a large input or destination while
still honoring resource reservations, fairness, capabilities, and leases.

Cache hits improve cost and speed but never determine correctness. Eviction is
safe, cache and scratch accounting remain separate, and plugins neither select
workers nor manage caches.

See [artifacts](artifacts.md), [architecture](architecture.md), and
[scheduler](scheduler.md). Implementation facts remain in
[current state](current-state.md).
# Client launcher integration

The launcher treats output selection and completed artifacts as provider-aware.
`server-local` remains the implemented default. Provider-specific browsing,
authorization, client-local transfer, Google Drive, and S3 are future provider
work; the generic launcher contract preserves a provider/key identity and an
ownership flag so purge and open actions need not assume server storage.
