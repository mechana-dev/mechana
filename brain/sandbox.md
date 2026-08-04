# Sandbox and plugin runtime architecture

Last reviewed: 2026-08-04

This is the canonical sandbox architecture for Architecture Baseline 1. It
describes the contract Mechana intends to preserve; it does not claim that
production isolation is implemented. Current evidence remains in
[current state](current-state.md).

## Status vocabulary

- **Accepted:** an Architecture Baseline 1 constraint that later work must
  preserve or explicitly supersede.
- **Directional:** the intended design, with implementation details still open.
- **Proposed:** a concrete candidate contract requiring validation before adoption.
- **Deferred:** deliberately outside the baseline or next implementation slice.

## Security principle — Accepted

> Mechana only claims security guarantees that are actually enforced by the
> operating system and verified on the platform where the plugin runs.

A process boundary, configuration flag, manifest declaration, package signature,
or passing compatibility test is not itself a sandbox. Operator-facing capability
data must name the controls that are active. Unknown, unavailable, failed, or
unverified controls are reported as absent; Mechana never silently downgrades a
requested sandboxed execution to a weaker mode.

The sandbox limits impact; it does not establish that plugin code is correct,
benign, confidential, or free of side channels.

## Trust levels — Accepted

| Level | Intended use | Guarantees |
| --- | --- | --- |
| **Trusted** | Operator-approved first-party or audited code where simplicity or performance justifies in-process execution. | No hostile-code isolation. Plugin failure, memory use, or native calls may affect the worker. Ordinary lifecycle, lease, and artifact checks still apply. |
| **Managed** | Code needing a separate lifecycle and fault boundary, including external tools, but not treated as hostile. | Separate process, explicit launch environment, cancellation/timeout handling, output capture, and worker survival after most plugin crashes. OS resource controls are used where available, but process separation alone is not a security sandbox. |
| **Sandboxed** | Untrusted or least-trusted third-party computation when the host can enforce the requested policy. | Separate process plus verified OS-enforced filesystem, network, CPU, memory, scratch, process, executable, and lifecycle restrictions represented by that platform's guarantee matrix. |

Trust is operator policy, not a property a plugin awards itself. A plugin declares
needs; the operator permits a maximum; the worker advertises enforceable features;
Mechana schedules only where all three are compatible.

## Runtime architecture — Accepted

The direct relationship:

`worker -> plugin`

becomes:

`worker -> plugin runtime manager -> sandbox runtime -> plugin/native tools`

The **plugin runtime manager** is the worker-side control boundary. It resolves
policy, selects a compatible runtime implementation, creates the attempt contract,
launches or reuses a runtime, monitors it, delivers cancellation, collects bounded
diagnostics, and guarantees cleanup. It does not trust the plugin to enforce its
own limits.

The **sandbox runtime** is the platform-specific enforcement adapter. In trusted
mode it may be a deliberately minimal in-process adapter; in managed mode it owns
the child process; in sandboxed mode it must apply and verify OS controls before
plugin code starts.

A plugin package is installed material. A plugin runtime is an execution process
that may be reused across compatible work units to amortize startup. A work-unit
attempt remains a distinct authority boundary with its own lease, workspace,
resource accounting, staged inputs, output publication rights, timeout,
cancellation, and cleanup. Runtime reuse must not leak files, environment,
credentials, mutable state, or artifact authority between attempts. A crash,
hang, leak, or forced termination must not require restarting the worker.

## Cross-platform enforcement — Directional

Mechana promises a common policy and launch API, not identical operating-system
implementations or identical guarantees.

- **Linux:** likely namespaces, cgroups, seccomp, dedicated identities, restricted
  mounts, capabilities, and an appropriate mandatory-access-control layer.
- **Windows:** likely Job Objects, restricted tokens or AppContainer where
  suitable, ACLs, and Windows network policy.
- **macOS:** the strongest maintainable combination of process identity,
  filesystem permissions, resource controls, network policy, and virtualization
  when host controls cannot provide the required guarantee.

Each implementation has a versioned guarantee matrix and adversarial verification
suite. A job requiring an unavailable control is rejected or scheduled elsewhere.
Equivalent API shape never implies identical resistance to escape, denial of
service, side channels, or platform vulnerabilities.

## Plugin manifest resource declarations — Proposed

The runtime manifest is distinct from the authoring-only
`plugin-definition.yaml` unless a later decision deliberately unifies them. A
versioned manifest should declare needs rather than implementation policy. The
operator and Mechana may grant less only when the plugin explicitly supports that
profile; they never grant more implicitly.

```yaml
sandbox:
  minimumTrust: sandboxed
  network:
    mode: none                 # none, outbound, allowlist
    destinations: []
  filesystem:
    input: read-only
    output: write-only
    work: read-write
    logs: append-only
  resources:
    cpu: 2
    memoryMiB: 2048
    scratchMiB: 4096
    timeoutSeconds: 900
    maxProcesses: 4
  runtimes:
    - name: ffmpeg
      version: ">=7 <9"
  gpu:
    required: false
    api: cuda
    memoryMiB: 0
```

Candidate fields include package identity and manifest version; minimum trust;
network mode and destinations; logical filesystem grants; CPU, RAM, scratch,
timeout, process/thread and file-descriptor limits; required native runtimes and
version constraints; GPU type/API/memory; environment-variable allowlists; and
whether compatible runtimes may be reused. Secrets are referenced through
platform-owned handles, never embedded in a manifest.

The schema, units, version matching, GPU isolation model, secret delivery, and
portable network-destination syntax remain **Proposed**.

## Filesystem model — Accepted

Plugins operate only on logical locations supplied for the attempt:

- **workspace:** the attempt root and only path namespace visible to the plugin;
- **input:** verified, immutable staged artifacts, mounted read-only;
- **output:** the publication staging area for declared results;
- **work:** private, mutable temporary files within the reserved scratch budget;
- **logs:** bounded diagnostics written through an append-only or platform-owned
  channel.

`workspace` contains or names the other locations; it is not an extra grant to a
host directory. Plugins assume no home directory, current-directory stability,
shared host path, device access, executable search path, system temporary
directory, user profile, registry access, or arbitrary filesystem visibility.
They do not construct paths outside the supplied logical locations. Cache, if
introduced, is platform-owned, separately bounded, and exposed only through an
explicit API—not as a hidden persistent directory.

Exact mount layout and permissions per operating system are **Directional**. A
general arbitrary-host-path permission is **Deferred** and would require a new
security decision.

## Resource and network enforcement — Accepted

Mechana resolves declarations and operator policy into an attempt budget before
launch. The runtime enforces and measures that budget independently of plugin
cooperation:

- **CPU:** schedulable capacity plus OS quota/affinity controls where supported;
- **RAM:** a hard or strongest available memory limit with an explicit
  out-of-memory outcome;
- **scratch:** reserved capacity, quota or bounded volume, measured through
  cleanup; caches are accounted separately;
- **timeout:** wall-clock deadline with cooperative cancellation, grace period,
  process-tree termination, and a terminal timeout result;
- **processes:** child-process tree and count limits; descendants cannot outlive
  the attempt;
- **network:** denied by default in sandboxed mode, with explicit outbound or
  destination allowlists only when declared and approved.

The runtime also bounds logs and environment, closes inherited handles, and
captures the actual applied policy in attempt diagnostics. Exact limit semantics,
portable network filtering, and behavior when an OS offers only soft controls are
**Directional**; Mechana must not label a soft or advisory limit as a hard one.

## Native runtime isolation — Accepted

FFmpeg, FFprobe, Tesseract, Python, Rust-produced binaries, Blender, CUDA tools,
and future native dependencies belong behind the runtime boundary. They are part
of the plugin's execution environment, not trusted extensions of the worker.

Native tools can spawn descendants, load libraries, inspect environment, consume
unbounded resources, parse hostile inputs, or crash outside the language runtime.
Keeping them behind the same sandbox gives the entire process tree one filesystem,
network, resource, timeout, cancellation, and cleanup policy. Runtime identity,
version, executable digest or provenance, and relevant accelerator/driver profile
form part of the advertised runtime signature.

Bundling, downloading, licensing, vulnerability response, signing, and patch
distribution for native runtimes are **Deferred**. Package signatures may prove
origin and integrity, but never replace isolation or operator consent.

## Plugin author contract — Accepted

Under sandboxed execution, authors:

- use only supplied artifact handles and logical input/output/work/log locations;
- declare all runtime, network, filesystem, CPU, memory, scratch, process, timeout,
  and GPU needs and tolerate rejection when policy cannot satisfy them;
- assume no home directory, arbitrary host access, unrestricted network, ambient
  credentials, interactive desktop, stable machine identity, or undeclared tool;
- make planning and work-unit execution deterministic for declared inputs and
  runtime profiles, and never coordinate through hidden host state;
- respond to cancellation, bound progress/log output, clean up cooperatively, and
  let Mechana perform authoritative forced cleanup;
- publish only declared outputs through the attempt context and never treat a
  local write as authoritative completion;
- treat secrets as explicit short-lived platform handles and avoid logging them;
- test in the strictest intended trust mode. Trusted-mode success is not evidence
  of sandbox compatibility.

## Compliance testing and certification — Directional

The Plugin SDK should provide a sandbox-compliance harness that attempts both
valid operations and prohibited behavior. Coverage should include manifest/schema
validation, allowed-path use, path traversal and symlink escape, network denial,
CPU/RAM/scratch/process/time limits, descendant cleanup, cancellation, log bounds,
stale-attempt publication, runtime identity, state leakage during reuse, and
equivalent lifecycle behavior across supported platforms.

Future certification may publish separate results for:

1. API and package compatibility;
2. lifecycle, cancellation, and cleanup compliance;
3. resource-declaration and budget compliance;
4. sandbox-policy compliance on a named OS/runtime profile;
5. provenance or organizational review.

Certification is versioned evidence for a specific plugin, package digest,
runtime signature, policy profile, operating system, and test-suite version. It
is not a universal safety claim and expires when relevant inputs change.
Certification services, trust stores, official badges, revocation, and a public
plugin marketplace are **Deferred**.

## Baseline boundaries

- **Accepted:** trust vocabulary; runtime-manager boundary; honest OS-enforced
  claims; logical filesystem restrictions; platform-owned resource, lifecycle,
  native-tool, artifact, and cleanup authority; author expectations.
- **Directional:** OS adapters and guarantee matrices; exact enforcement and
  diagnostics; compliance harness and multi-dimensional certification.
- **Proposed:** the concrete runtime manifest fields and serialization.
- **Deferred:** production sandbox implementation, general host-path access,
  runtime distribution/signing, certification infrastructure, and marketplace.

The first implementation foundation defines common contracts, managed child
process lifecycle, a fixed attempt workspace, fail-closed capability selection,
and a one-request plugin host. The macOS backend is experimental because
`sandbox-exec` is explicitly deprecated by Apple. It claims filesystem and
network controls only after a live probe; CPU, memory, scratch-size, process-count,
dedicated identity, bounded-log-size, and guaranteed descendant-tree controls
remain unimplemented. See the [macOS guide](../docs/macos-sandbox.md).
