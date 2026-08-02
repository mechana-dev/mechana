# Fractal render plugin

Last reviewed: 2026-08-02

The pure-Java `fractal-render` plugin is the no-input, CPU-bound reference
workload. A request declares total image count, optional task count, dimensions,
iteration ceiling, and seed. With task count zero, the server selects at most two
batches per connected compatible worker; an explicit count provides deterministic
demonstration topology.

Planning assigns each image index to exactly one batch. Image parameters derive
only from the job seed and image index, so retries reproduce identical filenames
and pixels. Even indices render Mandelbrot views and odd indices render Julia
views. Workers report progress throughout scanline rendering and publish one batch
ZIP through the current lease.

Assembly is plugin-owned composition invoked by the server after every work unit
succeeds. It rejects unexpected, duplicate, missing, or unreadable PNG entries and
publishes:

- every `fractal-NNNNN.png` image;
- `manifest.json` with deterministic job settings and image names;
- `contact-sheet.png` for quick inspection; and
- `fractal-collection.zip` containing the complete collection.

The first slice does not expose per-image mid-task checkpoints, resource or
scratch estimates, content-addressed batch storage, or generic artifact manifests.
An interrupted batch restarts in full under a new fenced attempt.

## Operational verification

On 2026-08-02 the distributed path completed two eight-worker, two-host runs:

- 120 images at 1920×1080 across 12 ten-image batches; and
- 160 images at 3840×2160 across 16 ten-image batches in 12 minutes 34 seconds.

The 4K run archived all 160 PNGs plus the manifest, contact sheet, and approximately
454 MiB collection ZIP. Both runs exercised MBA and Mini workers, queued follow-on
batches, live generic progress, plugin download, batch publication, assembly, and
durable completed-job links.
