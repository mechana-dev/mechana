# Sandbox and plugin runtime

Last reviewed: 2026-08-04

The goal is eventually to run third-party computations with explicit, verifiable
operator policy. This document is architecture, not a claim that production
isolation is currently implemented.

## Runtime shape

`worker -> plugin runtime manager -> sandbox launcher -> plugin runtime -> plugin`

A package is installed once. A runtime may remain alive for multiple work units to
amortize startup. Each work-unit attempt still has a distinct lease, workspace,
resource accounting, artifact authority, cancellation, and cleanup boundary.
Crashes, hangs, leaks, and forced termination of a managed runtime must not require
restarting the worker.

## Trust levels

- **Trusted:** code approved by the operator may execute in-process for simplicity
  or performance. This provides no untrusted-code isolation.
- **Managed:** a separate process provides crash, timeout, lifecycle, and some
  resource isolation. Process separation alone is not a security sandbox.
- **Sandboxed:** a separate process runs under verified OS-enforced filesystem,
  network, CPU, memory, process, and workspace policy. Only enforced capabilities
  may be advertised to operators.

Network is denied by default for sandboxed computation unless the plugin declares
a need and operator policy grants it. Native tools such as FFmpeg or Tesseract are
exposed deliberately, with identity/version included in runtime capability data.

## Platform strategy

- **Linux:** namespaces, cgroups, seccomp, restricted mounts/users, and an
  appropriate mandatory-access-control layer.
- **Windows:** Job Objects, restricted tokens/AppContainer where suitable, ACLs,
  and operating-system network policy.
- **macOS:** the strongest maintainable combination of process identity,
  filesystem permissions, resource controls, network policy, and—if necessary—
  virtualization. Equivalent API shape does not imply identical guarantees.

Each implementation requires adversarial verification. The operator UI and
capability advertisement must present a per-platform guarantee matrix rather than
the word "sandboxed" without details.

## Policy and accounting

Launch policy includes plugin/package identity, allowed executables, CPU, RAM,
scratch, cache, filesystem mounts, network, child-process limits, timeout, and
environment. Secrets and unrestricted host paths are absent by default. Scratch
is attempt-scoped and cleaned by Mechana; caches are bounded separately.

Signed official or organizational packages and allowlists are future provenance
controls. Signatures establish origin/integrity, not safety, and do not replace
sandboxing or operator consent.
