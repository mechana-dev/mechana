/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
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

final class LocalEchoEngine implements AutoCloseable {
	private final ObjectMapper json = new ObjectMapper();
	private final ExecutorService worker = Executors
			.newSingleThreadExecutor(Thread.ofVirtual().name("mechana-local-echo").factory());

	void submit(Path source, Path root, String outputName, EchoSettings settings, Consumer<ReverbJob> listener)
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
				new EchoFileRenderer().render(source, directory.resolve(outputName), settings,
						percent -> listener.accept(new ReverbJob(id, "RUNNING", percent, submitted, null, directory,
								outputName, summary, "")));
				Files.writeString(directory.resolve("echo-job-report.txt"), report(source, outputName, settings));
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

	private void writeState(ReverbJob job, Path source, EchoSettings settings) throws IOException {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("jobId", job.id());
		values.put("capability", "modeled-echo");
		values.put("effect", "Echo");
		values.put("status", job.status());
		values.put("progress", job.progress());
		values.put("submittedAt", job.submittedAt().toString());
		values.put("completedAt", job.completedAt() == null ? "" : job.completedAt().toString());
		values.put("dryPath", source.toString());
		values.put("outputName", job.outputName());
		values.put("model", settings.model().toString());
		values.put("delayMilliseconds", settings.delayMilliseconds());
		values.put("feedback", settings.feedback());
		values.put("wet", settings.wet());
		values.put("dry", settings.dry());
		values.put("lowCutHertz", settings.lowCutHertz());
		values.put("highCutHertz", settings.highCutHertz());
		values.put("saturation", settings.saturation());
		values.put("modulationRateHertz", settings.modulationRateHertz());
		values.put("modulationDepthMilliseconds", settings.modulationDepthMilliseconds());
		values.put("pingPong", settings.pingPong());
		values.put("parameterSummary", summary(settings));
		values.put("error", job.error());
		Path temporary = job.artifactDirectory().resolve("job.json.tmp");
		json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), values);
		Files.move(temporary, job.artifactDirectory().resolve("job.json"), StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
	}

	private static String summary(EchoSettings value) {
		return "%s · Delay %s ms · Feedback %s · Wet %s · Dry %s · EQ %s/%s Hz · Mod %s Hz/%s ms%s".formatted(
				value.model(), compact(value.delayMilliseconds()), compact(value.feedback()), compact(value.wet()),
				compact(value.dry()), compact(value.lowCutHertz()), compact(value.highCutHertz()),
				compact(value.modulationRateHertz()), compact(value.modulationDepthMilliseconds()),
				value.pingPong() ? " · Ping-pong" : "");
	}

	private static String report(Path source, String outputName, EchoSettings settings) {
		return "Mechana Echo Job Report\n=======================\n\nInput: " + source + "\nOutput: " + outputName
				+ "\nSettings: " + summary(settings) + "\n";
	}

	private static String compact(double value) {
		return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
	}

	@Override
	public void close() {
		worker.shutdownNow();
	}
}
