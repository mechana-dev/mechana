package dev.mechana.plugins.video;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FfmpegCommandsTest {
	@Test
	void buildsVisuallyLosslessSegmentWithoutShellInterpolation() {
		var commands = new FfmpegCommands("/tools/ffmpeg", "/tools/ffprobe");
		var options = new VideoTypes.Options(VideoTypes.Container.MP4, VideoTypes.QualityMode.VISUALLY_LOSSLESS, 17,
				"medium", Duration.ofSeconds(10), 2, Duration.ofMinutes(1));
		var command = commands.segment(Path.of("input with spaces.mp4"),
				new VideoTypes.Segment(1, 2.5, 9.0, Path.of("out.mkv")), options);
		assertEquals("/tools/ffmpeg", command.getFirst());
		assertTrue(
				command.containsAll(java.util.List.of("-crf", "17", "-progress", "pipe:1", "input with spaces.mp4")));
		assertFalse(command.contains("lossless=1"));
	}

	@Test
	void buildsExplicitX265LosslessMode() {
		var options = new VideoTypes.Options(VideoTypes.Container.MKV, VideoTypes.QualityMode.BIT_EXACT_LOSSLESS, 18,
				"slow", Duration.ofSeconds(10), 1, Duration.ofMinutes(1));
		var command = new FfmpegCommands(null, null).segment(Path.of("in.mkv"),
				new VideoTypes.Segment(0, 0, 1, Path.of("out.mkv")), options);
		assertTrue(command.contains("lossless=1"));
		assertFalse(command.contains("-crf"));
	}
}
