# Conventions

Last reviewed: 2026-08-01

## Development

- Accepted toolchain: Java 25 and Maven; primary environment: IntelliJ IDEA/macOS.
- Verify installed tools with `java --version` and `mvn --version`.
- Canonical repository check: `mvn verify`.
- Follow the existing Maven module boundaries and package naming under `dev.mechana`.
- Prefer small interfaces, immutable values, explicit state transitions, and tests
  for invariants and failure paths.
- Use `mvn spotless:apply` only to correct formatting; never use formatting to hide
  unrelated changes.

## Architecture and naming

- Core types use generic task, stage, partition, artifact, capability, lease, and
  resource vocabulary—not media-specific terms.
- Media/FFmpeg types belong in the media plugin.
- Transport DTOs adapt domain values and must not become the domain model.
- Do not represent durable artifact identity with a process-local filesystem path.

## Documentation

- Keep current implementation facts in `current-state.md`, decisions in
  `decisions.md`, and future sequencing in `roadmap.md`.
- Update only the relevant topic file and link to it; avoid narrative duplication.
- Append timestamped `YYYY-MM-DD HH:MM:SS TZ` entries to
  `../docs/PROJECT-NOTES.md` for material changes. Never rewrite historical notes.
- Do not store secrets, personal data, or transient debugging output in the brain.
