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

final class LocalLeslieEngine implements AutoCloseable {
	private final ObjectMapper json = new ObjectMapper();
	private final ExecutorService worker = Executors
			.newSingleThreadExecutor(Thread.ofVirtual().name("mechana-local-leslie").factory());

	void submit(Path source, Path root, String outputName, LeslieSettings settings, Consumer<ReverbJob> listener)
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
				new LeslieFileRenderer().render(source, directory.resolve(outputName), settings,
						percent -> listener.accept(new ReverbJob(id, "RUNNING", percent, submitted, null, directory,
								outputName, summary, "")));
				Files.writeString(directory.resolve("leslie-job-report.txt"), report(source, outputName, settings));
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

	private void writeState(ReverbJob job, Path source, LeslieSettings settings) throws IOException {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("jobId", job.id());
		values.put("capability", "modeled-leslie");
		values.put("effect", "Leslie");
		values.put("status", job.status());
		values.put("progress", job.progress());
		values.put("submittedAt", job.submittedAt().toString());
		values.put("completedAt", job.completedAt() == null ? "" : job.completedAt().toString());
		values.put("dryPath", source.toString());
		values.put("outputName", job.outputName());
		values.put("speed", settings.speed().toString());
		values.put("drive", settings.drive());
		values.put("hornLevel", settings.hornLevel());
		values.put("micDistance", settings.micDistance());
		values.put("stereoWidth", settings.stereoWidth());
		values.put("crossoverHertz", settings.crossoverHertz());
		values.put("wet", settings.wet());
		values.put("dry", settings.dry());
		values.put("parameterSummary", summary(settings));
		values.put("error", job.error());
		Path temporary = job.artifactDirectory().resolve("job.json.tmp");
		json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), values);
		Files.move(temporary, job.artifactDirectory().resolve("job.json"), StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
	}

	private static String summary(LeslieSettings value) {
		return "%s · Drive %s · Horn %s · Mic %s · Width %s · Crossover %s Hz · Wet %s · Dry %s".formatted(
				value.speed(), compact(value.drive()), compact(value.hornLevel()), compact(value.micDistance()),
				compact(value.stereoWidth()), compact(value.crossoverHertz()), compact(value.wet()),
				compact(value.dry()));
	}

	private static String report(Path source, String outputName, LeslieSettings settings) {
		return "Mechana Leslie Job Report\n=========================\n\nInput: " + source + "\nOutput: " + outputName
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
