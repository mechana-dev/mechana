package dev.mechana.plugins.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FinalValidator {
	private final MediaProbe probe;
	public FinalValidator(MediaProbe probe) {
		this.probe = probe;
	}

	public VideoTypes.MediaInfo validate(Path output, VideoTypes.Plan plan) throws IOException, InterruptedException {
		VideoTypes.MediaInfo actual = probe.inspect(output, plan.options().processTimeout());
		String containerToken = plan.options().outputContainer() == VideoTypes.Container.MP4 ? "mp4" : "matroska";
		if (!actual.formatName().contains(containerToken))
			fail("Unexpected output container: " + actual.formatName());
		if (!"hevc".equals(actual.videoCodec()) || actual.videoStreams() != 1)
			fail("Output must contain exactly one HEVC video stream");
		if (actual.width() != plan.input().width() || actual.height() != plan.input().height())
			fail("Output dimensions changed");
		if (actual.audioStreams() != plan.input().audioStreams())
			fail("Output audio stream presence changed");
		double tolerance = Math.max(0.5, plan.input().durationSeconds() * 0.01);
		if (Math.abs(actual.durationSeconds() - plan.input().durationSeconds()) > tolerance)
			fail("Output duration outside tolerance");
		if (!Files.isRegularFile(output) || Files.size(output) == 0)
			fail("Output is empty");
		return actual;
	}
	private static void fail(String message) throws IOException {
		throw new IOException("Final validation failed: " + message);
	}
}
