# Scheduler

Last reviewed: 2026-08-02

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

The scheduler feeds lease assignment, progress, completion, failure, and requeue
transitions into the same generic in-memory job monitor used by local plugin
workflows. Sleep jobs therefore expose a live dashboard with authoritative leased
worker identities. Active scheduling state remains volatile, while terminal
dashboard snapshots and server-owned artifacts are archived by the server and
loaded across restarts.

The scheduler can also return newest-first snapshots for every retained job. The
server combines those snapshots with its worker-presence registry for the master
dashboard. Terminal transitions capture their completion instant so job and
work-unit elapsed durations remain stable after completion.

The sleep scheduler supports terminal abort: it marks unfinished work units
`CANCELLED`, fences active lease tokens, rejects late updates, and lets the server
archive the result. Pause/resume, job lineage, completed-work reuse, and
plugin-defined mid-work-unit checkpoints are not implemented yet.

For local video workflows, configured workers still means executor parallelism and
active workers means currently running work units. The dashboard presents those
generic values without claiming scheduler ownership of the local executor.

`TwoHostVideoJobMain` goes one step farther only as a manual operational proof: it
uses a fixed four-local/four-SSH assignment and aggregates both processes' FFmpeg
progress. It does not call the scheduler, discover capacity, acquire leases,
reserve scratch, fence attempts, or retry failed work, so it must not be treated as
evidence that distributed media scheduling is implemented.
