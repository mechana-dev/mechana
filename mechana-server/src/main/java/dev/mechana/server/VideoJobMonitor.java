package dev.mechana.server;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import dev.mechana.plugins.video.VideoJobObserver;
import dev.mechana.plugins.video.VideoTypes;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe read model for one local video job. */
public final class VideoJobMonitor implements VideoJobObserver {
	public record SegmentSnapshot(int index, String state, double startSeconds, double endSeconds, int progress,
			String elapsed) {
	}

	@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Snapshot lists are immutable defensive copies")
	public record Snapshot(String stage, int progress, String elapsed, int configuredWorkers, int activeWorkers,
			int completedSegments, int totalSegments, String input, String output, String error,
			List<SegmentSnapshot> segments, List<String> events) {
	}

	private static final int MAX_EVENTS = 40;
	private final Instant startedAt = Instant.now();
	private final Path input;
	private final Path output;
	private final Map<Integer, SegmentState> segments = new LinkedHashMap<>();
	private final Deque<String> events = new ArrayDeque<>();
	private String stage = "STARTING";
	private String error = "";
	private int configuredWorkers;

	public VideoJobMonitor(Path input, Path output) {
		this.input = input;
		this.output = output;
		addEvent("Job created");
	}

	@Override
	public synchronized void onStage(String stage) {
		this.stage = stage;
		addEvent("Stage: " + stage);
	}

	@Override
	public synchronized void onPlan(VideoTypes.Plan plan) {
		configuredWorkers = plan.options().parallelism();
		for (VideoTypes.Segment segment : plan.segments()) {
			segments.put(segment.index(), new SegmentState(segment.startSeconds(), segment.endSeconds()));
		}
		addEvent("Planned " + segments.size() + " segment(s) for " + configuredWorkers + " worker(s)");
	}

	@Override
	public synchronized void onSegmentStarted(int segment) {
		SegmentState state = requireSegment(segment);
		state.state = "RUNNING";
		state.startedAt = Instant.now();
		addEvent("Segment " + segment + " started");
	}

	@Override
	public synchronized void onSegmentProgress(int segment, String update) {
		SegmentState state = requireSegment(segment);
		if ("progress=end".equals(update)) {
			state.progress = 100;
			return;
		}
		if (!update.startsWith("out_time_us="))
			return;
		try {
			double seconds = Long.parseLong(update.substring("out_time_us=".length())) / 1_000_000.0;
			state.progress = (int) Math.clamp(Math.round(seconds * 100.0 / state.duration()), 0, 99);
		} catch (NumberFormatException ignored) {
			// FFmpeg may report N/A while initializing.
		}
	}

	@Override
	public synchronized void onSegmentCompleted(int segment) {
		SegmentState state = requireSegment(segment);
		state.state = "SUCCEEDED";
		state.progress = 100;
		addEvent("Segment " + segment + " completed");
	}

	@Override
	public synchronized void onSegmentFailed(int segment, String message) {
		SegmentState state = requireSegment(segment);
		state.state = "FAILED";
		addEvent("Segment " + segment + " failed: " + message);
	}

	public synchronized void fail(Throwable failure) {
		stage = "FAILED";
		error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
		addEvent("Job failed: " + error);
	}

	public synchronized Snapshot snapshot() {
		List<SegmentSnapshot> segmentSnapshots = new ArrayList<>();
		int active = 0;
		int completed = 0;
		double weightedProgress = 0;
		double totalDuration = 0;
		for (var entry : segments.entrySet()) {
			SegmentState value = entry.getValue();
			if ("RUNNING".equals(value.state))
				active++;
			if ("SUCCEEDED".equals(value.state))
				completed++;
			weightedProgress += value.duration() * value.progress;
			totalDuration += value.duration();
			segmentSnapshots.add(new SegmentSnapshot(entry.getKey(), value.state, value.startSeconds, value.endSeconds,
					value.progress,
					formatDuration(value.startedAt == null
							? Duration.ZERO
							: Duration.between(value.startedAt, Instant.now()))));
		}
		int progress = totalDuration == 0 ? 0 : (int) Math.round(weightedProgress / totalDuration);
		if ("ASSEMBLING".equals(stage) || "VALIDATING".equals(stage))
			progress = Math.max(progress, 99);
		if ("SUCCEEDED".equals(stage))
			progress = 100;
		return new Snapshot(stage, progress, formatDuration(Duration.between(startedAt, Instant.now())),
				configuredWorkers, active, completed, segments.size(), input.toString(), output.toString(), error,
				List.copyOf(segmentSnapshots), List.copyOf(events));
	}

	private SegmentState requireSegment(int segment) {
		SegmentState state = segments.get(segment);
		if (state == null)
			throw new IllegalArgumentException("Unknown segment: " + segment);
		return state;
	}

	private void addEvent(String message) {
		events.addFirst(Instant.now() + " " + message);
		while (events.size() > MAX_EVENTS)
			events.removeLast();
	}

	private static String formatDuration(Duration duration) {
		long seconds = Math.max(0, duration.toSeconds());
		return "%02d:%02d:%02d".formatted(seconds / 3600, seconds % 3600 / 60, seconds % 60);
	}

	private static final class SegmentState {
		private final double startSeconds;
		private final double endSeconds;
		private String state = "QUEUED";
		private int progress;
		private Instant startedAt;

		private SegmentState(double startSeconds, double endSeconds) {
			this.startSeconds = startSeconds;
			this.endSeconds = endSeconds;
		}

		private double duration() {
			return endSeconds - startSeconds;
		}
	}
}
