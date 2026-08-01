# Mechana project brain

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
- [Scheduler](scheduler.md): leases, capabilities, scratch reservations
- [Media plugin](media-plugin.md): FFmpeg partitioned-video design
- [Glossary](glossary.md): shared terminology

## Maintenance

Keep facts concise and link instead of copying. Separate current implementation
from accepted direction. Date meaningful updates and append material changes to
`../docs/PROJECT-NOTES.md`. Explicit user instructions and verified code override
stale brain text; correct the stale entry as part of the same work.
