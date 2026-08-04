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

import java.time.Duration;
import java.util.List;

public final class RuntimeProbe {
	private final FfmpegCommands commands;
	private final ExternalProcessRunner runner;
	public RuntimeProbe(FfmpegCommands commands, ExternalProcessRunner runner) {
		this.commands = commands;
		this.runner = runner;
	}

	public VideoTypes.RuntimeCapabilities inspect() {
		var ffmpeg = run(List.of(commands.ffmpeg(), "-version"));
		var ffprobe = run(List.of(commands.ffprobe(), "-version"));
		var encoders = run(List.of(commands.ffmpeg(), "-hide_banner", "-encoders"));
		return new VideoTypes.RuntimeCapabilities(ffmpeg != null, ffprobe != null,
				encoders != null && encoders.stdout().contains("libx265"), firstLine(ffmpeg), firstLine(ffprobe));
	}

	private ExternalProcessRunner.Result run(List<String> command) {
		try {
			var r = runner.run(command, Duration.ofSeconds(10), CancellationToken.NEVER, ignored -> {
			});
			return r.exitCode() == 0 ? r : null;
		} catch (java.io.IOException | InterruptedException | RuntimeException unavailable) {
			if (unavailable instanceof InterruptedException)
				Thread.currentThread().interrupt();
			return null;
		}
	}
	private static String firstLine(ExternalProcessRunner.Result result) {
		return result == null ? "" : result.stdout().lines().findFirst().orElse("");
	}
}
