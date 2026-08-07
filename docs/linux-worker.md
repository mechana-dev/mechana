# Linux sandbox worker

The Linux worker uses the existing plugin host, `PluginRuntimeManager`, attempt
workspace, and trust modes. `SANDBOXED` selects the Linux Bubblewrap backend only
after a live namespace probe succeeds. It never falls back to `MANAGED`.

## Portable requirements

- a currently supported Linux kernel with unprivileged user, PID, IPC, UTS, and
  network namespaces enabled;
- Java 25 or newer;
- Bubblewrap (`bwrap`) available on `PATH`;
- the Mechana worker artifact and any native runtimes required by selected plugins.

Install Bubblewrap with the host's package manager:

```text
Debian / Ubuntu: apt install bubblewrap
Fedora / RHEL family: dnf install bubblewrap
Arch: pacman -S bubblewrap
openSUSE: zypper install bubblewrap
```

The worker itself does not call a package manager and does not require systemd.
It can run from a shell or any service manager. The optional Worker Control
provisioner currently generates systemd user services; that is deployment
convenience, not a Linux sandbox dependency.

## Validate a host

Run the shipped probe before enabling sandboxed workers:

```text
java -cp mechana-plugin-runtime.jar dev.mechana.runtime.plugin.LinuxSandboxProbe
```

The probe must end with `validation=passed`. The worker repeats the live backend
probe on startup and advertises the backend plus each enforced control alongside
its plugin capabilities.

## Enforced today

- plugin code and native tools run outside the worker JVM;
- separate user, PID, IPC, UTS, mount, and network namespaces;
- read-only `input/` and writable `work/`, `output/`, and `logs/`;
- no host home-directory mount and no writes outside the attempt workspace;
- isolated `/tmp`, minimal `/dev`, and a new `/proc`;
- wall-clock timeout and cancellation;
- Bubblewrap parent-death behavior plus runtime child-process termination;
- per-attempt cleanup and abandoned-attempt reclamation.

Read-only runtime trees such as `/usr`, `/lib`, `/etc`, and `/opt` remain visible
so Java and explicitly configured native tools can run. This is a restricted
runtime view, not a claim that only workspace bytes are readable.

## Not yet enforced

CPU quota, hard memory limits, scratch-byte quotas, process-count limits,
dedicated host identities, cgroup placement, seccomp syscall filtering, log-size
limits, and abrupt-parent descendant guarantees beyond Bubblewrap's direct
parent-death control are not yet claimed. Network allowlists are not implemented;
the supported policy is either a new network namespace or inherited networking.

## Worker launch

Set `-Dmechana.execution.mode=sandboxed` and choose a sandbox root outside any
sensitive tree. On Linux the default is `${java.io.tmpdir}/mechana-sandbox`.
The attempt layout is `input/`, `work/`, `output/`, and `logs/` beneath a unique
job/attempt directory. Native plugin executables remain explicit absolute
properties. See `docs/WORKER-CONTROL.md` for remote provisioning.
