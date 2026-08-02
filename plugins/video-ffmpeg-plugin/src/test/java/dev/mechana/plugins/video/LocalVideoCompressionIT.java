package dev.mechana.plugins.video;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalVideoCompressionIT {
	@TempDir
	Path temp;

	@Test
	void transcodesGeneratedFixture() throws Exception {
		var commands = new FfmpegCommands(null, null);
		var capabilities = new RuntimeProbe(commands, new ExternalProcessRunner()).inspect();
		Assumptions.assumeTrue(capabilities.usable(), "ffmpeg, ffprobe, and libx265 are required");
		Path input = temp.resolve("fixture.mp4");
		var generated = new ExternalProcessRunner().run(
				List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
						"testsrc2=size=160x90:rate=15:duration=3", "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
						"-c:v", "libx264", "-g", "15", "-c:a", "aac", "-shortest", input.toString()),
				Duration.ofMinutes(1), CancellationToken.NEVER, ignored -> {
				});
		Assumptions.assumeTrue(generated.exitCode() == 0, generated.stderr());
		Path output = temp.resolve("result.mkv");
		var options = new VideoTypes.Options(VideoTypes.Container.MKV, VideoTypes.QualityMode.VISUALLY_LOSSLESS, 24,
				"ultrafast", Duration.ofSeconds(1), 2, Duration.ofMinutes(2));
		var result = new LocalVideoCompression(commands).run(input, output, temp.resolve("attempt-1"), options,
				CancellationToken.NEVER, (segment, progress) -> {
				});
		assertEquals("hevc", result.videoCodec());
		assertEquals(1, result.audioStreams());
		assertTrue(Files.size(output) > 0);
		assertTrue(Files.isRegularFile(temp.resolve("attempt-1/plan.json")));
	}
}
