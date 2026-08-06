# Windows sandbox worker

Worker Control deploys Windows workers over an existing OpenSSH connection. The
target needs Windows 10/11 or a current Windows Server release, Java 25 in the SSH
`PATH`, and the native tools required by the selected plugins. No Maven, .NET SDK,
or source checkout is required on the worker.

Build the Java artifacts and the self-contained launcher on the development
machine, select the Windows launcher EXE in Worker Control, then use **Reinstall +
start via SSH**. Deployment copies everything beneath the selected per-user remote
directory, creates a private Java runtime there, and registers
`MechanaWorkerHostAgent` as a per-user Scheduled Task. It does not change Windows
Firewall. Use an SSH tunnel or create an appropriately source-restricted inbound
rule for the configured agent port if direct control is required.

The verified backend is `windows-appcontainer-job`. AppContainer denies access to
the user profile and network by default. ACLs grant read-only access to `input` and
the private runtime and modification access to `work`, `output`, and `logs`. The
Job Object enforces CPU rate, per-process memory, active-process count, and
kill-on-close lifecycle containment. The Java runtime manager enforces timeout and
cancellation. Scratch-byte quotas and log-size quotas are not yet enforced.

Hyperion validation used Windows ARM64 build 26200 and Java 25.0.4. Workspace
write, input read, home denial, input-write denial, and outbound-network denial
were exercised directly. A real distributed sandboxed sleep job then completed on
Hyperion on its first attempt while the worker remained connected.
