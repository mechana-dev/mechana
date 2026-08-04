# AI-assisted plugin authoring

Last reviewed: 2026-08-04

## Status

- **Accepted:** plugin-authoring artifacts are vendor-neutral, repository-owned,
  human-readable, and suitable for capable coding assistants.
- **Directional:** Mechana develops an integrated assistant for plugin creation,
  testing, explanation, refinement, and reuse.
- **Directional:** curated Mechana-specific knowledge accumulates through reviewed
  Git history and is included with every clone.
- **Proposed:** optional compact model adapters or fine-tuning recipes may be
  distributed when size, licensing, privacy, and security permit.
- **Deferred:** selection, bundling, training, and lifecycle of any base model.

## Vendor-neutral authoring contract

A domain expert should be able to describe desired inputs, outputs, processing
rules, partitioning, resources, permissions, assembly, and validation without
learning Mechana's distributed internals. Repository-owned artifacts such as
`plugin-definition.yaml` (or a future versioned `plugin-spec.yaml`) and
`plugin-context.md` carry that intent to humans or any capable assistant.

These artifacts must not require ChatGPT, Claude, Gemini, Copilot, a specific
local model, or hidden vendor prompts. Generated plugins follow the same review,
testing, packaging, permission, sandbox, compatibility, and certification rules
as human-authored plugins.

## Long-term Mechana assistant

The directional product goal is a specialized assistant that can:

- turn a domain description into a plugin specification and scaffold;
- generate validators, planners, work-unit executors, assemblers, tests, and docs;
- run local simulation and certification checks;
- explain failures and suggest bounded refinements;
- retrieve and adapt reviewed patterns from earlier plugins; and
- preserve clear authorship, review, and trust boundaries.

The assistant helps describe and implement computation. It does not silently take
ownership of scheduler, storage, lease, security, or placement policy.

## Git-backed Mechana knowledge

The repository is the durable, auditable knowledge layer. Every clone should
inherit the curated Mechana expertise available at that revision, including:

- platform architecture and plugin lifecycle context;
- plugin specifications, templates, and accepted examples;
- reusable planning, execution, assembly, validation, and error-handling patterns;
- prompt templates and model-neutral instructions;
- evaluation, compatibility, certification, and regression cases;
- known failures, troubleshooting guidance, and rejected unsafe approaches; and
- optional small adapters or recipes when appropriate.

Knowledge changes arrive through normal reviewable commits and pull requests.
Private requests, proprietary plugin code, credentials, and user artifacts are not
automatically contributed upstream. Any shared learning must be deliberately
sanitized and reviewed for privacy, licensing, security, provenance, and quality.

## What Git does not contain

Large foundation-model weights do not belong in the ordinary source repository.
They make cloning impractical and introduce independent licensing and distribution
concerns. Users may supply a hosted model, local runtime, or separately downloaded
open-weight model. Git stores the portable Mechana knowledge that makes a
replaceable model useful for this platform.

See [plugin author guide](plugin-author-guide.md), [plugin model](plugin-model.md),
and [project values](project.md).
