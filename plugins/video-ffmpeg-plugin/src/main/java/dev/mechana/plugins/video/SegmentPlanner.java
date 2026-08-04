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

public final class SegmentPlanner {
	public VideoTypes.Plan plan(VideoTypes.MediaInfo input, VideoTypes.Options options, List<Double> keyframes,
			Path scratch) {
		List<Double> boundaries = new ArrayList<>();
		boundaries.add(0.0);
		double target = options.targetSegmentDuration().toMillis() / 1000.0;
		double minimumSegment = target / 2.0;
		double maximumSegment = target * 1.5;
		for (double desired = target; desired < input.durationSeconds(); desired += target) {
			double chosen = nearestEligible(keyframes, desired, boundaries.getLast(), input.durationSeconds(),
					minimumSegment, maximumSegment);
			if (!Double.isNaN(chosen))
				boundaries.add(chosen);
		}
		boundaries.add(input.durationSeconds());
		List<VideoTypes.Segment> segments = new ArrayList<>();
		for (int i = 0; i + 1 < boundaries.size(); i++)
			segments.add(new VideoTypes.Segment(i, boundaries.get(i), boundaries.get(i + 1),
					scratch.resolve("segments").resolve("segment-%05d.mkv".formatted(i))));
		return new VideoTypes.Plan(input, options, segments, scratch);
	}

	private static double nearestEligible(List<Double> keyframes, double desired, double previous, double duration,
			double minimumSegment, double maximumSegment) {
		return keyframes.stream().filter(keyframe -> Math.abs(keyframe - desired) <= minimumSegment)
				.filter(keyframe -> keyframe - previous >= minimumSegment)
				.filter(keyframe -> keyframe - previous <= maximumSegment)
				.filter(keyframe -> duration - keyframe >= minimumSegment).min((left, right) -> {
					int distance = Double.compare(Math.abs(left - desired), Math.abs(right - desired));
					return distance != 0 ? distance : Double.compare(left, right);
				}).orElse(Double.NaN);
	}
}
