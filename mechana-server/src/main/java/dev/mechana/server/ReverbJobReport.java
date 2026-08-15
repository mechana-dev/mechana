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

import dev.mechana.coordinator.InMemoryJobMonitor;
import dev.mechana.protocol.Messages.AudioReverbJobSubmitRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Human-readable provenance report for a completed Reverb job. */
final class ReverbJobReport {
	private ReverbJobReport() {
	}

	static String render(Instant submittedAt, InMemoryJobMonitor.Snapshot snapshot, AudioReverbJobSubmitRequest request,
			long dryBytes, long irBytes, Double appliedGain, List<CompletedJobStore.Artifact> artifacts) {
		StringBuilder report = new StringBuilder(1_500);
		report.append("Mechana Reverb Job Report\n");
		report.append("==========================\n\n");
		section(report, "Job");
		line(report, "Job ID", snapshot.jobId());
		line(report, "Capability", snapshot.plugin());
		line(report, "Status", snapshot.stage());
		line(report, "Submitted at (UTC)", submittedAt.toString());
		line(report, "Completed at (UTC)", value(snapshot.completedAt()));
		line(report, "Processing elapsed", snapshot.elapsed());
		line(report, "Wall-clock duration", wallDuration(submittedAt, snapshot.completedAt()));
		line(report, "Progress", snapshot.progress() + "%");
		line(report, "Work units", snapshot.completedWorkUnits() + " of " + snapshot.totalWorkUnits());
		line(report, "Workers", workers(snapshot));
		if (!snapshot.error().isBlank())
			line(report, "Error", snapshot.error());

		section(report, "Inputs");
		line(report, "Dry audio", fileName(request.dryPath()));
		line(report, "Dry source path", request.dryPath());
		line(report, "Dry input size", bytes(dryBytes));
		line(report, "Impulse-response WAV", fileName(request.irPath()));
		line(report, "IR source path", request.irPath());
		line(report, "IR input size", bytes(irBytes));

		section(report, "Reverb Parameters");
		line(report, "Wet level", Double.toString(request.wet()));
		line(report, "Dry level", Double.toString(request.dry()));
		line(report, "Pre-delay", request.preDelayMilliseconds() + " ms");
		line(report, "Wet low-cut", frequency(request.lowCutHertz()));
		line(report, "Wet high-cut", frequency(request.highCutHertz()));
		line(report, "Normalize IR", yesNo(request.normalizeIr()));
		line(report, "Peak protection", yesNo(request.peakProtection()));
		line(report, "Safe headroom", request.headroomDecibels() + " dB");
		line(report, "Applied output gain", appliedGain(appliedGain));
		line(report, "Peak protection engaged", peakProtectionEngaged(request, appliedGain));
		line(report, "Requested tasks", Integer.toString(request.taskCount()));
		line(report, "Storage provider", request.storageProvider());
		line(report, "Shared artifact root",
				request.artifactRoot().isBlank() ? "Not configured" : request.artifactRoot());

		section(report, "Outputs");
		line(report, "Output WAV", request.outputName());
		for (CompletedJobStore.Artifact artifact : artifacts.stream()
				.sorted(java.util.Comparator.comparing(CompletedJobStore.Artifact::name)).toList()) {
			report.append("- ").append(artifact.name()).append("\n");
			report.append("  Size: ").append(bytes(artifact.size())).append("\n");
			report.append("  Provider: ").append(artifact.provider()).append("\n");
			report.append("  SHA-256: ").append(value(artifact.sha256())).append("\n");
		}
		report.append("- reverb-job-report.txt (this file)\n");
		return report.toString();
	}

	private static String frequency(double hertz) {
		return hertz == 0 ? "Off" : hertz + " Hz";
	}

	private static String workers(InMemoryJobMonitor.Snapshot snapshot) {
		String result = snapshot.workUnits().stream().map(InMemoryJobMonitor.WorkUnitSnapshot::workerAddress)
				.filter(worker -> worker != null && !worker.isBlank() && !"—".equals(worker)).distinct().sorted()
				.collect(java.util.stream.Collectors.joining(", "));
		return result.isBlank() ? "None recorded" : result;
	}

	private static String wallDuration(Instant submittedAt, String completedAt) {
		if (completedAt == null || completedAt.isBlank())
			return "Not available";
		try {
			long milliseconds = Math.max(0, Duration.between(submittedAt, Instant.parse(completedAt)).toMillis());
			return String.format(java.util.Locale.ROOT, "%.3f seconds", milliseconds / 1_000.0);
		} catch (java.time.format.DateTimeParseException invalid) {
			return "Not available";
		}
	}

	private static String fileName(String path) {
		Path fileName = Path.of(path).getFileName();
		return fileName == null ? path : fileName.toString();
	}

	private static String bytes(long value) {
		return String.format(java.util.Locale.ROOT, "%,d bytes", value);
	}

	private static String yesNo(boolean value) {
		return value ? "Yes" : "No";
	}

	private static String appliedGain(Double gain) {
		if (gain == null || !Double.isFinite(gain) || gain <= 0)
			return "Not available";
		double decibels = 20 * Math.log10(gain);
		return String.format(java.util.Locale.ROOT, "%.9f (%.3f dB)", gain, decibels);
	}

	private static String peakProtectionEngaged(AudioReverbJobSubmitRequest request, Double gain) {
		if (gain == null || !Double.isFinite(gain) || gain <= 0)
			return "Not available";
		return yesNo(request.peakProtection() && gain < 1 - 1e-12);
	}

	private static String value(String value) {
		return value == null || value.isBlank() ? "Not available" : value;
	}

	private static void section(StringBuilder report, String title) {
		report.append('\n').append(title).append('\n');
		report.append("-".repeat(title.length())).append('\n');
	}

	private static void line(StringBuilder report, String label, String value) {
		report.append(label).append(": ").append(value).append('\n');
	}
}
