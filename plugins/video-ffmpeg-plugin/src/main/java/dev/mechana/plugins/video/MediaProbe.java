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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class MediaProbe {
	private static final ObjectMapper JSON = new ObjectMapper();
	private final FfmpegCommands commands;
	private final ExternalProcessRunner runner;

	public MediaProbe(FfmpegCommands commands, ExternalProcessRunner runner) {
		this.commands = commands;
		this.runner = runner;
	}

	public VideoTypes.MediaInfo inspect(Path input, Duration timeout) throws IOException, InterruptedException {
		var result = runner.run(commands.probe(input), timeout, CancellationToken.NEVER, ignored -> {
		});
		requireSuccess(result, "ffprobe");
		JsonNode root = JSON.readTree(result.stdout());
		int videos = 0, audios = 0, subtitles = 0, width = 0, height = 0;
		String codec = "";
		for (JsonNode stream : root.path("streams")) {
			switch (stream.path("codec_type").asText()) {
				case "video" -> {
					videos++;
					if (videos == 1) {
						codec = stream.path("codec_name").asText();
						width = stream.path("width").asInt();
						height = stream.path("height").asInt();
					}
				}
				case "audio" -> audios++;
				case "subtitle" -> subtitles++;
				default -> {
				}
			}
		}
		return new VideoTypes.MediaInfo(root.path("format").path("format_name").asText(),
				root.path("format").path("duration").asDouble(), codec, width, height, videos, audios, subtitles,
				root.path("chapters").size(), Files.size(input));
	}

	public List<Double> keyframes(Path input, Duration timeout) throws IOException, InterruptedException {
		var result = runner.run(commands.keyframes(input), timeout, CancellationToken.NEVER, ignored -> {
		});
		requireSuccess(result, "ffprobe keyframe scan");
		List<Double> values = new ArrayList<>();
		for (String line : result.stdout().lines().toList()) {
			try {
				double value = Double.parseDouble(line.trim());
				if (value >= 0)
					values.add(value);
			} catch (NumberFormatException ignored) {
			}
		}
		return values.stream().distinct().sorted().toList();
	}

	static void requireSuccess(ExternalProcessRunner.Result result, String operation) throws IOException {
		if (result.exitCode() != 0)
			throw new IOException(operation + " failed (exit " + result.exitCode() + "): " + result.stderr().strip());
	}
}
