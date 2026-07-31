# Mechana development guide

## First distributed execution slice

The current implementation provides a continuously running HTTP server, any number of pull-based workers, a
parallel-job client, and a dynamically downloaded `sleep` plugin. The server keeps job state in memory for this
iteration.

Build everything from the repository root:

```shell
mvn verify
```

Start the server in terminal 1:

```shell
java -jar mechana-server/target/mechana-server.jar
```

Start one or more workers in separate terminals:

```shell
java -jar mechana-worker/target/mechana-worker.jar
```

Submit a job containing four five-second tasks:

```shell
java -jar mechana-client/target/mechana-client.jar http://localhost:8080 4 5000
```

With four workers, the tasks run in parallel. With one worker, the same tasks run serially. Each assignment uses a
renewable lease. If a worker disappears, the server returns its task to the queue after five seconds and rejects any
late completion from the abandoned worker.

The server is authoritative for plugin code. A worker advertises the plugin IDs it accepts, downloads the exact
assigned plugin JAR into temporary storage, verifies its SHA-256 checksum, loads it for that execution, and deletes
the temporary artifact afterward.

Server arguments are `[port] [sleep-plugin-jar] [public-server-url]`. When workers connect over a network, set the
public URL to an address they can reach:

```shell
java -jar mechana-server/target/mechana-server.jar 8080 \
  mechana-plugin-sleep/target/mechana-plugin-sleep-0.1.0-SNAPSHOT.jar https://server.example
```
