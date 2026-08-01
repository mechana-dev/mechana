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
		for (double desired = target; desired < input.durationSeconds(); desired += target) {
			double chosen = nearestAfter(keyframes, desired, boundaries.getLast());
			if (chosen > boundaries.getLast() + 0.001 && chosen < input.durationSeconds() - 0.001)
				boundaries.add(chosen);
		}
		boundaries.add(input.durationSeconds());
		List<VideoTypes.Segment> segments = new ArrayList<>();
		for (int i = 0; i + 1 < boundaries.size(); i++)
			segments.add(new VideoTypes.Segment(i, boundaries.get(i), boundaries.get(i + 1),
					scratch.resolve("segments").resolve("segment-%05d.mkv".formatted(i))));
		return new VideoTypes.Plan(input, options, segments, scratch);
	}

	private static double nearestAfter(List<Double> keyframes, double desired, double previous) {
		return keyframes.stream().filter(k -> k > previous + 0.001)
				.min((a, b) -> Double.compare(Math.abs(a - desired), Math.abs(b - desired))).orElse(Double.NaN);
	}
}
