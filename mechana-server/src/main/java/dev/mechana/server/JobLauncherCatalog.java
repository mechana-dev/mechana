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
				descriptor("audio-ir-deconvolution", "Create impulse response", "/api/jobs/audio-ir", serverFiles,
						"Pure Java; one worker; source sweep and recorded wet return must have matching sample rates",
						commonStorage("server-local"), file("sweepPath", "Original sweep WAV", "", "wav"),
						file("recordedReturnPath", "Recorded wet return WAV", "", "wav"),
						directory("artifactRoot", "IR library folder (optional)"),
						text("outputName", "IR profile artifact name", "impulse-response.wav"),
						integer("taskCount", "Tasks (0 = fleet)", "0", 0, 1)),
				descriptor("audio-convolution-reverb", "Convolution reverb", "/api/jobs/audio-reverb", serverFiles,
						"Pure Java; one worker; memory scales with IR length and FFT partitions",
						commonStorage("server-local"), file("dryPath", "Dry audio WAV", "", "wav"),
						file("irPath", "Impulse response WAV", "", "wav"),
						directory("artifactRoot", "Shared artifacts folder (optional)"),
						text("outputName", "Output artifact name", "reverberated.wav"),
						integer("taskCount", "Tasks (0 = fleet)", "0", 0, 1), decimal("wet", "Wet level", "0.35", 0, 2),
						decimal("dry", "Dry level", "1.0", 0, 2),
						decimal("preDelayMilliseconds", "Pre-delay (ms)", "20", 0, 10000),
						choice("normalizeIr", "Normalize IR", "true", "true", "false"),
						choice("peakProtection", "Peak protection", "true", "true", "false"),
						decimal("headroomDecibels", "Safe headroom (dB)", "1.0", 0, 24)),
				descriptor("sleep", "Sleep", "/api/jobs", serverFiles, "One worker slot per task",
						commonStorage("server-local"), directory("clientScratchDirectory", "Client scratch directory"),
						directory("clientOutputDirectory", "Client output directory"),
						optionalText("clientTransferHost", "Client transfer host (blank = this computer)", ""),
						integer("taskCount", "Tasks (0 = fleet)", "0", 0, 10000),
						integer("durationMillis", "Duration (ms)", "1000", 1, 86400000)),
				descriptor("video-ffmpeg", "FFmpeg video", "/api/jobs/video", serverFiles,
						"FFmpeg runtime and scratch proportional to the source",
						commonStorage("server-local", "client-local"),
						directory("clientScratchDirectory", "Client scratch directory"),
						directory("clientOutputDirectory", "Client output directory"),
						optionalText("clientTransferHost", "Client transfer host (blank = this computer)", ""),
						file("sourcePath", "Input video"),
						decimal("startOffsetSeconds", "Start offset in seconds", "0", 0, 86400),
						decimal("durationSeconds", "Duration (seconds)", "60", 0.01, 86400),
						integer("segmentCount", "Tasks (0 = fleet)", "0", 0, 10000),
						decimal("targetSizeRatio", "Target size ratio", "0.75", 0.01, 0.99)),
				descriptor("fractal-render", "Fractal render", "/api/jobs/fractal", serverFiles,
						"Pure Java; memory scales with image dimensions", commonStorage("server-local", "client-local"),
						directory("clientScratchDirectory", "Client scratch directory"),
						directory("clientOutputDirectory", "Client output directory"),
						optionalText("clientTransferHost", "Client transfer host (blank = this computer)", ""),
						integer("imageCount", "Images", "8", 1, 10000),
						integer("taskCount", "Tasks (0 = fleet)", "0", 0, 10000),
						integer("width", "Width", "1024", 64, 8192), integer("height", "Height", "1024", 64, 8192),
						integer("maxIterations", "Maximum iterations", "1000", 16, 100000),
						integer("seed", "Seed", "1", Long.MIN_VALUE, Long.MAX_VALUE)),
				descriptor("ocr-tesseract", "OCR / Tesseract", "/api/jobs/ocr", serverFiles,
						"PDF rasterization at the assembly host; Tesseract runtime on workers",
						commonStorage("server-local", "client-local"),
						directory("clientScratchDirectory", "Client scratch directory"),
						directory("clientOutputDirectory", "Client output directory"),
						optionalText("clientTransferHost", "Client transfer host (blank = this computer)", ""),
						file("sourcePath", "Input PDF", "", "pdf"),
						integer("taskCount", "Tasks (0 = fleet)", "0", 0, 10000),
						integer("dpi", "DPI", "300", 150, 600), text("language", "Language", "eng"),
						text("title", "Document title", "OCR Document"),
						integer("firstPage", "First page", "1", 1, 100000),
						integer("pageCount", "Page count (0 = all)", "0", 0, 100000)),
				descriptor("blender-render", "Blender render", "/api/jobs/blender", serverFiles,
						"Blender Cycles CPU runtime; frame scratch plus final movie",
						commonStorage("server-local", "client-local"),
						directory("clientScratchDirectory", "Client scratch directory"),
						directory("clientOutputDirectory", "Client output directory"),
						optionalText("clientTransferHost", "Client transfer host (blank = this computer)", ""),
						file("sourcePath", "Packed .blend file", "samples/blender/mechana-camera-orbit-2s.blend",
								"blend"),
						integer("taskCount", "Tasks (0 = fleet)", "0", 0, 100000),
						integer("firstFrame", "First frame", "1", 0, 1000000),
						integer("lastFrame", "Last frame", "48", 0, 1000000),
						integer("width", "Width", "640", 64, 8192), integer("height", "Height", "360", 64, 8192),
						integer("samples", "Samples", "32", 1, 4096),
						integer("fps", "Frames per second", "24", 1, 240)));
	}

	private static SubmissionField commonStorage(String... providers) {
		return choice("storageProvider", "Storage and assembly", providers[0], providers);
	}

	private static JobLauncherDescriptor descriptor(String id, String label, String path, OutputDescriptor output,
			String estimate, SubmissionField... fields) {
		return new JobLauncherDescriptor(id, label, path, List.of(fields), output, estimate, 1, "catalog");
	}

	private static SubmissionField file(String name, String label) {
		return file(name, label, "");
	}

	private static SubmissionField file(String name, String label, String value) {
		return file(name, label, value, new String[0]);
	}

	private static SubmissionField file(String name, String label, String value, String... acceptedExtensions) {
		return new SubmissionField(name, label, "file", true, value, null, null, List.of(), "Server-readable path",
				List.of(acceptedExtensions));
	}

	private static SubmissionField text(String name, String label, String value) {
		return new SubmissionField(name, label, "text", true, value, null, null, List.of(), "");
	}

	private static SubmissionField optionalText(String name, String label, String value) {
		return new SubmissionField(name, label, "text", false, value, null, null, List.of(), "");
	}

	private static SubmissionField choice(String name, String label, String value, String... choices) {
		return new SubmissionField(name, label, "choice", true, value, null, null, List.of(choices), "");
	}

	private static SubmissionField directory(String name, String label) {
		return new SubmissionField(name, label, "directory", false, "", null, null, List.of(),
				"Directory on the computer running Client Job Launcher");
	}

	private static SubmissionField integer(String name, String label, String value, long min, long max) {
		return new SubmissionField(name, label, "integer", true, value, (double) min, (double) max, List.of(), "");
	}

	private static SubmissionField decimal(String name, String label, String value, double min, double max) {
		return new SubmissionField(name, label, "decimal", true, value, min, max, List.of(), "");
	}
}
