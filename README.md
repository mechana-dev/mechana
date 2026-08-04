# Mechana™

Mechana is an open-source, plugin-driven distributed computation platform. A
plugin describes a computation; Mechana supplies the distributed execution
environment that places work, moves artifacts, reserves resources, schedules and
retries attempts, aggregates progress, propagates cancellation, and cleans up.

The first deliberately constrained execution model is:

`plan -> parallel work units -> assemble`

It is not a general DAG engine. The narrow shape keeps the platform understandable
while supporting media and document processing, rendering, simulations, and other
partitionable computations.

## Three audiences, three clear responsibilities

- **Worker operators** choose the CPU, memory, scratch, network, and plugin trust
  they contribute. Mechana states guarantees it actually enforces; it does not
  imply isolation that is only planned.
- **Plugin authors** define inputs, outputs, options, validation, planning,
  resource estimates, one-work-unit execution, assembly, and final validation.
  They do not implement scheduling, networking, retries, leases, persistence,
  artifact movement, worker selection, or cleanup.
- **Infrastructure contributors** implement distributed-systems concerns without
  learning PDF, OCR, FFmpeg, Blender, or another plugin domain.

Concrete plugins live under [`plugins/`](plugins/); the public contract remains in
[`mechana-api/`](mechana-api/). Current demonstrations include distributed sleep
work, FFmpeg video compression, Mandelbrot/Julia rendering, Tesseract document
processing, and Blender rendering. These prove useful slices but do not imply that
every accepted Architecture Baseline 1 contract is implemented.

## Architecture Baseline 1

The repository-local [project brain](brain/README.md) is the canonical design
memory. Start with the [architecture](brain/architecture.md),
[plugin model](brain/plugin-model.md), [plugin lifecycle](docs/plugin-lifecycle.md),
and [current state](brain/current-state.md). Third-party authors should read the
[plugin author guide](brain/plugin-author-guide.md); operators should read
[worker management](brain/worker-management.md) and the
[canonical sandbox architecture](brain/sandbox.md), including its Accepted,
Directional, Proposed, and Deferred classifications. The
[roadmap](brain/roadmap.md) distinguishes
accepted direction from shipped behavior.

Mechana treats vendor-neutral, human- and AI-readable plugin specifications,
templates, examples, and certification rules as first-class SDK artifacts. The
goal is for a domain expert to describe a computation and implement it directly or
use any capable coding assistant without learning Mechana internals.

Storage is also a first-class architecture boundary. Jobs may independently use
different input, intermediate, and output providers behind stable artifact
references. The coordinator remains primarily a control plane, and the
directional data plane allows workers to publish directly to requester-controlled
storage. Concurrent worker uploads can aggregate bandwidth instead of forcing all
bytes through one coordinator pipe, while the same assembly contract can run on a
client, coordinator, or worker. See the
[distributed-storage design](docs/architecture/distributed-storage.md) and
[storage brain](brain/storage.md).

Mechana's long-term AI authoring direction is vendor-neutral and Git-backed.
Curated context, examples, prompts, evaluations, and reusable plugin patterns
travel with every clone; large base-model weights do not. See
[AI-assisted plugin authoring](brain/ai-plugin-authoring.md).

See [`DEVELOPMENT.md`](DEVELOPMENT.md) for runnable examples.

## License and trademarks

The software in this repository is licensed under the
[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution and trademark
information.

Mechana and the Mechana logo are trademarks of Mark Vita. The Apache License 2.0
applies to the software and other licensed repository content; it does not grant
permission to use those trademarks except as required by applicable law. See the
[trademark and brand policy](assets/brand/TRADEMARK.md) for permitted uses.
