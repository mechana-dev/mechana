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
import java.util.List;

public final class VideoTypes {
	private VideoTypes() {
	}

	public enum Container {
		MP4, MKV
	}
	public enum QualityMode {
		VISUALLY_LOSSLESS, BIT_EXACT_LOSSLESS
	}

	public record Options(Container outputContainer, QualityMode qualityMode, int crf, String preset,
			Duration targetSegmentDuration, int parallelism, Duration processTimeout) {
		public Options {
			if (outputContainer == null || qualityMode == null || targetSegmentDuration == null
					|| processTimeout == null || preset == null || preset.isBlank())
				throw new IllegalArgumentException("Missing option");
			if (crf < 0 || crf > 51)
				throw new IllegalArgumentException("CRF must be 0..51");
			if (targetSegmentDuration.isZero() || targetSegmentDuration.isNegative())
				throw new IllegalArgumentException("Segment duration must be positive");
			if (parallelism < 1)
				throw new IllegalArgumentException("Parallelism must be positive");
		}

		public static Options defaults(Container container) {
			return new Options(container, QualityMode.VISUALLY_LOSSLESS, 18, "slow", Duration.ofMinutes(5),
					Math.max(1, Runtime.getRuntime().availableProcessors() / 2), Duration.ofHours(6));
		}
	}

	public record MediaInfo(String formatName, double durationSeconds, String videoCodec, int width, int height,
			int videoStreams, int audioStreams, int subtitleStreams, int chapterCount, long inputBytes) {
	}
	public record Segment(int index, double startSeconds, double endSeconds, Path output) {
		public double durationSeconds() {
			return endSeconds - startSeconds;
		}
	}
	public record Plan(MediaInfo input, Options options, List<Segment> segments, Path scratchRoot) {
		public Plan {
			segments = List.copyOf(segments);
		}
	}
	public record RuntimeCapabilities(boolean ffmpegAvailable, boolean ffprobeAvailable, boolean libx265Available,
			String ffmpegVersion, String ffprobeVersion) {
		public boolean usable() {
			return ffmpegAvailable && ffprobeAvailable && libx265Available;
		}
	}
}
