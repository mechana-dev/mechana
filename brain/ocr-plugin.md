# OCR plugin

Last reviewed: 2026-08-04

The `ocr-tesseract` path accepts a server-readable PDF, rasterizes each page
server-side with PDFBox, publishes each grayscale PNG through the default
artifact provider, and distributes only verified staged page images to workers. Each
work unit invokes external Tesseract over a deterministic contiguous page range
and publishes one lease-fenced ZIP of numbered UTF-8 page text. Server assembly
validates every page, preserves order, and publishes Markdown, Unicode LaTeX, and
raw text files. The LaTeX artifact is source-only and does not require a TeX engine;
users may compile it separately with XeLaTeX or another `fontspec`-capable engine.
It includes a TeXShop engine directive so opening the artifact on macOS selects
XeLaTeX instead of incompatible pdfLaTeX.
LaTeX output preserves source-page breaks without adding visible synthetic
`Page N` headings; Markdown retains explicit page headings for navigation.
Submissions may select a one-based starting page and bounded page count, allowing
representative test slices without copying or rewriting the source PDF.

Workers require Java, Tesseract, and the requested traineddata. They should
advertise `ocr-tesseract` only after runtime probing confirms both executable and
language availability.

Current limitations:

- Input PDF paths are server-local and submission is loopback-only.
- Rasterized pages are ephemeral server-local artifacts served by reference; worker caching is not implemented.
- Client-local input, direct batch publication, and ordered client assembly are not yet implemented.
- Markdown and LaTeX preserve page boundaries but do not infer semantic document structure.
- LaTeX compilation is not currently part of the job.
- Mid-page checkpoints and generic artifact-backed resume are not implemented.

## Accepted document-processing direction

OCR is the first capability, not the final boundary. The long-term plugin covers
page rendering, OCR, layout classification, figures, captions, tables, structured
Markdown/HTML/searchable-PDF or data artifacts, per-page confidence and warnings,
ordered assembly, and final validation. Page work units may return multiple
artifacts: text, images, tables, metadata, and diagnostics.

Every PDF/layout/OCR decision remains inside the plugin. Mechana sees a validated
plan, opaque work units, declared resources, artifacts, progress, and assembly
requirements.
