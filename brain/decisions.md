# Accepted decisions

Last reviewed: 2026-08-01

This is the concise decision index. Detailed implications live in the linked files.

| Decision | Accepted outcome |
| --- | --- |
| Toolchain | Java 25 with Maven; IntelliJ IDEA on macOS is the primary development setup. |
| License | Apache License 2.0; design and docs should support outside contributors. |
| Core model | Plugin-driven and task-agnostic. Domain semantics stay in plugins. |
| Milestone 1 | In-process execution before distributed transport. |
| Transport | HTTP+JSON later, as an adapter around core contracts. |
| Work graph | `plan -> parallel partitions -> assemble`. |
| Plan/assembly topology | Server-side initially; preserve a path to later client-side assembly. |
| Planes | Separate control-plane metadata from data-plane artifact bytes. |
| Artifacts | Use a storage-neutral artifact abstraction and stable artifact identities. |
| Media | FFmpeg/FFprobe run as external processes in the media plugin. |
| Video profile | All partitions in a video job initially share one runtime signature. |
| Segmentation | Keyframe-aware and time-based; correctness at boundaries outranks equal byte sizes. |
| Worker storage | Workers advertise scratch space; the scheduler reserves before assignment. |
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
- External-process failures are bounded, captured, and translated into plugin task
  results without crashing the host process.

See the topic files for rationale and operational detail. A new decision should
update this index and receive a timestamped project-note entry.
