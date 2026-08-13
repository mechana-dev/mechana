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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import dev.mechana.api.TaskContext;
import dev.mechana.plugins.audio.AudioConvolutionReverbPlugin;
import dev.mechana.plugins.audio.DryAudioImporter;
import dev.mechana.plugins.audio.WavFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Single-worker local lifecycle adapter around the production reverb plugin.
 */
public final class LocalReverbEngine implements AutoCloseable {
	private static final String CAPABILITY = "audio-convolution-reverb";
	private final ObjectMapper json = new ObjectMapper();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "mechana-local-reverb");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean cancellation = new AtomicBoolean();
	private volatile ReverbJob current;

	public synchronized ReverbJob submit(ReverbRequest request, Consumer<ReverbJob> listener) throws IOException {
		if (current != null && "RUNNING".equals(current.status()))
			throw new IllegalStateException("A reverb job is already running");
		Files.createDirectories(request.artifactRoot());
		String id = Instant.now().toString().replaceAll("[:.]", "-") + "-"
				+ UUID.randomUUID().toString().substring(0, 8);
		Path directory = request.artifactRoot().resolve(id);
		Files.createDirectory(directory);
		current = new ReverbJob(id, "RUNNING", 0, Instant.now(), null, directory, request.outputName(), "");
		cancellation.set(false);
		writeState(current, request);
		listener.accept(current);
		worker.execute(() -> execute(request, listener));
		return current;
	}

	public void cancel() {
		cancellation.set(true);
	}

	public List<ReverbJob> loadJobs(Path root) throws IOException {
		if (root == null || !Files.isDirectory(root))
			return List.of();
		List<ReverbJob> jobs = new ArrayList<>();
		try (var directories = Files.list(root)) {
			for (Path directory : directories.filter(Files::isDirectory).toList()) {
				Path state = directory.resolve("job.json");
				if (!Files.isRegularFile(state))
					continue;
				try {
					Map<String, Object> values = json.readValue(state.toFile(), new TypeReference<>() {
					});
					String status = text(values, "status");
					if ("RUNNING".equals(status))
						status = "INTERRUPTED";
					jobs.add(new ReverbJob(text(values, "jobId"), status, number(values, "progress"),
							Instant.parse(text(values, "submittedAt")), instant(values.get("completedAt")), directory,
							text(values, "outputName"), text(values, "error")));
				} catch (IOException | RuntimeException ignored) {
					// An unrelated or incomplete directory is not local job history.
				}
			}
		}
		return jobs.stream().sorted(Comparator.comparing(ReverbJob::submittedAt).reversed()).toList();
	}

	private void execute(ReverbRequest request, Consumer<ReverbJob> listener) {
		Path metadata = current.artifactDirectory().resolve("reverb-result.properties");
		Path convertedDry = current.artifactDirectory().resolve(".prepared-dry.wav");
		try {
			int sampleRate;
			try (WavFile.Reader ir = WavFile.open(request.irPath())) {
				sampleRate = ir.format().sampleRate();
			}
			Path preparedDry = DryAudioImporter.prepare(request.dryPath(), sampleRate, convertedDry);
			new AudioConvolutionReverbPlugin()
					.execute(new LocalTaskContext(request, preparedDry, current.artifactDirectory(), percent -> {
						current = current.withProgress(percent);
						listener.accept(current);
					}));
			Path pluginOutput = current.artifactDirectory().resolve("reverberated.wav");
			Path namedOutput = current.artifactDirectory().resolve(request.outputName());
			if (!pluginOutput.equals(namedOutput))
				Files.move(pluginOutput, namedOutput, StandardCopyOption.REPLACE_EXISTING);
			Double gain = readGain(metadata);
			Files.writeString(current.artifactDirectory().resolve("reverb-job-report.txt"), report(request, gain));
			current = current.terminal("SUCCEEDED", "");
		} catch (dev.mechana.api.PluginExecutionException | IOException | RuntimeException failure) {
			String message = rootMessage(failure);
			current = current.terminal(cancellation.get() ? "CANCELLED" : "FAILED", message);
		}
		try {
			Files.deleteIfExists(convertedDry);
		} catch (IOException ignored) {
			// The job result remains valid if temporary-import cleanup fails.
		}
		try {
			writeState(current, request);
		} catch (IOException persistenceFailure) {
			current = current.terminal("FAILED", "Could not save job history: " + rootMessage(persistenceFailure));
		}
		listener.accept(current);
	}

	private final class LocalTaskContext implements TaskContext {
		private final Map<String, String> parameters;
		private final Path directory;
		private final java.util.function.IntConsumer progress;

		private LocalTaskContext(ReverbRequest request, Path preparedDry, Path directory,
				java.util.function.IntConsumer progress) {
			this.directory = directory;
			this.progress = progress;
			parameters = Map.of("dryPath", preparedDry.toString(), "irPath", request.irPath().toString(), "wet",
					Double.toString(request.wet()), "dry", Double.toString(request.dry()), "preDelayMilliseconds",
					Double.toString(request.preDelayMilliseconds()), "normalizeIr",
					Boolean.toString(request.normalizeIr()), "peakProtection",
					Boolean.toString(request.peakProtection()), "headroomDecibels",
					Double.toString(request.headroomDecibels()));
		}

		@Override
		public long durationMillis() {
			return 0;
		}

		@Override
		public Map<String, String> parameters() {
			return parameters;
		}

		@Override
		public void publishArtifact(String name, Path file) {
			try {
				Files.copy(file, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException failure) {
				throw new IllegalStateException("Could not publish " + name, failure);
			}
		}

		@Override
		public void reportProgress(int percent) {
			progress.accept(percent);
		}

		@Override
		public boolean isCancellationRequested() {
			return cancellation.get();
		}
	}

	private void writeState(ReverbJob job, ReverbRequest request) throws IOException {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("jobId", job.id());
		values.put("capability", CAPABILITY);
		values.put("pluginVersion", new AudioConvolutionReverbPlugin().descriptor().version());
		values.put("status", job.status());
		values.put("progress", job.progress());
		values.put("submittedAt", job.submittedAt().toString());
		values.put("completedAt", job.completedAt() == null ? "" : job.completedAt().toString());
		values.put("dryPath", request.dryPath().toString());
		values.put("irPath", request.irPath().toString());
		values.put("outputName", request.outputName());
		values.put("wet", request.wet());
		values.put("dry", request.dry());
		values.put("preDelayMilliseconds", request.preDelayMilliseconds());
		values.put("normalizeIr", request.normalizeIr());
		values.put("peakProtection", request.peakProtection());
		values.put("headroomDecibels", request.headroomDecibels());
		values.put("error", job.error());
		Path temporary = job.artifactDirectory().resolve("job.json.tmp");
		json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), values);
		Files.move(temporary, job.artifactDirectory().resolve("job.json"), StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
	}

	@SuppressFBWarnings(value = "VA_FORMAT_STRING_USES_NEWLINE", justification = "The durable plain-text report intentionally uses LF on every platform")
	private String report(ReverbRequest request, Double gain) throws IOException {
		Path output = current.artifactDirectory().resolve(request.outputName());
		return """
				Mechana Reverb Job Report
				==========================

				Job
				---
				Job ID: %s
				Capability: %s
				Plugin version: %s
				Submitted at (UTC): %s
				Completed at (UTC): %s
				Processing duration: %.3f seconds

				Inputs
				------
				Dry audio: %s
				Dry source path: %s
				Dry input size: %,d bytes
				Impulse-response WAV: %s
				IR source path: %s
				IR input size: %,d bytes

				Reverb Parameters
				-----------------
				Wet level: %s
				Dry level: %s
				Pre-delay: %s ms
				Normalize IR: %s
				Peak protection: %s
				Safe headroom: %s dB
				Applied output gain: %s

				Outputs
				-------
				Output WAV: %s
				Output size: %,d bytes
				Output SHA-256: %s
				""".formatted(current.id(), CAPABILITY, new AudioConvolutionReverbPlugin().descriptor().version(),
				current.submittedAt(), Instant.now(),
				Duration.between(current.submittedAt(), Instant.now()).toMillis() / 1000.0,
				request.dryPath().getFileName(), request.dryPath(), Files.size(request.dryPath()),
				request.irPath().getFileName(), request.irPath(), Files.size(request.irPath()), request.wet(),
				request.dry(), request.preDelayMilliseconds(), yesNo(request.normalizeIr()),
				yesNo(request.peakProtection()), request.headroomDecibels(), gain == null ? "Not available" : gain,
				request.outputName(), Files.size(output), sha256(output));
	}

	private static Double readGain(Path metadata) {
		try {
			return Double.parseDouble(Files.readString(metadata).strip().replaceFirst("^appliedGain=", ""));
		} catch (IOException | NumberFormatException ignored) {
			return null;
		}
	}

	private static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(file)) {
				byte[] buffer = new byte[64 * 1024];
				for (int count; (count = input.read(buffer)) >= 0;)
					digest.update(buffer, 0, count);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String rootMessage(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null)
			root = root.getCause();
		return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
	}

	private static String text(Map<String, Object> values, String name) {
		Object value = values.get(name);
		return value == null ? "" : String.valueOf(value);
	}

	private static int number(Map<String, Object> values, String name) {
		Object value = values.get(name);
		return value instanceof Number number ? number.intValue() : 0;
	}

	private static Instant instant(Object value) {
		return value == null || String.valueOf(value).isBlank() ? null : Instant.parse(String.valueOf(value));
	}

	private static String yesNo(boolean value) {
		return value ? "Yes" : "No";
	}

	@Override
	public void close() {
		cancellation.set(true);
		worker.shutdownNow();
	}
}
