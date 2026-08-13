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
  Blender render, and pure-Java convolution reverb and submits through their
  existing server endpoints.
- Convolution reverb provides two WAV pickers, an output artifact name, wet and
  dry levels, pre-delay, attenuation-only IR normalization, peak protection,
  headroom, and an optional shared artifacts folder. When selected, each successful reverb job is
  copied into `<selected folder>/<job ID>/` while its durable server record is
  retained. After the dry and IR WAVs are selected, the output name is suggested
  from both base names plus wet, dry, pre-delay, and IR-normalization settings;
  the suggestion follows input and control changes until the user overrides the
  name. Its POC placement is server-local and its task count is limited to one.
  Each terminal Reverb job also includes a human-readable
  `reverb-job-report.txt` with its inputs, complete controls, timing, workers,
  status, output artifact metadata, actual applied output-gain multiplier and dB
  value, and an explicit indication of whether peak protection engaged.
- FFmpeg video includes `Start offset in seconds`, default `0`, which selects the
  beginning of the requested compression range for server-local and client-local
  jobs. Duration is measured from that offset.
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
  diagnostics, completion time, and provider-aware artifact references. Periodic
  refresh preserves the selected job instead of clearing the selection.
- Opens the selected completed job's artifact folder directly in Finder. Reverb
  jobs with a successfully populated shared folder open that job-specific folder;
  other jobs open their normal server artifact folder.
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

For client-local video, the launcher clips and keyframe-splits the requested range
before transfer, so workers receive only their assigned chunks rather than the
whole source. Worker video partitions return concurrently to launcher scratch;
the launcher verifies them, performs safe final video assembly, and copies the
requested audio range once from the original local source. Multiple workers can
therefore contribute their independent upload bandwidth, bounded by the launcher's
own network, storage, and local assembly capacity. The launcher and source must
remain available until completion. Server-local remains preferable when the source
is already server-resident/cached or the job must outlive the launcher.

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
When a worker advertises `audio-ir-deconvolution`, the generic launcher shows
**Create impulse response**. Select the exact original sweep WAV and its recorded
100%-wet hardware return. The optional IR library folder receives a job-ID
subfolder containing the named IR profile, result properties, job summary, and a
plain-text provenance report. The source and return sample rates must match.
