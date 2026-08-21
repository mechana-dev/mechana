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
| Plan/assembly topology | Server-side initially; use one storage-neutral contract that preserves later client-side or worker-side assembly. |
| Planes | Separate control-plane metadata from data-plane artifact bytes. |
| Artifacts | Use a storage-neutral artifact abstraction and stable artifact identities. |
| Storage providers | Input, intermediate, and output providers are independently selectable behind artifact references/handles; plugins do not know provider APIs or credentials. |
| Coordinator role | Primarily a control plane, not the mandatory bulk storage server or byte relay. |
| Direct publication | Preserve authoritative, lease-fenced worker publication to requester-controlled storage; concurrent uploads may aggregate worker bandwidth. |
| Assembly placement | One storage-neutral assembly API supports coordinator, client, or worker placement; the plugin defines how and Mechana decides where. |
| Storage security | Require authenticated TLS beyond explicit local development; distinguish optional provider encryption at rest from proposed end-to-end artifact encryption. Key management is deferred. |
| Media | FFmpeg/FFprobe run as external processes in the media plugin. |
| Native reverb boundary | Product-owned reverb DSP, parameters, IR preparation, tests, and JUCE adapters are authoritative in Mechana Audio. Mechana retains only the pure-Java distributed reference plugin. |
| Mechana Audio boundary | Production DSP/products were history-preservingly extracted at `architecture-baseline-1.4`. Keep orchestration in Mechana, expose a public engine contract, and use one future generic descriptor-driven Mechana wrapper without DSP duplication. Licensing remains a separate audited decision. See [Mechana Audio](mechana-audio.md). |
| Video profile | All partitions in a video job initially share one runtime signature. |
| Segmentation | Keyframe-aware and time-based; correctness at boundaries outranks equal byte sizes. |
| Initial audio path | Transcode video in parallel segments; copy optional audio once as a whole stream before final mux. |
| Video quality | Default CRF-based visually lossless mode makes no mathematical-losslessness claim; x265 bit-exact lossless is explicit and may increase size. |
| Worker storage | Workers advertise scratch space; the scheduler reserves before assignment. |
| Worker resources | Workers advertise CPU, RAM, scratch, plugin capabilities, and runtime signatures; matching and reservation remain platform-owned. |
| Locality and cache | Workers may cache immutable, content-verifiable artifacts; locality is an optimization and never a correctness requirement. |
| Plugin runtime | Package, long-lived runtime, and work-unit attempt are distinct; managed and sandboxed execution passes through a worker-owned runtime manager and platform-specific sandbox runtime. |
| Trust levels | Trusted, managed, and sandboxed are explicit modes. Only verified OS enforcement named by a platform guarantee matrix may be presented as a sandbox guarantee. |
| Sandbox filesystem | Plugins receive only logical workspace/input/output/work/log locations and assume no arbitrary host filesystem, home directory, ambient credentials, or hidden persistent state. |
| Sandbox resources | Mechana—not the plugin—resolves, enforces, accounts, and reports CPU, RAM, scratch, timeout, process-tree, network, environment, and cleanup policy. |
| Native runtimes | FFmpeg, Tesseract, Python, Rust-produced binaries, Blender, CUDA tools, and other native dependencies execute behind the same runtime policy as plugin code. |
| Third-party authoring | SDK, templates, simulator, certification, packaging, and vendor-neutral plugin definition/context artifacts are first-class goals. |
| Mechana AI assistant | Directionally provide specialized plugin creation, testing, explanation, refinement, and pattern-reuse assistance without making one AI vendor part of the plugin contract. |
| Git-backed AI knowledge | Version curated platform context, examples, prompts, evaluations, and reusable plugin patterns in Git so each clone inherits them; exclude large base-model weights and private user material. |
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
