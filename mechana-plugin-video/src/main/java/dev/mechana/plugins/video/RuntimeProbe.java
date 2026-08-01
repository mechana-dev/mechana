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
