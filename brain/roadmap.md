# Roadmap

Last reviewed: 2026-08-04

This sequence expresses intent, not completion. Check
[current state](current-state.md) before reporting progress. Architecture Baseline
1 closes design stabilization; implementation should preserve it or record an
explicit superseding decision.

## Next — Parallel Plugin Execution Framework

- Define stable descriptor, validation, deterministic plan, work-unit, resource,
  assembly, and final-validation contracts in `mechana-api`.
- Implement `plan -> parallel work units -> assemble` locally without a general DAG.
- Introduce storage-neutral artifact references and a filesystem-backed provider.
- Introduce scratch request/reservation lifecycle contracts and cleanup.
- Add a minimal generated plugin template and compatibility tests.

## Artifact and worker resources

- Separate data-plane transfer from control-plane scheduling metadata.
- Advertise CPU, RAM, scratch, plugin capabilities, and runtime health.
- Match and reserve resources atomically; preserve lease-fenced publication.
- Add immutable content identity, bounded worker caching, and later locality-aware
  scheduling without depending on cache hits for correctness.

## Reference plugins

- Adapt FFmpeg media to the generic framework: H.264 input, H.265 output,
  keyframe-aligned plans, quality profiles, assembly, and FFprobe validation.
- Evolve OCR into document processing with layout, figures, tables, structured
  multi-artifact results, confidence, assembly, and validation.
- Use fractal and Blender workloads to prove infrastructure remains domain-agnostic.

## Worker operations and plugin runtime

- Provide a simple host control surface where operators set worker count, CPU,
  RAM, scratch, network, and allowed plugins with understandable defaults.
- Separate plugin runtimes from workers for crash, timeout, and resource isolation;
  reuse runtimes across work units where appropriate.
- Implement trusted, managed, and sandboxed modes. Verify platform-specific
  enforcement before advertising a guarantee.

## Plugin authoring ecosystem

- Ship SDK contracts, scaffolding, simulator, compatibility suite, certification,
  documentation generation, and packaging.
- Define vendor-neutral `plugin-definition.yaml` and `plugin-context.md` artifacts
  for human and AI-assisted authoring.
- Add specialized templates only after a demonstrated need.

## Production evolution

- Durable active state and recovery, authentication/authorization, signed plugin
  provenance, secure distribution, operator policy, and production observability.
- Client-side assembly may be added without changing artifact identity or lifecycle.
