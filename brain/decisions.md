# Accepted decisions

Last reviewed: 2026-08-04

This is the concise decision index. Detailed implications live in the linked files.

| Decision | Accepted outcome |
| --- | --- |
| Toolchain | Target Java release 25 with Maven; builds may run on JDK 25 or newer. IntelliJ IDEA on macOS is the primary development setup. |
| License | Apache License 2.0; design and docs should support outside contributors. |
| Core model | Plugin-driven and task-agnostic. Domain semantics stay in plugins. |
| Plugin contract | A plugin encapsulates supported input/output descriptions, processing options, authoritative validation, planning/decomposition, per-work-unit execution, resource estimation, assembly/reassembly, and final-result validation. |
| Responsibility boundary | Plugins describe what must happen; Mechana determines where, when, and under which attempt it happens and owns platform lifecycle concerns. |
| Milestone 1 | In-process execution before distributed transport. |
| Transport | HTTP+JSON later, as an adapter around core contracts. |
| Work graph | Constrained to `plan -> parallel work units -> assemble`; no generic DAG engine. |
| Plan/assembly topology | Server-side initially; preserve a path to later client-side assembly. |
| Planes | Separate control-plane metadata from data-plane artifact bytes. |
| Artifacts | Use a storage-neutral artifact abstraction and stable artifact identities. |
| Media | FFmpeg/FFprobe run as external processes in the media plugin. |
| Video profile | All partitions in a video job initially share one runtime signature. |
| Segmentation | Keyframe-aware and time-based; correctness at boundaries outranks equal byte sizes. |
| Initial audio path | Transcode video in parallel segments; copy optional audio once as a whole stream before final mux. |
| Video quality | Default CRF-based visually lossless mode makes no mathematical-losslessness claim; x265 bit-exact lossless is explicit and may increase size. |
| Worker storage | Workers advertise scratch space; the scheduler reserves before assignment. |
| Worker resources | Workers advertise CPU, RAM, scratch, plugin capabilities, and runtime signatures; matching and reservation remain platform-owned. |
| Locality and cache | Workers may cache immutable, content-verifiable artifacts; locality is an optimization and never a correctness requirement. |
| Plugin runtime | Package, long-lived runtime, and work-unit attempt are distinct; managed and sandboxed runtimes execute outside the worker. |
| Trust levels | Trusted, managed, and sandboxed are explicit modes. Only verified OS enforcement may be presented as a sandbox guarantee. |
| Third-party authoring | SDK, templates, simulator, certification, packaging, and vendor-neutral plugin definition/context artifacts are first-class goals. |
| Document processing | OCR evolves toward layout, figures, tables, structured outputs, confidence, assembly, and validation inside the plugin. |
| Values | Prefer simplicity, clarity, cognitive separation, honest operator guarantees, and architecture readable by humans and AI. |
| Worker process management | Optional remote lifecycle control uses a distinct authenticated host-agent HTTP API. Hostnames are never treated as permission or a shell transport, and the agent manages only children it launches. |
| Project record | Append material updates to timestamped `docs/PROJECT-NOTES.md`. |

## Cross-cutting invariants

- Planning is deterministic for the same validated inputs and declared profile.
- Partition outputs are uniquely identified and safe to retry without ambiguity.
- Assembly begins only when its required partition artifacts are complete and
  compatible; ordering is explicit.
- A stale lease cannot publish authoritative completion or artifacts.
- Capacity is not merely observed: reserved scratch is deducted until released.
- Artifact bytes do not flow through scheduler/control-plane message objects.
- Plugin-specific fields do not leak into generic scheduler policies.
- Plugins do not own IDs, persistence, scheduling, worker selection, scratch
  reservations, artifact transfer/integrity, leases, retries, attempt fencing,
  cancellation propagation, progress aggregation, invocation placement, cleanup,
  or retention.
- Plugin resource estimates inform Mechana's decisions but do not confer placement
  or reservation authority on the plugin.
- External-process failures are bounded, captured, and translated into plugin task
  results without crashing the host process.

See the topic files for rationale and operational detail. A new decision should
update this index and receive a timestamped project-note entry.
