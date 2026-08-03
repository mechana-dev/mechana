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
- Worker-presence expiry and task-lease expiry are independent. Presence
  heartbeats must continue while idle, staging artifacts, executing plugins, or
  publishing outputs; task heartbeats renew ownership without altering progress.
- Retry creates a distinct attempt while preserving logical partition identity.
- Assembly becomes runnable only after every required partition artifact is
  authoritative and compatible.
- Reservation accounting survives every state transition without leaks or
  double-release; durable recovery requirements are a later explicit decision.

The current scheduler implements the same capability-matched renewable lease and
attempt-fencing path for sleep tasks and distributed video segments, but not
generic resources or scratch reservations; see [current state](current-state.md).
The HTTP worker sends presence heartbeats every three seconds and the server marks
it offline after fifteen seconds without contact. The dashboard retains that offline
worker for two minutes after its last contact, then removes it from the presence
registry. Each active assignment also has a lease-token heartbeat paced from its
advertised lease duration, so slow staging
or quiet external-process startup does not cause a false retry.

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

The sleep scheduler supports terminal abort and cooperative pause. Both fence
active lease tokens so late updates are rejected. Pause retains succeeded work,
marks unfinished work `PAUSED`, and excludes paused time from job elapsed time;
resume queues only those unfinished units under the same job ID. Interrupted work
units restart from zero.

Cancelled or failed sleep jobs can be resumed as a new job with explicit
`resumedFromJobId` lineage. Source history remains immutable, corresponding
succeeded work units are reused, and only incomplete units are queued. This first
slice reuses sleep work-unit completion state; generic artifact identity and
availability validation plus plugin-defined mid-work-unit checkpoints remain
future contracts.

For local video workflows, configured workers still means executor parallelism and
active workers means currently running work units. The dashboard presents those
generic values without claiming scheduler ownership of the local executor.

`TwoHostVideoJobMain` remains a manual operational proof: it
uses a fixed four-local/four-SSH assignment and aggregates both processes' FFmpeg
progress. It does not call the scheduler, discover capacity, acquire leases,
reserve scratch, fence attempts, or retry failed work, so it must not be treated as
evidence that distributed media scheduling is implemented.

The server-managed video path is separate evidence: it plans the requested number
of segment work units, queues them with the `video-ffmpeg` capability, transfers
inputs through the server, accepts outputs only for the matching live lease, and
assembles only after all segments succeed. It currently lacks scratch reservations,
input caching, checksummed content identity, and durable recovery of
active/intermediate state.

A 12-segment operational proof used three workers on each of four heterogeneous
hosts. It also exposed an important boundary: capability advertisement currently
does not prove that an external executable remains discoverable in the worker's
service environment. A worker may therefore register and lease `video-ffmpeg`
work before failing at process launch; runtime preflight/capability health is not
yet part of scheduler matching.
