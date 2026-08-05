# Worker host agent and desktop control

The desktop app controls a small HTTP agent installed on each worker host. Merely
entering an IP address does not create remote processes; the target must already
be running the agent and must share its token with the controller.

## Build

From the repository root with JDK 25 and Maven 3.9 or newer:

```shell
mvn verify
```

This produces:

- `worker-host-agent/target/mechana-worker-host-agent.jar`
- `worker-control-app/target/mechana-worker-control.jar`
- `mechana-worker/target/mechana-worker.jar`

## Configure a test host

Copy the host-agent JAR and worker JAR to the target. Create
`worker-host-agent.properties` beside them:

```properties
bind-address=0.0.0.0
port=8790
token=replace-with-a-long-random-secret
allow-unauthenticated=false
machine-name=test-host-name
coordinator=http://COORDINATOR_HOST:8787
java=/absolute/path/to/java
worker-jar=/absolute/path/to/mechana-worker.jar
working-directory=/absolute/path/to/mechana-agent-data
max-workers=12
capabilities=sleep,video-ffmpeg,fractal-render,ocr-tesseract,blender-render
sandbox-root=/private/tmp/mechana-sandbox
sandboxed-capabilities=fractal-render
stop-timeout-ms=10000
```

Use an explicit Java path. On Windows it will usually end in `bin\\java.exe`; use
normal Java-properties escaping (`C:\\Program Files\\Java\\...`). On macOS/Linux,
`/usr/bin/java` or the selected JDK's `bin/java` is typical. Open TCP port 8790 in
the host firewall only for trusted controller addresses.

Start the agent in PowerShell on Windows:

```powershell
java -jar .\mechana-worker-host-agent.jar .\worker-host-agent.properties
```

Start it on macOS/Linux:

```shell
java -jar ./mechana-worker-host-agent.jar ./worker-host-agent.properties
```

For a local-only test, keep `bind-address=127.0.0.1`; a blank token is then allowed.
The agent refuses a non-loopback bind with a blank token unless
`allow-unauthenticated=true` explicitly enables development mode. This mode gives
every client that can reach the port full worker start/stop authority. If
`machine-name` is omitted, the agent derives and normalizes the local hostname.

## Run the desktop app

```shell
java -jar worker-control-app/target/mechana-worker-control.jar
```

Enter the agent hostname/IP, port, and matching token. The app probes the selected
agent at startup, after host selection, and on **Refresh**. An authenticated response
is shown as **AGENT ONLINE** and its live worker records, counts, execution mode, and
plugins replace stale display values. **Start** and **Stop all** remain disabled until
that probe succeeds. An HTTP-responding agent that rejects the token is shown as
detected with controls locked; an endpoint that does not answer is unavailable.
Select a count, execution mode, and comma-separated plugin list, then press **Start**
to add the deficit up to that count.

On the MBA, choose **SANDBOXED** and `fractal-render`. The agent verifies that the
host is macOS, the requested plugin is listed in `sandboxed-capabilities`, and the
sandbox root is outside the user's home directory. It launches each child with the
configured `mechana.sandbox.root` system property. The status panel reports the
actual mode, plugins, and sandbox root used by the running group. Stop all workers
before changing mode or plugin selection.

Choose **LEGACY** only for plugins that have not yet migrated to the sandbox host.
The agent limits those selections to `capabilities`; this label intentionally does
not imply OS isolation. **Stop all** gracefully stops all tracked children and
forces remaining processes down after the configured timeout. Known hosts and the
last settings are stored at `~/.mechana/worker-control.properties` (under the user
profile on Windows).

## Provision a new host over SSH

The desktop app can deploy Mechana to a macOS or Linux account that already has:

- working OpenSSH access from the controller machine;
- Java 25 available in the non-interactive SSH `PATH`;
- a network route from the remote host to the configured coordinator URL; and
- permission to create the selected per-user install directory and sandbox root.

Build the artifacts locally, then launch the app:

```shell
mvn -pl mechana-worker,worker-host-agent,worker-control-app -am package
java -jar worker-control-app/target/mechana-worker-control.jar
```

Enter the agent host, port, desired worker count/mode/plugins, SSH user,
coordinator URL, local host-agent and worker JAR paths, remote directory, and
sandbox root. Leave **Identity** blank to use the normal SSH agent/config, or set
an explicit private-key path. Host-key verification is strict by default; select
**Accept new host key** only after independently verifying the target.

**Reinstall + start via SSH** performs this sequence:

1. connects with batch-mode `ssh` and detects `Darwin` or `Linux`;
2. discovers the remote home and Java executable;
3. uploads the host-agent JAR, worker JAR, and generated token-protected config;
4. installs and starts `dev.mechana.worker-host-agent` as a per-user launchd job
   on macOS or systemd user service on Linux;
5. waits for the authenticated agent API; and
6. starts the requested number of workers with the selected mode and plugins.

No root access is requested. Linux user services require a functioning user
systemd session; staying active after logout may require an administrator to
enable lingering for that account. The generated agent listens on port 8790 (or
the selected port) on all interfaces, protected by a generated or supplied bearer
token. Use a trusted LAN or encrypted overlay and restrict the port with the host
firewall.

**Stop remote agent via SSH** first asks the agent to stop its managed workers,
then unloads/disables the remote launchd or systemd user service. Uploaded files
and service definitions are retained for a later restart or upgrade. The action
still works through SSH when the agent HTTP endpoint is unavailable.

**Restart agent via SSH** first attempts a graceful worker stop through the agent
API, then reloads the existing launchd job or restarts the existing systemd user
service. The SSH path can recover an installed agent whose HTTP API is hung or
unreachable, and it does not upload files. Use **Reinstall + start via SSH** when
the installed artifacts or configuration may be damaged or stale; it overwrites
them, reloads the service, and starts the requested workers.

This is "from scratch" for Mechana files and service registration; it does not
install Java, configure SSH itself, change firewalls, enable Linux lingering, or
support Windows OpenSSH service installation yet.

## Security and operational limits

The first version uses bearer-token authentication over plain HTTP by default and
supports an explicit unauthenticated development mode. Restrict either mode to
a trusted LAN or encrypted overlay network, protect the settings files, use unique
random tokens per environment, and apply firewall rules. It does not provide TLS,
token rotation, OS keychain storage, roles, audit logging, or host identity proof.
It does not find or kill arbitrary Java processes or adopt children after an agent
restart. SSH provisioning installs a per-user launchd or systemd unit, but not a
Windows service or a system-wide/root service.
Sandbox mode is currently macOS-only and `fractal-render` is the only migrated
concrete plugin. The agent's implementation allowlist cannot be expanded through
configuration alone; a later plugin migration must update code and tests before
that plugin can be launched as sandboxed.
