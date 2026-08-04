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

package dev.mechana.plugins.video;

import dev.mechana.api.PluginDescriptor;
import dev.mechana.api.PluginExecutionException;
import dev.mechana.api.TaskContext;
import dev.mechana.api.TaskPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Worker entry point for one server-planned HEVC video segment. */
public final class DistributedVideoSegmentPlugin implements TaskPlugin {
	private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("video-ffmpeg", "1.0.0");

	@Override
	public PluginDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public void execute(TaskContext context) throws PluginExecutionException {
		Map<String, String> parameters = context.parameters();
		Path directory = null;
		try {
			directory = Files.createTempDirectory("mechana-video-segment-");
			Path input = directory.resolve("input.mp4");
			Path output = directory.resolve("segment.mkv");
			download(parameters.get("inputUrl"), input, context);
			double duration = Double.parseDouble(parameters.get("durationSeconds"));
			long bitrate = Long.parseLong(parameters.get("videoBitrate"));
			VideoTypes.Options options = new VideoTypes.Options(VideoTypes.Container.MKV,
					VideoTypes.QualityMode.VISUALLY_LOSSLESS, 28, parameters.getOrDefault("preset", "slow"),
					Duration.ofMillis(Math.max(1, Math.round(duration * 1000))), 1, Duration.ofHours(2));
			VideoTypes.Segment segment = new VideoTypes.Segment(Integer.parseInt(parameters.get("segmentIndex")), 0,
					duration, output);
			new ExternalProcessRunner().run(
					new FfmpegCommands("ffmpeg", "ffprobe").bitrateSegment(input, segment, options, bitrate),
					options.processTimeout(), () -> context.isCancellationRequested(),
					line -> reportProgress(line, duration, context));
			context.reportProgress(99);
			context.publishArtifact("segment-%05d.mkv".formatted(segment.index()), output);
			context.reportProgress(100);
		} catch (IOException | InterruptedException | RuntimeException failure) {
			if (failure instanceof InterruptedException)
				Thread.currentThread().interrupt();
			throw new PluginExecutionException("Distributed video segment failed", failure);
		} finally {
			delete(directory);
		}
	}

	private static void download(String url, Path destination, TaskContext context)
			throws IOException, InterruptedException {
		HttpResponse<InputStream> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(),
				HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200)
			throw new IOException("Input download returned HTTP " + response.statusCode());
		try (InputStream input = response.body(); var output = Files.newOutputStream(destination)) {
			byte[] buffer = new byte[1024 * 1024];
			long copied = 0;
			for (int read; (read = input.read(buffer)) >= 0;) {
				output.write(buffer, 0, read);
				copied += read;
				if (copied % (8L * 1024 * 1024) < read)
					context.reportProgress(0);
			}
		}
	}

	private static void reportProgress(String line, double durationSeconds, TaskContext context) {
		if (!line.startsWith("out_time_us="))
			return;
		try {
			double seconds = Long.parseLong(line.substring("out_time_us=".length())) / 1_000_000.0;
			context.reportProgress(Math.min(98, Math.max(0, (int) Math.round(seconds * 98 / durationSeconds))));
		} catch (NumberFormatException ignored) {
			// FFmpeg can emit N/A before the first encoded frame.
		}
	}

	private static void delete(Path directory) {
		if (directory == null)
			return;
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// Worker temporary storage is best-effort cleanup.
		}
	}
}
