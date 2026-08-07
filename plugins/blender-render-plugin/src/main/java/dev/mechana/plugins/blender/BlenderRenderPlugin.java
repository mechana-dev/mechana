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

package dev.mechana.plugins.blender;

import dev.mechana.api.PluginDescriptor;
import dev.mechana.api.PluginExecutionException;
import dev.mechana.api.TaskContext;
import dev.mechana.api.TaskPlugin;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Renders one contiguous frame batch with headless Blender/Cycles. */
public final class BlenderRenderPlugin implements TaskPlugin {
	private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("blender-render", "1.0.0");
	private static final Pattern FRAME = Pattern.compile("^Fra:(\\d+)");
	private static final Pattern SAMPLE = Pattern.compile("Rendering (\\d+) / (\\d+) samples");

	@Override
	public PluginDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public void execute(TaskContext context) throws PluginExecutionException {
		Map<String, String> p = context.parameters();
		int batch = integer(p, "batchIndex");
		int first = integer(p, "firstFrame");
		int last = integer(p, "lastFrame");
		Path scratch = null;
		try {
			scratch = Files.createTempDirectory("mechana-blender-");
			Path scene = scratch.resolve("scene.blend");
			stageInput(p, scene);
			Path frames = Files.createDirectories(scratch.resolve("frames"));
			String executable = p.getOrDefault("blenderCommand",
					System.getenv().getOrDefault("MECHANA_BLENDER", "blender"));
			List<String> command = new BlenderCommands(executable).render(scene, frames.resolve("frame_######"), first,
					last, integer(p, "width"), integer(p, "height"), integer(p, "samples"), renderThreads(p));
			run(command, first, last, context, scratch);
			validateFrames(frames, first, last);
			Path archive = scratch.resolve("frames-%05d.zip".formatted(batch));
			zipFrames(frames, archive);
			context.reportProgress(99);
			context.publishArtifact(Objects.requireNonNull(archive.getFileName()).toString(), archive);
			context.reportProgress(100);
		} catch (IOException | InterruptedException | RuntimeException failure) {
			if (failure instanceof InterruptedException)
				Thread.currentThread().interrupt();
			throw new PluginExecutionException("Blender frame batch failed", failure);
		} finally {
			deleteTree(scratch);
		}
	}

	static int renderThreads(Map<String, String> parameters) {
		int threads = Integer.parseInt(parameters.getOrDefault("threads", "1"));
		if (threads == 0)
			return 1;
		if (threads < 0)
			throw new IllegalArgumentException("threads must not be negative");
		return threads;
	}

	static void stageInput(Map<String, String> parameters, Path destination) throws IOException, InterruptedException {
		String local = parameters.get("inputPath");
		if (local != null) {
			Files.copy(Path.of(local), destination);
			return;
		}
		download(required(parameters, "inputUrl"), destination);
	}

	private static void run(List<String> command, int first, int last, TaskContext context, Path scratch)
			throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		configureTemporaryDirectory(builder, scratch);
		Process process = builder.start();
		context.reportProgress(1);
		Thread output = Thread.ofVirtual().start(() -> {
			try (var lines = process.inputReader().lines()) {
				lines.forEach(line -> progressForLine(line, first, last).ifPresent(context::reportProgress));
			}
		});
		long deadline = System.nanoTime() + Duration.ofHours(6).toNanos();
		while (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
			if (context.isCancellationRequested() || System.nanoTime() >= deadline) {
				process.destroyForcibly();
				throw new IOException(context.isCancellationRequested()
						? "Blender render was cancelled"
						: "Blender render timed out");
			}
		}
		output.join();
		if (process.exitValue() != 0)
			throw new IOException("Blender exited with status " + process.exitValue());
	}

	static OptionalInt progressForLine(String line, int first, int last) {
		Matcher frameMatch = FRAME.matcher(line);
		if (!frameMatch.find())
			return OptionalInt.empty();
		int frame = Integer.parseInt(frameMatch.group(1));
		int frameCount = last - first + 1;
		int frameBase = Math.clamp(5 + (frame - first) * 90 / frameCount, 5, 90);
		Matcher sampleMatch = SAMPLE.matcher(line);
		if (!sampleMatch.find())
			return OptionalInt.of(frameBase);
		int sample = Integer.parseInt(sampleMatch.group(1));
		int samples = Integer.parseInt(sampleMatch.group(2));
		if (samples < 1)
			return OptionalInt.of(frameBase);
		int withinFrame = Math.clamp(sample * 85 / samples / frameCount, 0, 85 / frameCount);
		return OptionalInt.of(Math.clamp(frameBase + withinFrame, 5, 95));
	}

	static void configureTemporaryDirectory(ProcessBuilder builder, Path scratch) {
		String value = scratch.toAbsolutePath().normalize().toString();
		builder.environment().put("TMPDIR", value);
		builder.environment().put("TMP", value);
		builder.environment().put("TEMP", value);
	}

	private static void validateFrames(Path directory, int first, int last) throws IOException {
		for (int frame = first; frame <= last; frame++) {
			Path image = directory.resolve("frame_%06d.png".formatted(frame));
			if (!Files.isRegularFile(image) || Files.size(image) == 0
					|| javax.imageio.ImageIO.read(image.toFile()) == null)
				throw new IOException("Missing or invalid rendered frame " + frame);
		}
	}

	private static void zipFrames(Path directory, Path archive) throws IOException {
		try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(archive)));
				var files = Files.list(directory)) {
			for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				output.putNextEntry(new ZipEntry(Objects.requireNonNull(file.getFileName()).toString()));
				Files.copy(file, output);
				output.closeEntry();
			}
		}
	}

	private static void download(String url, Path destination) throws IOException, InterruptedException {
		HttpResponse<Path> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(20)).GET().build(),
				HttpResponse.BodyHandlers.ofFile(destination));
		if (response.statusCode() != 200)
			throw new IOException("Scene download returned HTTP " + response.statusCode());
	}

	private static String required(Map<String, String> parameters, String name) {
		String value = parameters.get(name);
		if (value == null || value.isBlank())
			throw new IllegalArgumentException("Missing parameter " + name);
		return value;
	}

	private static int integer(Map<String, String> parameters, String name) {
		return Integer.parseInt(required(parameters, name));
	}

	private static void deleteTree(Path root) {
		if (root == null)
			return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// Best-effort attempt scratch cleanup.
		}
	}
}
