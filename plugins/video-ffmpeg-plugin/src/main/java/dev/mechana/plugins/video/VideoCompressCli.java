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

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

public final class VideoCompressCli {
	private VideoCompressCli() {
	}
	public static void main(String[] args) throws java.io.IOException, InterruptedException {
		if (args.length < 2) {
			System.err.println(
					"Usage: VideoCompressCli <input.mp4|mkv> <output.mp4|mkv> [scratch-dir] [segment-seconds] [parallelism]");
			System.exit(2);
		}
		Path input = Path.of(args[0]).toAbsolutePath();
		Path output = Path.of(args[1]).toAbsolutePath();
		Path scratch = args.length > 2
				? Path.of(args[2]).toAbsolutePath()
				: output.resolveSibling(output.getFileName() + ".scratch");
		var container = output.toString().toLowerCase(Locale.ROOT).endsWith(".mkv")
				? VideoTypes.Container.MKV
				: VideoTypes.Container.MP4;
		var defaults = VideoTypes.Options.defaults(container);
		var options = new VideoTypes.Options(container, defaults.qualityMode(), defaults.crf(), defaults.preset(),
				args.length > 3 ? Duration.ofSeconds(Long.parseLong(args[3])) : defaults.targetSegmentDuration(),
				args.length > 4 ? Integer.parseInt(args[4]) : defaults.parallelism(), defaults.processTimeout());
		String ffmpeg = System.getenv().getOrDefault("MECHANA_FFMPEG", "ffmpeg");
		String ffprobe = System.getenv().getOrDefault("MECHANA_FFPROBE", "ffprobe");
		var result = new LocalVideoCompression(new FfmpegCommands(ffmpeg, ffprobe)).run(input, output, scratch, options,
				CancellationToken.NEVER, (segment, update) -> System.out.println("segment=" + segment + " " + update));
		System.out.println(
				"Completed: " + output + " (" + result.durationSeconds() + " seconds, " + result.videoCodec() + ")");
	}
}
