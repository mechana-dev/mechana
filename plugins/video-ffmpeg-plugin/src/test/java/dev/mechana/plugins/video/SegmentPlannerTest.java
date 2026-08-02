package dev.mechana.plugins.video;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SegmentPlannerTest {
	@Test
	void alignsDeterministicallyToNearestKeyframes() {
		var info = new VideoTypes.MediaInfo("mov,mp4", 31, "h264", 1920, 1080, 1, 1, 0, 0, 1000);
		var options = new VideoTypes.Options(VideoTypes.Container.MP4, VideoTypes.QualityMode.VISUALLY_LOSSLESS, 18,
				"slow", Duration.ofSeconds(10), 2, Duration.ofMinutes(1));
		var plan = new SegmentPlanner().plan(info, options, List.of(0.0, 8.0, 12.0, 20.0, 28.0), Path.of("scratch"));
		assertEquals(List.of(0.0, 8.0, 20.0), plan.segments().stream().map(VideoTypes.Segment::startSeconds).toList());
		assertEquals(31.0, plan.segments().getLast().endSeconds());
	}

	@Test
	void avoidsTinySegmentsWhenKeyframesAreClusteredNearTheEnd() {
		var info = new VideoTypes.MediaInfo("mov,mp4", 20.733, "h264", 1920, 1080, 1, 1, 0, 0, 1000);
		var options = new VideoTypes.Options(VideoTypes.Container.MKV, VideoTypes.QualityMode.VISUALLY_LOSSLESS, 18,
				"slow", Duration.ofSeconds(5), 4, Duration.ofMinutes(1));
		var plan = new SegmentPlanner().plan(info, options, List.of(0.0, 18.073, 19.073, 20.072), Path.of("scratch"));

		assertEquals(1, plan.segments().size());
		assertEquals(0.0, plan.segments().getFirst().startSeconds());
		assertEquals(20.733, plan.segments().getFirst().endSeconds());
	}
}
