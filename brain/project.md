# Project

Last reviewed: 2026-08-04

Mechana is an open-source, plugin-driven distributed computation platform. Plugins
describe complete computations; the platform executes them safely across workers
without learning their domain semantics.

## Project values

1. **Simplicity before sophistication.** Important concepts and state transitions
   should be easy to explain and observe.
2. **Plugins describe computation.** Plugin authors focus on their domain.
3. **Infrastructure owns distributed systems.** Scheduling, placement, leases,
   retries, artifact movement, reservations, cancellation, and cleanup stay in
   Mechana.
4. **Responsibilities remain separate.** Operators understand their resources,
   authors their computation, and contributors the platform; no audience must
   understand the whole system.
5. **Complexity must justify itself.** Generality is introduced only for a
   demonstrated need.
6. **Humans and AI should understand the architecture.** Canonical, concise,
   vendor-neutral documents and specifications are part of the product.
7. **Operator trust is explicit.** Operators know what CPU, memory, scratch,
   network access, and plugin trust they contribute and what is actually enforced.

## Accepted foundation

- Language/toolchain target: Java 25 and Maven.
- Primary development environment: IntelliJ IDEA on macOS.
- License: Apache License 2.0 (`../LICENSE`).
- The constrained parallel model is `plan -> parallel work units -> assemble`,
  not a general DAG engine.
- Contributor readiness is a product requirement: understandable modules,
  reproducible commands, documented decisions, and reviewable changes.
- Third-party authoring is a first-class surface with an SDK, templates, simulator,
  verification/certification, packaging, and human- and AI-readable artifacts.
- Significant work is recorded append-only in `../docs/PROJECT-NOTES.md`.

See [current state](current-state.md) for repository evidence and
[roadmap](roadmap.md) for intended sequencing.
