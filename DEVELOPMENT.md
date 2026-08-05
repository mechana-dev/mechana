# Mechana™ development guide

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
Connected workers show `IDLE` when unassigned or the active plugin ID and progress
with a link to their currently assigned job.
The master page also provides a confirmed, loopback-only **Restart server** action.
It launches the same server JAR with the current port, plugin, public URL, and data
directory. Workers reconnect automatically and durable completed history remains;
active in-memory jobs do not survive the restart.
Active-job rows and detail pages provide an **Abort** action. Aborting fences all
outstanding leases, rejects late updates, records queued/running work units as
cancelled, and archives the job in the completed section.
Active jobs can also be paused. Pause stops new assignments, fences running
leases, preserves completed work units, and freezes the job elapsed timer. Resume
uses the same job ID and queues only unfinished work units from the beginning.
Cancelled or failed sleep jobs provide **Resume as new**: the original terminal
record remains immutable, the new job links back to it, completed work units are
reused, and only incomplete units are queued. Mid-work-unit checkpoints are not
supported, so interrupted sleep tasks restart from zero.

Start one or more workers in separate terminals:

```shell
java -jar mechana-worker/target/mechana-worker.jar
```

Pass a comma-separated capability list and worker ID to accept multiple job types:

```shell
java -jar mechana-worker/target/mechana-worker.jar http://localhost:8787 \
  sleep,video-ffmpeg,fractal-render,ocr-tesseract worker-1
```

On macOS, `fractal-render` now runs in the separate plugin host through the
experimental sandbox backend. The default attempt root is
`/private/tmp/mechana-sandbox`; override it only with another location outside
your home directory:

```shell
java -Dmechana.sandbox.root=/private/tmp/mechana-sandbox-test \
  -jar mechana-worker/target/mechana-worker.jar http://localhost:8787 \
  fractal-render sandbox-worker-1
```

Start that command in four terminals with unique worker IDs, then submit a job
with four tasks. Tahoe currently enforces denial of plugin reads beneath the
user home directory, denial of network access, and writes limited to the
attempt's `work/`, `output/`, and `logs/` directories. It does not provide
workspace-only reads of system/runtime files or hard CPU, memory, scratch, or
process-count limits. `sandbox-exec` is deprecated by Apple, so this backend is
an explicitly experimental development foundation rather than a production
security boundary.

Each sandbox attempt records its worker, process, job, attempt, and creation time
and holds an operating-system lock for its lifetime. Normal completion, handled
failure, timeout, cancellation, and graceful worker shutdown delete the complete
attempt tree. If the worker JVM crashes or is forcibly killed, the OS releases
the ownership lock; the next worker started against the same sandbox root safely
reclaims marked, unlocked attempts. Unmarked directories and attempts still
locked by another worker are never removed by this startup pass.

Submit a job containing four five-second tasks:

```shell
java -jar mechana-client/target/mechana-client.jar http://localhost:8787 4 5000
```

The third client argument may instead contain one duration per task. This submits
four tasks lasting two, three, three-and-a-half, and four minutes:

```shell
java -jar mechana-client/target/mechana-client.jar http://localhost:8787 4 120000,180000,210000,240000
```

Submit an eight-segment distributed video job from the server host:

```shell
curl -H 'Content-Type: application/json' -d '{
  "sourcePath":"/absolute/path/input.mp4",
  "durationSeconds":60,
  "segmentCount":8,
  "targetSizeRatio":0.65
}' http://127.0.0.1:8787/api/jobs/video
```

Video submission is loopback-only because it names a server-local source path.
The server creates the requested leading clip, probes and keyframe-plans it,
stream-copies each planned range into a small per-task input chunk, and serves
only that chunk plus the exact video plugin JAR to each compatible worker. It accepts
lease-fenced segment uploads, assembles and validates a smaller HEVC MKV, and
publishes it as a durable completed-job artifact. Content-addressed caching remains
a follow-up optimization, but remote work no longer downloads the whole clip for
every segment.

Submit a no-input fractal collection job from the server host:

```shell
java -cp mechana-client/target/mechana-client.jar dev.mechana.client.FractalClientMain \
  http://localhost:8787 24 0 1920 1080 4000 1
```

The arguments after the server URL are image count, task count, width, height,
maximum iterations, and deterministic seed. Task count `0` automatically chooses
up to two batches per currently connected `fractal-render` worker; an explicit
positive value fixes the number of batches. Each worker renders its assigned PNGs
without an input artifact and uploads one lease-fenced batch. Successful assembly
publishes every PNG, `manifest.json`, `contact-sheet.png`, and
`fractal-collection.zip` as durable job artifacts.

For OCR workers, install Tesseract 5 and verify the requested language before
advertising `ocr-tesseract`:

```shell
tesseract --version
tesseract --list-langs
```

Submit a PDF OCR job from the server host:

```shell
java -cp mechana-client/target/mechana-client.jar dev.mechana.client.OcrClientMain \
  http://localhost:8787 /absolute/path/book.pdf 12 300 eng "Book title" 1 40
```

The server renders grayscale PNG pages and divides them across the requested task
count. Workers download only assigned pages, invoke Tesseract, and upload page-text
batches. Assembly publishes `document.md`, Unicode `document.tex`, and every raw
page text file. The TeX source uses `fontspec` and can be compiled separately with
XeLaTeX; it includes a TeXShop directive that selects that engine automatically.
A TeX installation is not required by the server or workers. Task count
`0` automatically chooses up to two batches per connected OCR worker. The final two
arguments select the first PDF page and number of pages; page count `0` means the
rest of the document.

For a client running on the server host, the client prints a loopback-only
dashboard URL such as `http://localhost:8787/dashboard/jobs/<job-id>`. The same
generic dashboard model is used by scheduler-managed sleep work and the local
FFmpeg video workflows. Remote dashboard access is intentionally unavailable
until authentication and transport security are implemented.

With four workers, the tasks run in parallel. With one worker, the same tasks run serially. Each assignment uses a
renewable lease. A dedicated task heartbeat renews that lease independently of plugin progress, including while the
worker downloads inputs, starts an external process, or uploads artifacts. If lease renewal stops, the server returns
the task to the queue after five seconds and rejects any late completion from the abandoned attempt.

Fleet presence is tracked separately: workers send a lightweight heartbeat every three seconds whether idle or busy,
and the dashboard marks a worker disconnected only after fifteen seconds without contact. Task progress is not used
as the worker-liveness signal.

The server is authoritative for plugin code. A worker advertises the plugin IDs it accepts, downloads the exact
assigned plugin JAR into temporary storage, verifies its SHA-256 checksum, loads it for that execution, and deletes
the temporary artifact afterward.

Server arguments are `[port] [sleep-plugin-jar] [public-server-url] [data-directory] [video-plugin-jar] [fractal-plugin-jar] [ocr-plugin-jar]`.
The default data directory is `.mechana/server`; it is ignored by Git. When workers connect over a network, set the
public URL to an address they can reach:

```shell
java -jar mechana-server/target/mechana-server.jar 8787 \
  plugins/sleep-plugin/target/mechana-plugin-sleep-0.1.0-SNAPSHOT.jar https://server.example \
  /var/lib/mechana
```
