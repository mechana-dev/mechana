# Worker management

Last reviewed: 2026-08-07

Worker management is an operational layer around workers, not part of plugin
semantics or scheduler placement. Its purpose is to make contributed compute
understandable and controllable.

## Operator contract

An operator chooses worker count, CPU contribution, memory limit, scratch and
optional cache allocation, allowed plugins/signers and trust level, network policy,
and host availability. The control surface should show consumption, reservations,
active plugin/work-unit progress, and whether runtime or sandbox limits are
enforced or advisory. Defaults should be safe and few. Operators should not need
to understand leases, partition plans, artifacts, or scheduler policy.

## Worker advertisement and scratch

Workers report stable identity plus available CPU, RAM, scratch, plugin
capabilities, runtime signatures/health, supported trust/enforcement features, and
later cache/locality hints. Presence is distinct from task-lease ownership.
Advertisement never grants work; the scheduler matches and reserves atomically.

Scratch holds staged inputs, intermediates, outputs awaiting publication, and a
safety allowance. Reservation begins with assignment and ends on completion,
cancellation, definitive failure, or lease expiry, followed by cleanup. The
scheduler does not knowingly overcommit capacity. Immutable caches use separate
accounting.

## Implemented optional host management

The `worker-host-agent` runs on a controlled machine, and the Swing
`worker-control-app` connects to an explicitly configured agent over HTTP/JSON.
An IP address alone does not provide process-creation authority.

- `GET /api/v1/health` reports reachability.
- `GET /api/v1/workers` reports requested/live child counts, state, diagnostics,
  IDs, PIDs, and start times.
- `POST /api/v1/workers/start` adds only the deficit needed to reach the requested
  count and rejects counts above the configured maximum.
- `POST /api/v1/workers/stop` gracefully terminates tracked children, then forcibly
  terminates survivors after the configured timeout.

Agent-generated worker IDs begin with the configured machine name (or normalized
hostname) and a UUID. The agent launches without shell interpolation, writes
output below its configured working directory, and manages only children it
launches. Workers started elsewhere are neither discovered nor stopped, and an
agent restart does not adopt surviving children.

Management routes require a bearer token for non-loopback binding unless
`allow-unauthenticated=true` explicitly enables development mode. Loopback may
omit a token for local tests. Shared tokens are stored in local properties and
sent over plain HTTP; unauthenticated mode allows any reachable caller to manage
workers. These modes are suitable only for an appropriately firewalled trusted
LAN/tailnet. TLS, OS credential storage, token rotation, roles, audit logging,
service installers, and durable adoption remain production work.

The current host agent controls process count, not the full accepted CPU, RAM,
scratch, cache, plugin allowlist, network, or sandbox policy. Those remain roadmap
items and must not be presented as implemented guarantees. See
[`WORKER-CONTROL.md`](../docs/WORKER-CONTROL.md) for current setup.

Worker Control stores a complete profile per hostname and restores it when the
operator changes hosts. Legacy global settings migrate to the previously selected
host, while missing profiles receive defaults without replacing a later saved
customization. The known development hosts are pre-populated with their established
SSH usernames and ports and the complete supported plugin capability set; SSH
authentication continues to rely on existing keys and batch-mode OpenSSH rather
than password storage.
