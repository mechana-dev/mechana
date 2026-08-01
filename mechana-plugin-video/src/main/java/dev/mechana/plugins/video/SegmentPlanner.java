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
