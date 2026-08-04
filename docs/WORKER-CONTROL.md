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

Enter the agent hostname/IP, port, and matching token. **Refresh** reports only
workers launched by this agent. Select a count and press **Start** to add the
deficit up to that count. **Stop all** gracefully stops all tracked children and
forces remaining processes down after the configured timeout. Known hosts and the
last settings are stored at `~/.mechana/worker-control.properties` (under the user
profile on Windows).

## Security and operational limits

The first version uses bearer-token authentication over plain HTTP by default and
supports an explicit unauthenticated development mode. Restrict either mode to
a trusted LAN or encrypted overlay network, protect the settings files, use unique
random tokens per environment, and apply firewall rules. It does not provide TLS,
token rotation, OS keychain storage, roles, audit logging, or host identity proof.
It does not find or kill arbitrary Java processes, adopt children after an agent
restart, or install itself as a Windows service, launchd job, or systemd unit.
