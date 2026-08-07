# Client Job Launcher

The Client Job Launcher is the user-facing counterpart to Worker Control. Worker
Control manages hosts and worker processes; this Swing application discovers,
submits, monitors, aborts, and browses user jobs.

Run it after packaging:

```shell
java -jar client-job-launcher/target/mechana-client-job-launcher.jar
```

## Implemented first slice

- Connects to a configured server and remembers its URL.
- Shows only plugin capabilities advertised by connected workers.
- Renders submission fields, defaults, bounds, input pickers, output-provider
  information, and resource guidance from server-provided descriptors.
- Includes descriptors for sleep, FFmpeg video, fractal render, Tesseract OCR,
  and Blender render and submits through their existing server endpoints.
- Refreshes active and completed jobs, including progress, worker assignments,
  diagnostics, completion time, and provider-aware artifact references.
- Aborts active jobs and purges selected or all completed server-owned
  history/artifacts. Bulk purge requires confirmation and does not affect active
  jobs.
- Retains the last successful capability view during a disconnect and labels it
  stale rather than presenting it as fresh scheduling state.

The current server is still a loopback-oriented development server. File fields
therefore select paths readable by that server; the picker does not upload bytes.
Server-local remains the only implemented artifact provider.

The Blender form defaults to `samples/blender/mechana-camera-orbit-2s.blend`, a
packed lightweight geometry scene with a continuously orbiting camera. Frames
1–48 at 24 fps produce a two-second clip; the development defaults use 640×360,
32 Cycles samples, and eight distributed tasks.

## Direction

The descriptor belongs in the plugin package/manifest once the general plugin
manifest contract lands. The temporary server composition catalog is deliberately
transport-shaped and contains no computation logic. Future descriptor versions
can add directory and provider pickers, richer conditional validation, units,
resource estimates, and client-local/cloud authorization without adding
plugin-specific launcher classes.

Artifact rows use provider plus stable key rather than a filesystem path. Future
providers can supply appropriate actions and ownership-based purge behavior.
