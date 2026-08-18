/* Copyright (c) 2026 Mark Vita. Licensed under the Apache License, Version 2.0. */
package dev.mechana.localreverb;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class LocalOctaveFuzzEngine implements AutoCloseable {
	private final ObjectMapper json = new ObjectMapper();
	private final ExecutorService worker = Executors
			.newSingleThreadExecutor(Thread.ofVirtual().name("mechana-local-fuzz").factory());

	void submit(Path source, Path root, String outputName, OctaveFuzzSettings settings, Consumer<ReverbJob> listener)
			throws IOException {
		Files.createDirectories(root);
		String id = Instant.now().toString().replaceAll("[:.]", "-") + "-"
				+ UUID.randomUUID().toString().substring(0, 8);
		Path directory = root.resolve(id);
		Files.createDirectory(directory);
		Instant submitted = Instant.now();
		String summary = summary(settings);
		ReverbJob started = new ReverbJob(id, "RUNNING", 0, submitted, null, directory, outputName, summary, "");
		writeState(started, source, settings);
		listener.accept(started);
		worker.execute(() -> {
			ReverbJob result;
			try {
				new OctaveFuzzFileRenderer().render(source, directory.resolve(outputName), settings,
						percent -> listener.accept(new ReverbJob(id, "RUNNING", percent, submitted, null, directory,
								outputName, summary, "")));
				Files.writeString(directory.resolve("octave-fuzz-job-report.txt"),
						"Mechana Octave Fuzz Job Report\n\nInput: " + source + "\nOutput: " + outputName
								+ "\nSettings: " + summary + "\n");
				result = new ReverbJob(id, "SUCCEEDED", 100, submitted, Instant.now(), directory, outputName, summary,
						"");
			} catch (IOException | RuntimeException failure) {
				result = new ReverbJob(id, "FAILED", 100, submitted, Instant.now(), directory, outputName, summary,
						failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
			}
			try {
				writeState(result, source, settings);
			} catch (IOException failure) {
				result = new ReverbJob(id, "FAILED", 100, submitted, Instant.now(), directory, outputName, summary,
						"Could not save history: " + failure.getMessage());
			}
			listener.accept(result);
		});
	}

	private void writeState(ReverbJob job, Path source, OctaveFuzzSettings settings) throws IOException {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("jobId", job.id());
		values.put("capability", "octave-fuzz");
		values.put("effect", "Octave Fuzz");
		values.put("status", job.status());
		values.put("progress", job.progress());
		values.put("submittedAt", job.submittedAt().toString());
		values.put("completedAt", job.completedAt() == null ? "" : job.completedAt().toString());
		values.put("dryPath", source.toString());
		values.put("outputName", job.outputName());
		values.put("drive", settings.drive());
		values.put("tone", settings.tone());
		values.put("level", settings.level());
		values.put("octave", settings.octave());
		values.put("bypass", settings.bypass());
		values.put("parameterSummary", summary(settings));
		values.put("error", job.error());
		Path temporary = job.artifactDirectory().resolve("job.json.tmp");
		json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), values);
		Files.move(temporary, job.artifactDirectory().resolve("job.json"), StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
	}

	private static String summary(OctaveFuzzSettings value) {
		return "Drive %s · Tone %s · Level %s · Octave %s · %s".formatted(value.drive(), value.tone(), value.level(),
				value.octave(), value.bypass() ? "Bypassed" : "Active");
	}

	@Override
	public void close() {
		worker.shutdownNow();
	}
}
