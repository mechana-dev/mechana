# Worker management

Last reviewed: 2026-08-04

Worker management is an operational layer around workers, not part of the plugin
or scheduling domain. Its purpose is to make contributed compute understandable.

## Operator contract

An operator chooses:

- worker count and CPU contribution;
- memory limit;
- scratch allocation and optional separately bounded cache;
- allowed plugins/signers and trust level;
- network policy;
- start/stop and host availability.

The control surface should show current consumption, reservations, active plugin,
work-unit progress, runtime/sandbox guarantees, and whether limits are enforced or
advisory. Defaults should be safe and few. Operators should not need to understand
leases, partition plans, artifact internals, or scheduler policy.

## Worker advertisement

Workers report stable identity plus available CPU, RAM, scratch, plugin
capabilities, runtime signatures/health, supported trust/enforcement features, and
later cache/locality hints. Presence is distinct from task-lease ownership.
Advertisement never grants work by itself; the scheduler matches requirements and
reserves resources atomically.

## Scratch model

Scratch is temporary capacity for staged inputs, intermediates, outputs awaiting
publication, and a safety allowance. Reservation begins with assignment and ends
on authoritative completion, cancellation, definitive failure, or lease expiry,
followed by deterministic cleanup. The scheduler does not knowingly overcommit
advertised capacity. Cached immutable artifacts use separate accounting.

## Host management direction

An optional host agent may start, stop, and observe a bounded set of local worker
children. A desktop or web controller calls that explicit API; hostnames never
become implicit remote shell commands. Authentication, TLS, durable child adoption,
service installation, and remote administration are production requirements, not
assumed guarantees.
