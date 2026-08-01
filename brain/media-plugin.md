# Media plugin

Last reviewed: 2026-08-01

The first local media-plugin slice is the reference partitioned-workload plugin. It invokes
FFprobe and FFmpeg as bounded external processes; the Mechana core does not link
media internals or embed media-specific scheduling logic.

## Initial video flow

1. FFprobe inspects the input artifact and produces normalized metadata.
2. Planning chooses time ranges aligned to usable keyframes where possible.
3. Partition tasks invoke FFmpeg with one identical normalized runtime signature
   for the entire video job.
4. Each successful partition publishes an ordered, content-verifiable artifact.
5. Local assembly concatenates video-only Matroska intermediates, copies the one
   optional audio stream as a separate whole-stream artifact, and remuxes both into
   MP4 or Matroska.
6. FFprobe validates container, HEVC codec, duration tolerance, dimensions, and
   expected video/audio stream presence.

The separate audio path is an intentional first-pass reliability choice: segment
workers do not independently cut audio, avoiding timestamp gaps and overlaps at
keyframe-aligned video boundaries. Audio is copied, so final MP4 muxing can reject
an input audio codec that MP4 does not support.

Segmentation is time-based and keyframe-aware. Segment duration is a planning goal,
not permission to cut at unsafe boundaries or divide by equal byte count. Boundary
ownership must avoid gaps and duplicate presentation time.

## Runtime and process invariants

- The initial job uses the same codec/container-affecting runtime signature for
  every partition; mixed signatures are rejected before assembly.
- FFmpeg/FFprobe executable identity/version and relevant options are observable.
- Arguments are passed without shell interpolation.
- Stdout/stderr are drained, bounded or redirected; exit code, timeout,
  cancellation, and diagnostics map to explicit plugin outcomes.
- Processes are terminated on cancellation/timeout and temporary scratch is
  released through the normal reservation lifecycle.
- Assembly never starts from partial, unordered, stale, or incompatible outputs.
- A later client-side assembler must consume the same artifact/manifest semantics
  as initial server-side assembly.

## Implemented local scope and limits

- One local H.264 video stream, zero or one audio stream, MP4/Matroska input and
  output; subtitles, chapters, and multiple video/audio streams are rejected.
- `VISUALLY_LOSSLESS` defaults to CRF 18 and the slow preset; this is perceptual,
  not mathematically lossless. `BIT_EXACT_LOSSLESS` uses x265 lossless mode and is
  not expected to reduce source size.
- Planning uses FFprobe keyframes and persists deterministic segment definitions
  in `plan.json` beneath an attempt-specific scratch tree.
- A bounded local executor proves parallel work-unit execution. It is not wired to
  cluster scheduling, leases, artifact transport, or scratch reservations.
- FFmpeg progress is parsed from `-progress pipe:1`; cancellation, timeouts, and
  forced process termination are supported.
