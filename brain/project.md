# Project

Last reviewed: 2026-08-01

Mechana is an open-source, plugin-driven execution system for task-agnostic,
partitionable workloads. The core coordinates work without knowing media formats
or other plugin-specific semantics.

## Accepted foundation

- Language/toolchain target: Java 25 and Maven.
- Primary development environment: IntelliJ IDEA on macOS.
- License: Apache License 2.0 (`../LICENSE`).
- Milestone 1 topology: in-process execution, with clean boundaries for later
  distribution.
- Later transport: HTTP+JSON, kept outside the core domain model.
- Contributor readiness is a product requirement: understandable modules,
  reproducible commands, documented decisions, and reviewable changes.
- Significant work is recorded append-only in timestamped
  `../docs/PROJECT-NOTES.md` entries.

See [current state](current-state.md) for what the present branch actually does and
[roadmap](roadmap.md) for the intended sequence.
