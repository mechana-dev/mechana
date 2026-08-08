# Roadmap

Last reviewed: 2026-08-08

This sequence expresses intent, not completion. Check
[current state](current-state.md) before reporting progress. Architecture Baseline
1 closes design stabilization; implementation should preserve it or record an
explicit superseding decision.

## Completed sandbox sequence

1. **PR A — macOS foundation:** runtime/policy/capability interfaces, workspace
   layout, managed lifecycle, experimental macOS adapter, host, and tests.
2. **PR B — plugin migration:** connect the worker, migrate sleep then fractal,
   OCR/Tesseract, FFmpeg, and Blender, and declare native tools explicitly.
3. **Platform backends:** Linux Bubblewrap and the Windows AppContainer/Job
   Object foundation are implemented. All five current plugin paths are verified
   on Hyperion for the recorded Windows runtime versions, and Worker Control can
   provision the current macOS, Linux, and Windows development fleet. Broader
   native-runtime profiles and stronger resource enforcement remain future work.

## Active next step — Parallel Plugin Execution Framework

- Define stable descriptor, validation, deterministic plan, work-unit, resource,
  assembly, and final-validation contracts in `mechana-api`.
- Implement `plan -> parallel work units -> assemble` locally without a general DAG.
- Introduce storage-neutral artifact references and a filesystem-backed provider.
- Model independent input, intermediate, and output provider selection while
  retaining a simple filesystem-backed first implementation.
- Introduce scratch request/reservation lifecycle contracts and cleanup.
- Add a minimal generated plugin template and compatibility tests.

## Artifact and worker resources

- Separate data-plane transfer from control-plane scheduling metadata.
- Advertise CPU, RAM, scratch, plugin capabilities, and runtime health.
- Match and reserve resources atomically; preserve lease-fenced publication.
- Add immutable content identity, bounded worker caching, and later locality-aware
  scheduling without depending on cache hits for correctness.
- Add authorized, atomic, lease-fenced direct worker publication so bulk results
  need not traverse coordinator storage and parallel uploads can aggregate worker
  bandwidth.
- Generalize assembly placement only after the same artifact API is proven
  coordinator-side; later placement may be client-side or worker-side.
- Add authenticated TLS and provider security policy. Design optional end-to-end
  artifact encryption separately; key management remains deferred.

## Reference plugins

- Adapt FFmpeg media to the generic framework: H.264 input, H.265 output,
  keyframe-aligned plans, quality profiles, assembly, and FFprobe validation.
- Evolve OCR into document processing with layout, figures, tables, structured
  multi-artifact results, confidence, assembly, and validation.
- Use fractal and Blender workloads to prove infrastructure remains domain-agnostic.

## Worker operations and plugin runtime

- Provide a simple host control surface where operators set worker count, CPU,
  RAM, scratch, network, and allowed plugins with understandable defaults.
- Implement the accepted runtime-manager boundary and trusted, managed, and
  sandboxed modes from the [canonical sandbox architecture](sandbox.md).
- Validate the proposed manifest schema and implement per-platform guarantee
  matrices, adversarial compliance tests, resource enforcement, native-tool
  containment, attempt isolation, runtime reuse hygiene, and truthful diagnostics.
- Do not advertise sandboxed execution until the named OS controls are enforced
  and verified; certification services and marketplace trust remain deferred.

## Plugin authoring ecosystem

- Ship SDK contracts, scaffolding, simulator, compatibility suite, certification,
  documentation generation, and packaging.
- Define vendor-neutral `plugin-definition.yaml` and `plugin-context.md` artifacts
  for human and AI-assisted authoring.
- Curate architecture context, examples, prompts, evaluations, known failures,
  and reusable plugin patterns in Git so every clone receives the reviewed
  Mechana knowledge base without bundling large base-model weights.
- Evolve toward an integrated, model-replaceable Mechana assistant for plugin
  creation, testing, failure explanation, refinement, and reuse.
- Add specialized templates only after a demonstrated need.

## Production evolution

- Durable active state and recovery, authentication/authorization, signed plugin
  provenance, secure distribution, operator policy, and production observability.
- Client-side assembly may be added without changing artifact identity or lifecycle.
