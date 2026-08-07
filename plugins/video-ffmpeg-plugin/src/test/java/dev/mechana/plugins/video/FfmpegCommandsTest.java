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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegCommandsTest {
	@TempDir
	Path temporary;

	@Test
	void stagesWorkerProvidedInputWithoutNetworkAccess() throws Exception {
		Path source = temporary.resolve("staged.mp4");
		Path destination = temporary.resolve("work/input.mp4");
		Files.writeString(source, "video-chunk");
		Files.createDirectories(destination.getParent());

		DistributedVideoSegmentPlugin.stageInput(Map.of("inputPath", source.toString()), destination, null);

		assertEquals("video-chunk", Files.readString(destination));
	}

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

	@Test
	void buildsSizeConstrainedSegmentWithExplicitRateControls() {
		var options = new VideoTypes.Options(VideoTypes.Container.MKV, VideoTypes.QualityMode.VISUALLY_LOSSLESS, 28,
				"slow", Duration.ofSeconds(10), 8, Duration.ofMinutes(1));
		var command = new FfmpegCommands("ffmpeg", "ffprobe").bitrateSegment(Path.of("input.mp4"),
				new VideoTypes.Segment(3, 30, 40, Path.of("segment.mkv")), options, 1_500_000);
		assertTrue(command.containsAll(java.util.List.of("-b:v", "1500000", "-maxrate", "1500000", "-bufsize",
				"3000000", "-progress", "pipe:1")));
		assertFalse(command.contains("-crf"));
	}

	@Test
	void buildsKeyframeAlignedServerSideInputChunkWithoutEncoding() {
		var segment = new VideoTypes.Segment(2, 31.823458, 42.625917, Path.of("encoded.mkv"));
		var command = new FfmpegCommands("ffmpeg", "ffprobe").copySegment(Path.of("source.mp4"), segment,
				Path.of("input-00002.mp4"));
		assertTrue(command.containsAll(java.util.List.of("-ss", "31.823458", "-t", "10.802459", "-map", "0:v:0", "-c:v",
				"copy", "input-00002.mp4")));
		assertFalse(command.contains("libx265"));
	}
}
