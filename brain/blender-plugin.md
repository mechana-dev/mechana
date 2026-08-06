# Blender animation plugin

Last reviewed: 2026-08-03

The first `blender-render` slice accepts one server-local, packed `.blend` file and
an explicit inclusive frame range. Planning deterministically divides that range
into contiguous batches. Capability-matched workers download the immutable scene,
invoke configurable Blender in background mode with embedded script auto-execution
disabled, force Cycles CPU rendering, retain persistent scene data across frames
within a batch, and publish lease-fenced ZIP archives of numbered PNG frames.

The server validates that every expected frame exists with the requested
dimensions, invokes external FFmpeg to assemble an H.265 MP4 with Apple's `hvc1`
sample entry for QuickTime compatibility, and archives it as the final artifact.
Blender stdout frame markers drive normalized progress;
cancellation and a six-hour task timeout forcibly terminate the child process.

The path has an end-to-end local distributed proof using Blender 4.5.3 LTS on
three MBA workers. Frames 1–3 of the packed Junkshop fly-through were rendered as
three independent work units, uploaded, validated, and assembled into a 640x360,
24 fps HEVC MP4 in 2 minutes 44 seconds. This proves the workflow, not multi-host
runtime portability; the other fleet nodes did not have Blender installed.
A subsequent visible-motion proof compressed the full camera move into 24 frames;
three workers rendered eight frames each and produced a one-second, 24-distinct-
frame `hvc1` MP4 in 18 minutes 34 seconds. The four-host fleet is now provisioned
with Blender 4.5 LTS: MBA 4.5.3, Rocinante 4.5.12, Hyperion 4.5.3, and Linux
4.5.3. All twelve workers advertise `blender-render` with an explicit executable
path. Job `accf1dd0-95f7-4f9d-8b16-83ba74dbfc9e` completed the first four-host
proof: twelve workers each rendered one frame of a small packed scene using CPU
Cycles, and the server assembled twelve distinct frames into a 640x360, 12 fps,
one-second `hvc1` HEVC MP4 in 19 seconds. Render commands explicitly override the
scene engine to Cycles rather than relying on the packed file's saved engine.

The initial contract excludes external asset trees, linked libraries, embedded
scripts, GPU rendering, simulation baking, audio, multiple scenes/cameras, and
arbitrary passes. It does not yet probe scene metadata server-side or prove Blender
availability at worker registration. Every batch independently downloads the full
packed scene; caching and content-addressed distribution remain future work.

Blender 4.5.3 runs in `SANDBOXED` host-agent worker groups on the MBA profile. The
profile includes the narrow I/O Kit device-enumeration operation required by
Blender's Metal backend discovery even when CPU Cycles is explicitly selected.
It permits local IPC required by Intel Blender 4.5 startup on macOS 12, and
Blender's temporary directories point into the attempt workspace. Without these
operations Blender exited with status 139 during startup. A one-frame CPU Cycles
render—not merely `blender --version`—verified the corrected profile while the
existing network denial, home-directory denial, and workspace write restriction
remained configured.
