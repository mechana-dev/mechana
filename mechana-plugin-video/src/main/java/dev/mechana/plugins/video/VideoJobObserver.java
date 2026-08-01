package dev.mechana.plugins.video;

/**
 * Receives observable local-workflow lifecycle events without controlling
 * execution.
 */
public interface VideoJobObserver {
	default void onStage(String stage) {
	}

	default void onPlan(VideoTypes.Plan plan) {
	}

	default void onSegmentStarted(int segment) {
	}

	default void onSegmentStarted(int segment, String workerAddress) {
		onSegmentStarted(segment);
	}

	default void onSegmentProgress(int segment, String update) {
	}

	default void onSegmentCompleted(int segment) {
	}

	default void onSegmentFailed(int segment, String message) {
	}

	VideoJobObserver NONE = new VideoJobObserver() {
	};
}
