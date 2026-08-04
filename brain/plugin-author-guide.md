# Plugin author guide

Last reviewed: 2026-08-04

## The author mental model

Describe the computation; Mechana handles distributed execution. A plugin author
should not need scheduler, transport, lease, persistence, or cluster knowledge.

Managed or sandboxed plugins use only the supplied logical workspace and
`TaskContext`, emit bounded progress, tolerate forced cancellation, and declare
every external executable. Ambient home files, inherited credentials, undeclared
host paths, and undeclared network access are incompatible. A richer manifest and
cooperative-cancellation protocol remain pending.

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

For sandboxed execution, authors must assume no home directory, arbitrary host
filesystem, unrestricted network, ambient credentials, undeclared executable, or
stable machine identity. They use only Mechana-provided artifact handles and
logical input/output/work/log locations and declare every resource and native
runtime need. The complete, status-classified author contract is canonical in the
[sandbox architecture](sandbox.md).

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

The long-term integrated Mechana assistant may create, test, explain, refine, and
reuse plugin patterns, but the authoring contract remains model-replaceable. The
repository—not a vendor's hidden context—is the durable source for curated
architecture knowledge, examples, prompts, evaluations, certification cases, and
reusable patterns inherited by every clone. Large base-model weights are not
stored in ordinary Git, and private user requests or code are never contributed
without deliberate sanitization and review.

See [AI-assisted plugin authoring](ai-plugin-authoring.md) for statuses and scope.

## Certification direction

Verification should cover computational and packaging compatibility. Sandbox
compliance is a distinct, platform-specific result tied to a package digest,
runtime signature, policy, OS, and test-suite version. Passing API compatibility
tests is not proof of OS enforcement or universal plugin safety. The canonical
[sandbox architecture](sandbox.md) defines the proposed adversarial checks and
future certification dimensions without duplicating them here.

See the normative [plugin lifecycle](../docs/plugin-lifecycle.md),
[plugin model](plugin-model.md), and [sandbox strategy](sandbox.md).
