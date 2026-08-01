package dev.mechana.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mechana.plugins.video.VideoTypes;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoJobMonitorTest {
	@Test
	void reportsWorkerAndWeightedSegmentProgress() {
		VideoJobMonitor monitor = new VideoJobMonitor(Path.of("input.mp4"), Path.of("output.mkv"));
		VideoTypes.Options defaults = VideoTypes.Options.defaults(VideoTypes.Container.MKV);
		VideoTypes.Options options = new VideoTypes.Options(VideoTypes.Container.MKV, defaults.qualityMode(),
				defaults.crf(), defaults.preset(), Duration.ofSeconds(10), 2, defaults.processTimeout());
		VideoTypes.MediaInfo media = new VideoTypes.MediaInfo("mov,mp4", 20, "h264", 1920, 1080, 1, 1, 0, 0, 1_000_000);
		monitor.onPlan(
				new VideoTypes.Plan(media, options, List.of(new VideoTypes.Segment(0, 0, 10, Path.of("segment-0.mkv")),
						new VideoTypes.Segment(1, 10, 20, Path.of("segment-1.mkv"))), Path.of("scratch")));
		monitor.onStage("TRANSCODING");
		monitor.onSegmentStarted(0);
		monitor.onSegmentStarted(1);
		monitor.onSegmentProgress(0, "out_time_us=5000000");
		monitor.onSegmentCompleted(1);

		VideoJobMonitor.Snapshot snapshot = monitor.snapshot();
		assertEquals(75, snapshot.progress());
		assertEquals(2, snapshot.configuredWorkers());
		assertEquals(1, snapshot.activeWorkers());
		assertEquals(1, snapshot.completedSegments());
		assertEquals(2, snapshot.totalSegments());
	}
}
