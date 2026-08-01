package dev.mechana.plugins.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class VideoAssembler {
	private final FfmpegCommands commands;
	private final ExternalProcessRunner runner;
	public VideoAssembler(FfmpegCommands commands, ExternalProcessRunner runner) {
		this.commands = commands;
		this.runner = runner;
	}

	public void assemble(Path input, Path output, VideoTypes.Plan plan, CancellationToken cancellation)
			throws IOException, InterruptedException {
		Path assembly = plan.scratchRoot().resolve("assembly");
		Files.createDirectories(assembly);
		Path manifest = assembly.resolve("segments.ffconcat");
		StringBuilder text = new StringBuilder("ffconcat version 1.0\n");
		for (VideoTypes.Segment segment : plan.segments())
			text.append("file '").append(escape(segment.output().toAbsolutePath().toString())).append("'\n");
		Files.writeString(manifest, text, StandardCharsets.UTF_8);
		Path video = assembly.resolve("video.mkv");
		run(commands.concat(manifest, video), plan, cancellation, "video concat");
		Path audio = assembly.resolve("audio.mka");
		boolean hasAudio = plan.input().audioStreams() == 1;
		if (hasAudio)
			run(commands.extractAudio(input, audio), plan, cancellation, "audio extraction");
		Path parent = output.toAbsolutePath().getParent();
		if (parent != null)
			Files.createDirectories(parent);
		run(commands.mux(video, audio, output, hasAudio, plan.options().outputContainer()), plan, cancellation,
				"final mux");
	}

	private void run(List<String> command, VideoTypes.Plan plan, CancellationToken cancellation, String name)
			throws IOException, InterruptedException {
		var result = runner.run(command, plan.options().processTimeout(), cancellation, ignored -> {
		});
		MediaProbe.requireSuccess(result, name);
	}
	private static String escape(String path) {
		return path.replace("'", "'\\''");
	}
}
