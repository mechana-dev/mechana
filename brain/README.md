# Mechana™ project brain

This directory is the repository-local source of durable Mechana context. It is
loaded through the root `AGENTS.md` and does not affect other repositories.

## Map

- [Project](project.md): purpose, principles, licensing, and contributor posture
- [Current state](current-state.md): facts verified in the repository today
- [Architecture](architecture.md): system boundaries and execution shape
- [Decisions](decisions.md): accepted decisions and invariants
- [Roadmap](roadmap.md): milestone sequence, not implementation claims
- [Conventions](conventions.md): development and documentation practices
- [Plugin model](plugin-model.md): task-agnostic extension boundary
- [Artifacts](artifacts.md): artifact identity and movement
- [Storage](storage.md): providers, direct publication, assembly placement, security, locality, and caching
- [Scheduler](scheduler.md): leases, capabilities, scratch reservations
- [Worker management](worker-management.md): operator resource model, host agent, and controller
- [Plugin author guide](plugin-author-guide.md): third-party and AI-assisted authoring
- [AI plugin authoring](ai-plugin-authoring.md): vendor-neutral artifacts, integrated-assistant direction, and Git-backed knowledge
- [Sandbox](sandbox.md): canonical, status-classified trust, runtime, enforcement,
  manifest, filesystem, native-tool, author, and certification architecture
- [Plugin lifecycle](../docs/plugin-lifecycle.md): normative stage ordering and ownership
- [Media plugin](media-plugin.md): FFmpeg partitioned-video design
- [Fractal plugin](fractal-plugin.md): deterministic no-input rendering design
- [OCR plugin](ocr-plugin.md): PDF rasterization, distributed Tesseract, and Markdown assembly
- [Blender plugin](blender-plugin.md): distributed animation-frame rendering and movie assembly
- [Audio reverb plugin](audio-reverb-plugin.md): pure-Java partitioned convolution and WAV artifact workflow
- [Native echo engine](audio-echo-engine.md): reusable delay-loop DSP and behavioral device models
- [Native Leslie engine](audio-leslie-engine.md): real-time two-rotor moving-speaker model
- [Mechana Audio](mechana-audio.md): future product/repository boundary, public integration contract, packaging, and migration checkpoint
- [Glossary](glossary.md): shared terminology

## Maintenance

Keep facts concise and link instead of copying. Separate current implementation
from accepted direction. Date meaningful updates and append material changes to
`../docs/PROJECT-NOTES.md`. Explicit user instructions and verified code override
stale brain text; correct the stale entry as part of the same work.
