# Plugin model

Last reviewed: 2026-08-04

## Accepted contract

A Mechana plugin encapsulates the complete computational contract for a class of
work. It describes **what must happen**; Mechana determines **where, when, and
under which attempt it happens**.

The contract includes:

1. Machine-readable descriptions of supported inputs and outputs.
2. A versioned schema for configurable processing options.
3. Authoritative validation of inputs, outputs, options, and their combinations.
4. Deterministic parallel planning/decomposition into independent work units plus
   explicit assembly requirements.
5. Execution of one work unit against artifact references and an invocation
   context supplied by Mechana.
6. Resource estimates for planning, work-unit execution, and assembly so Mechana
   can make generic placement and reservation decisions.
7. Assembly/reassembly of the complete, ordered, compatible work-unit result set.
8. Authoritative validation of the assembled final result before it is accepted.

The initial parallel execution model is deliberately constrained to:

`plan -> parallel work units -> assemble`

This is not a generic DAG engine. Plugins may choose domain-specific splitting and
joining strategies within that shape, but do not define arbitrary workflow graphs.

## Ownership boundary

Mechana owns IDs, persistence, scheduling, worker selection, scratch reservations,
artifact transfer and integrity, leases, retries, attempt fencing, cancellation
propagation, progress aggregation, invocation placement, cleanup, and retention.
Plugins provide domain descriptions, validation, decomposition, execution,
estimation, and assembly without taking ownership of those platform concerns.

The repository mirrors this boundary structurally: `mechana-api` contains the
public plugin contract, while concrete implementations are collected beneath
`plugins/`. Concrete plugins may depend on `mechana-api`; infrastructure must not
depend on a concrete plugin except where an explicit composition or demo layer
wires that plugin into a runnable application.

The complete stage ordering is normative in the
[plugin lifecycle specification](../docs/plugin-lifecycle.md). Third-party and
AI-assisted creation guidance lives in the [plugin author guide](plugin-author-guide.md).
The plugin execution environment, trust contract, and proposed runtime manifest
are canonical in the [sandbox architecture](sandbox.md).

## Invariants

- The scheduler matches declared capabilities and resource estimates, not plugin
  payload semantics.
- Plugin inputs and outputs cross core boundaries as versioned values and artifact
  references, not assumed shared paths.
- Planning does not execute the workload or move large artifact bytes.
- Plugin resource estimates inform placement but never select workers or reserve
  capacity directly.
- Retries cannot silently overwrite another attempt's authoritative output.
- Plugin versions and runtime signatures needed for compatibility are explicit.
- Cancellation, timeout, progress, and external-process failure have defined task
  outcomes.
- Plugins report stages and normalized work-unit lifecycle/progress through the
  generic `JobObserver`. Optional display details remain opaque to scheduling and
  dashboard aggregation.
- Plugins declare resource and runtime needs but neither grant themselves host
  access nor enforce their own sandbox policy.

The sleep plugin in the current branch is an implementation slice, not the full
accepted plan/partition/assemble contract. See [media plugin](media-plugin.md) for
the first domain-specific local implementation. That implementation keeps its
descriptor/validation, planner, executor, estimator, assembler, runtime probe, and
result validator modular, but does not yet implement a generic core plugin API.

The initial separate host adapts the existing `TaskPlugin` contract without
changing computation semantics. One NDJSON request selects a verified JAR and
entrypoint; events carry progress, artifact staging, completion, and failure.
Forced cancellation terminates the host process; cooperative protocol cancellation
and migration of the five concrete plugins remain pending.
