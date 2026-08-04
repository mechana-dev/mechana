# Plugin author guide

Last reviewed: 2026-08-04

## The author mental model

Describe the computation; Mechana handles distributed execution. A plugin author
should not need scheduler, transport, lease, persistence, or cluster knowledge.

Authors implement or declare:

- supported inputs, outputs, and versioned processing options;
- authoritative validation;
- deterministic planning into independent work units;
- resource estimates for planning, work units, and assembly;
- execution of one work unit in the workspace Mechana supplies;
- ordered assembly and authoritative final validation.

Authors do not create worker pools, select machines, transfer artifacts, allocate
scratch, implement retries/leases, aggregate global progress, propagate
cancellation between machines, or own retention and cleanup.

## Authoring surface

The planned Plugin SDK comprises:

1. Small lifecycle contracts and immutable values.
2. A minimal template that builds before domain TODOs are filled in.
3. `plugin-definition.yaml`, a vendor-neutral design contract describing the
   computation, artifacts, partitioning, assembly, runtimes, resources, options,
   permissions, and validation.
4. `plugin-context.md`, concise authoritative context for human authors and any
   coding assistant, including responsibilities and prohibited infrastructure work.
5. Generated tests, local simulation, documentation, packaging, and compatibility
   or certification checks.

These artifacts are not runtime manifests unless a future explicit decision makes
them so. They must remain understandable without a particular AI vendor, hidden
prompt, or access to the infrastructure source tree.

## AI-assisted development

A domain expert should be able to describe a computation in domain language, fill
or review the plugin definition, and ask ChatGPT, Claude, Gemini, Copilot, or a
future assistant to implement the template using the same repository-owned
context. Generated code receives no special trust: it follows normal review,
testing, packaging, permission declaration, and certification.

## Certification direction

Verification should cover descriptor/schema completeness, deterministic planning,
artifact identity, cancellation and timeout responsiveness, bounded progress,
scratch compliance, attempt-safe publication, assembly correctness, cleanup,
documentation, API compatibility, and declared sandbox/runtime needs. Passing
compatibility tests is not itself proof that OS sandbox enforcement exists.

See the normative [plugin lifecycle](../docs/plugin-lifecycle.md),
[plugin model](plugin-model.md), and [sandbox strategy](sandbox.md).
