# macOS plugin sandbox development

Status: experimental foundation  
Verified host: macOS 26.5.2 (25F84), Apple Silicon MacBook Air

The `mechana-plugin-runtime` module provides common policy, workspace,
capability, launch, timeout, cancellation, and result contracts. `plugin-host`
provides a separate JVM executable with a one-request newline-delimited JSON
protocol. It loads one `TaskPlugin`, verifies its identity, emits progress,
artifact, completion, or failure events, and exits.

## Mechanism and limitations

The experimental macOS backend uses `/usr/bin/sandbox-exec` when a live probe
succeeds. Apple's installed manual marks this tool **deprecated** and recommends
App Sandbox. App Sandbox is designed around signed, entitled application bundles;
it is not a direct dynamic policy mechanism for arbitrary downloaded plugin JARs.
This backend may disappear in a future macOS release.

Use an operator-controlled temporary root such as
`/private/tmp/mechana-sandbox`. Attempts have this fixed layout:

```text
<root>/<job>/<attempt>/
  input/   staged, sandbox read-only
  work/    private read/write temporary data
  output/  read/write publication staging
  logs/    platform-owned stdout/stderr capture
```

The generated profile denies by default, grants required system/runtime reads,
grants only the workspace access above, and grants network only when policy allows
it. `HOME` and `TMPDIR` point to `work/`. A dedicated low-privilege macOS account
is not yet configured or enforced.

Run outside an already sandboxed parent process:

```shell
mvn -pl mechana-plugin-runtime -am verify
```

Tests perform real allowed workspace I/O, denied input writes, denied outside
reads/writes, and denied network access. They skip when the live probe fails.
Codex containment on the MBA currently rejects nested profiles with `Operation
not permitted`; that means unavailable, not passed.

## Exact current guarantee matrix

| Control | Managed | macOS experimental |
| --- | --- | --- |
| Separate process; ordinary crash does not stop worker | yes | yes |
| Wall-clock timeout and direct-child termination | yes | yes |
| Stdout/stderr capture | yes, size cap pending | yes, size cap pending |
| Workspace filesystem restriction | no | only after live probe/test |
| Network denial | no | only after live probe/test |
| Whole descendant-tree termination | best effort, not claimed | best effort, not claimed |
| CPU, memory, scratch-size, process-count limits | no | no |
| Dedicated low-privilege identity | no | no |

Sandboxed requests fail closed when required filesystem or network controls are
unavailable. There is no automatic downgrade.

## Planned backends

- Linux: namespaces, cgroup v2, seccomp, restricted mounts, and a dedicated
  identity through the same launcher interface.
- Windows: Job Objects plus a restricted token or AppContainer and explicit ACL/
  network policy.

Neither backend is implemented or advertised.
