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
- Presents every capability with the same `Tasks (0 = fleet)` control. Zero
  creates one task per currently compatible worker, capped by finite work such as
  pages, images, or frames; a positive value requests that explicit task count.
- Descriptor forms scroll independently when their fields exceed the available
  submission-panel height. Blender places Tasks directly below its source picker.
- File fields honor descriptor-provided accepted extensions in both the chooser
  and pre-submit validation. OCR accepts `.pdf` and Blender accepts `.blend`;
  server validation remains authoritative if another client bypasses the launcher.
- Capability names do not include transient worker counts. Availability still
  comes from the live schedulable fleet and becomes stale on disconnect.
- Refreshes active and completed jobs, including progress, worker assignments,
  diagnostics, completion time, and provider-aware artifact references.
- Aborts active jobs and purges selected or all completed server-owned
  history/artifacts. Bulk purge requires confirmation and does not affect active
  jobs.
- Retains the last successful capability view during a disconnect and labels it
  stale rather than presenting it as fresh scheduling state.

`server-local` remains the default. FFmpeg, Fractal, OCR, and Blender also offer
`client-local`: the generic launcher artifact data plane serves prepared inputs
directly to capability-gated workers and receives lease-fenced batch outputs.
The same plugin-owned split/planning and assembly code used by server-local jobs
runs on the requester for client-local jobs. OCR rasterizes its PDF locally;
Blender serves one packed scene reference; Fractal has no input artifact. Large
intermediate batches do not traverse the coordinator.

The Blender form defaults to `samples/blender/mechana-camera-orbit-2s.blend`, a
packed lightweight geometry scene with a continuously orbiting camera. Frames
1–48 at 24 fps produce a two-second clip; the development defaults use 640×360,
32 Cycles samples, with the task count derived from the compatible fleet.

## Direction

The descriptor belongs in the plugin package/manifest once the general plugin
manifest contract lands. The temporary server composition catalog is deliberately
transport-shaped and contains no computation logic. Future descriptor versions
can add directory and provider pickers, richer conditional validation, units,
resource estimates, and client-local/cloud authorization without adding
plugin-specific launcher classes.

Artifact rows use provider plus stable key rather than a filesystem path. Future
providers can supply appropriate actions and ownership-based purge behavior.
