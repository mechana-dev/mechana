package dev.mechana.api;

import java.util.List;
import java.util.Map;

/** Receives observable job lifecycle events without controlling execution. */
public interface JobObserver {
	default void onStage(String stage) {
	}

	default void onPlan(int configuredWorkers, List<WorkUnit> workUnits) {
	}

	default void onWorkUnitStarted(String workUnitId, String workerAddress) {
	}

	default void onWorkUnitProgress(String workUnitId, int percent, Map<String, String> details) {
	}

	default void onWorkUnitCompleted(String workUnitId) {
	}

	default void onWorkUnitFailed(String workUnitId, String message) {
	}

	JobObserver NONE = new JobObserver() {
	};
}
