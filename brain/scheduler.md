# Scheduler

Last reviewed: 2026-08-01

The scheduler is task-agnostic. It assigns runnable stages using dependencies,
plugin/runtime capabilities, leases, and declared resources. It does not inspect
media payloads or transfer artifact bytes.

Workers advertise supported plugin/runtime signatures and scratch-space capacity.
An assignment requiring scratch is eligible only when compatible unreserved
capacity is available. Reservation and assignment must act as one logical state
transition; capacity is released on completion, cancellation, definitive failure,
or lease expiry.

## Invariants

- `available scratch = advertised capacity - active reservations`; never schedule
  based on raw free-space observation alone.
- Requested capacity includes expected inputs, intermediates, outputs, and a
  documented safety allowance where estimates are uncertain.
- A worker is not assigned incompatible plugin/runtime work.
- One partition has at most one authoritative active lease.
- Renewal extends only the matching live lease; stale completion and publication
  are rejected.
- Retry creates a distinct attempt while preserving logical partition identity.
- Assembly becomes runnable only after every required partition artifact is
  authoritative and compatible.
- Reservation accounting survives every state transition without leaks or
  double-release; durable recovery requirements are a later explicit decision.

The current scheduler implements leases for sleep tasks but not generic resources
or scratch reservations; see [current state](current-state.md).

The video-plugin demo uses a bounded in-process `ExecutorService` after checking a
conservative scratch estimate against local usable space. This is deliberately not
scheduler integration: it neither advertises nor reserves capacity, assigns remote
workers, retries attempts, nor changes lease ownership.
