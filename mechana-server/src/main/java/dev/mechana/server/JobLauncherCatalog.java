/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mechana.server;

import dev.mechana.protocol.Messages.JobLauncherDescriptor;
import dev.mechana.protocol.Messages.OutputDescriptor;
import dev.mechana.protocol.Messages.SubmissionField;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Composition-boundary catalog until descriptors are loaded from plugin
 * manifests.
 */
final class JobLauncherCatalog {
	private JobLauncherCatalog() {
	}

	static List<JobLauncherDescriptor> available(Map<String, Integer> workerCounts) {
		String observedAt = Instant.now().toString();
		return definitions().stream().filter(definition -> workerCounts.getOrDefault(definition.capabilityId(), 0) > 0)
				.map(definition -> new JobLauncherDescriptor(definition.capabilityId(), definition.displayName(),
						definition.submitPath(), definition.fields(), definition.output(),
						definition.resourceEstimate(), workerCounts.get(definition.capabilityId()), observedAt))
				.toList();
	}

	private static List<JobLauncherDescriptor> definitions() {
		OutputDescriptor serverFiles = new OutputDescriptor("server-local", "directory", "Server job artifacts", false);
		return List.of(
				descriptor("sleep", "Sleep", "/api/jobs", serverFiles, "One worker slot per task",
						integer("taskCount", "Tasks (0 = fleet)", "0", 0, 10000),
						integer("durationMillis", "Duration (ms)", "1000", 1, 86400000)),
				descriptor("video-ffmpeg", "FFmpeg video", "/api/jobs/video", serverFiles,
						"FFmpeg runtime and scratch proportional to the source", file("sourcePath", "Input video"),
						decimal("durationSeconds", "Duration (seconds)", "60", 0.01, 86400),
						integer("segmentCount", "Tasks (0 = fleet)", "0", 0, 10000),
						decimal("targetSizeRatio", "Target size ratio", "0.75", 0.01, 0.99)),
				descriptor("fractal-render", "Fractal render", "/api/jobs/fractal", serverFiles,
						"Pure Java; memory scales with image dimensions",
						integer("imageCount", "Images", "8", 1, 10000),
						integer("taskCount", "Tasks (0 = fleet)", "0", 0, 10000),
						integer("width", "Width", "1024", 64, 8192), integer("height", "Height", "1024", 64, 8192),
						integer("maxIterations", "Maximum iterations", "1000", 16, 100000),
						integer("seed", "Seed", "1", Long.MIN_VALUE, Long.MAX_VALUE)),
				descriptor("ocr-tesseract", "OCR / Tesseract", "/api/jobs/ocr", serverFiles,
						"PDF rasterization on server; Tesseract runtime on workers", file("sourcePath", "Input PDF"),
						integer("taskCount", "Tasks (0 = fleet)", "0", 0, 10000),
						integer("dpi", "DPI", "300", 150, 600), text("language", "Language", "eng"),
						text("title", "Document title", "OCR Document"),
						integer("firstPage", "First page", "1", 1, 100000),
						integer("pageCount", "Page count (0 = all)", "0", 0, 100000)),
				descriptor("blender-render", "Blender render", "/api/jobs/blender", serverFiles,
						"Blender Cycles CPU runtime; frame scratch plus final movie",
						file("sourcePath", "Packed .blend file", "samples/blender/mechana-camera-orbit-2s.blend"),
						integer("taskCount", "Tasks (0 = fleet)", "0", 0, 100000),
						integer("firstFrame", "First frame", "1", 0, 1000000),
						integer("lastFrame", "Last frame", "48", 0, 1000000),
						integer("width", "Width", "640", 64, 8192), integer("height", "Height", "360", 64, 8192),
						integer("samples", "Samples", "32", 1, 4096),
						integer("fps", "Frames per second", "24", 1, 240)));
	}

	private static JobLauncherDescriptor descriptor(String id, String label, String path, OutputDescriptor output,
			String estimate, SubmissionField... fields) {
		return new JobLauncherDescriptor(id, label, path, List.of(fields), output, estimate, 1, "catalog");
	}

	private static SubmissionField file(String name, String label) {
		return file(name, label, "");
	}

	private static SubmissionField file(String name, String label, String value) {
		return new SubmissionField(name, label, "file", true, value, null, null, List.of(), "Server-readable path");
	}

	private static SubmissionField text(String name, String label, String value) {
		return new SubmissionField(name, label, "text", true, value, null, null, List.of(), "");
	}

	private static SubmissionField integer(String name, String label, String value, long min, long max) {
		return new SubmissionField(name, label, "integer", true, value, (double) min, (double) max, List.of(), "");
	}

	private static SubmissionField decimal(String name, String label, String value, double min, double max) {
		return new SubmissionField(name, label, "decimal", true, value, min, max, List.of(), "");
	}
}
