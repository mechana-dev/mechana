# mechana
A plugin-driven distributed execution platform for Java

Concrete plugin implementations live under [`plugins/`](plugins/). The public
plugin contract remains in [`mechana-api/`](mechana-api/), separate from plugin
implementations and infrastructure modules.

Current concrete demonstrations include distributed sleep work, FFmpeg video
compression, deterministic Mandelbrot/Julia collection rendering, and distributed
Tesseract OCR of server-rendered PDF pages. See
[`DEVELOPMENT.md`](DEVELOPMENT.md) for runnable server, worker, and client examples.
