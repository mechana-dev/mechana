# Media plugin

Last reviewed: 2026-08-01

The planned media plugin is the reference partitioned-workload plugin. It invokes
FFprobe and FFmpeg as bounded external processes; the Mechana core does not link
media internals or embed media-specific scheduling logic.

## Initial video flow

1. FFprobe inspects the input artifact and produces normalized metadata.
2. Planning chooses time ranges aligned to usable keyframes where possible.
3. Partition tasks invoke FFmpeg with one identical normalized runtime signature
   for the entire video job.
4. Each successful partition publishes an ordered, content-verifiable artifact.
5. Server-side assembly validates completeness, order, and compatibility, then
   creates the final artifact.

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
