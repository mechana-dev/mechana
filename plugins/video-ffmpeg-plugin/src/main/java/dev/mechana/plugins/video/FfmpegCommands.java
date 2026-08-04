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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FfmpegCommands {
	private final String ffmpeg;
	private final String ffprobe;

	public FfmpegCommands(String ffmpeg, String ffprobe) {
		this.ffmpeg = blankToDefault(ffmpeg, "ffmpeg");
		this.ffprobe = blankToDefault(ffprobe, "ffprobe");
	}

	public String ffmpeg() {
		return ffmpeg;
	}
	public String ffprobe() {
		return ffprobe;
	}

	public List<String> probe(Path input) {
		return List.of(ffprobe, "-v", "error", "-show_streams", "-show_format", "-show_chapters", "-of", "json",
				input.toString());
	}

	public List<String> keyframes(Path input) {
		return List.of(ffprobe, "-v", "error", "-select_streams", "v:0", "-skip_frame", "nokey", "-show_entries",
				"frame=best_effort_timestamp_time", "-of", "csv=p=0", input.toString());
	}

	public List<String> segment(Path input, VideoTypes.Segment segment, VideoTypes.Options options) {
		List<String> c = base();
		c.addAll(List.of("-ss", decimal(segment.startSeconds()), "-i", input.toString(), "-t",
				decimal(segment.durationSeconds()), "-map", "0:v:0", "-an", "-sn", "-c:v", "libx265", "-preset",
				options.preset()));
		if (options.qualityMode() == VideoTypes.QualityMode.BIT_EXACT_LOSSLESS)
			c.addAll(List.of("-x265-params", "lossless=1"));
		else
			c.addAll(List.of("-crf", Integer.toString(options.crf())));
		c.addAll(List.of("-progress", "pipe:1", "-nostats", "-f", "matroska", segment.output().toString()));
		return List.copyOf(c);
	}

	public List<String> bitrateSegment(Path input, VideoTypes.Segment segment, VideoTypes.Options options,
			long videoBitrate) {
		List<String> c = base();
		c.addAll(List.of("-ss", decimal(segment.startSeconds()), "-i", input.toString(), "-t",
				decimal(segment.durationSeconds()), "-map", "0:v:0", "-an", "-sn", "-c:v", "libx265", "-preset",
				options.preset(), "-b:v", Long.toString(videoBitrate), "-maxrate", Long.toString(videoBitrate),
				"-bufsize", Long.toString(Math.multiplyExact(videoBitrate, 2)), "-progress", "pipe:1", "-nostats", "-f",
				"matroska", segment.output().toString()));
		return List.copyOf(c);
	}

	public List<String> copySegment(Path input, VideoTypes.Segment segment, Path output) {
		List<String> c = base();
		c.addAll(List.of("-ss", decimal(segment.startSeconds()), "-i", input.toString(), "-t",
				decimal(segment.durationSeconds()), "-map", "0:v:0", "-an", "-sn", "-c:v", "copy", "-avoid_negative_ts",
				"make_zero", "-f", "mp4", output.toString()));
		return List.copyOf(c);
	}

	public List<String> extractAudio(Path input, Path output) {
		List<String> c = base();
		c.addAll(List.of("-i", input.toString(), "-map", "0:a:0", "-vn", "-c:a", "copy", "-progress", "pipe:1",
				"-nostats", "-f", "matroska", output.toString()));
		return List.copyOf(c);
	}

	public List<String> concat(Path manifest, Path output) {
		List<String> c = base();
		c.addAll(List.of("-f", "concat", "-safe", "0", "-i", manifest.toString(), "-map", "0:v:0", "-c", "copy",
				"-progress", "pipe:1", "-nostats", output.toString()));
		return List.copyOf(c);
	}

	public List<String> mux(Path video, Path audio, Path output, boolean hasAudio, VideoTypes.Container container) {
		List<String> c = base();
		c.addAll(List.of("-i", video.toString()));
		if (hasAudio)
			c.addAll(List.of("-i", audio.toString(), "-map", "0:v:0", "-map", "1:a:0"));
		else
			c.addAll(List.of("-map", "0:v:0"));
		c.addAll(List.of("-c", "copy"));
		if (container == VideoTypes.Container.MP4)
			c.addAll(List.of("-movflags", "+faststart", "-f", "mp4"));
		else
			c.addAll(List.of("-f", "matroska"));
		c.addAll(List.of("-progress", "pipe:1", "-nostats", output.toString()));
		return List.copyOf(c);
	}

	private List<String> base() {
		return new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y"));
	}
	private static String blankToDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
	private static String decimal(double value) {
		return String.format(Locale.ROOT, "%.6f", value);
	}
}
