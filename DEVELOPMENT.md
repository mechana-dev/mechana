# Mechana development guide

## First distributed execution slice

The current implementation provides a continuously running HTTP server, any number of pull-based workers, a
parallel-job client, and a dynamically downloaded `sleep` plugin. Active scheduling state remains in memory; terminal
job dashboards and server-owned result artifacts are archived on disk.

Build everything from the repository root:

```shell
mvn verify
```

Start the server in terminal 1:

```shell
java -jar mechana-server/target/mechana-server.jar
```

The server prints its persistent master dashboard URL:

```text
http://127.0.0.1:8787/dashboard
```

The master dashboard shows connected and previously registered workers, their
advertised IP addresses and capabilities, server process/runtime metadata, and all
active jobs plus a separate durable completed-jobs section.
Selecting a completed job opens its detailed work-unit dashboard and downloadable
artifact list. Completed jobs survive server restarts. The **Purge** action removes
the archived snapshot, all server-owned artifacts for that job, and the dashboard row.
Worker presence history remains in memory and resets when the server restarts.
Connected workers show `IDLE` when unassigned or `WORKING` with a link to their
currently assigned job.
Active-job rows and detail pages provide an **Abort** action. Aborting fences all
outstanding leases, rejects late updates, records queued/running work units as
cancelled, and archives the job in the completed section.

Start one or more workers in separate terminals:

```shell
java -jar mechana-worker/target/mechana-worker.jar
```

Submit a job containing four five-second tasks:

```shell
java -jar mechana-client/target/mechana-client.jar http://localhost:8787 4 5000
```

For a client running on the server host, the client prints a loopback-only
dashboard URL such as `http://localhost:8787/dashboard/jobs/<job-id>`. The same
generic dashboard model is used by scheduler-managed sleep work and the local
FFmpeg video workflows. Remote dashboard access is intentionally unavailable
until authentication and transport security are implemented.

With four workers, the tasks run in parallel. With one worker, the same tasks run serially. Each assignment uses a
renewable lease. If a worker disappears, the server returns its task to the queue after five seconds and rejects any
late completion from the abandoned worker.

The server is authoritative for plugin code. A worker advertises the plugin IDs it accepts, downloads the exact
assigned plugin JAR into temporary storage, verifies its SHA-256 checksum, loads it for that execution, and deletes
the temporary artifact afterward.

Server arguments are `[port] [sleep-plugin-jar] [public-server-url] [data-directory]`.
The default data directory is `.mechana/server`; it is ignored by Git. When workers connect over a network, set the
public URL to an address they can reach:

```shell
java -jar mechana-server/target/mechana-server.jar 8787 \
  plugins/sleep-plugin/target/mechana-plugin-sleep-0.1.0-SNAPSHOT.jar https://server.example \
  /var/lib/mechana
```
