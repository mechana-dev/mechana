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

package dev.mechana.coordinator;

import dev.mechana.api.JobObserver;
import dev.mechana.api.WorkUnit;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe in-memory read model for one observable job. */
public final class InMemoryJobMonitor implements JobObserver {
	@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Snapshot maps are immutable defensive copies")
	public record WorkUnitSnapshot(String id, String label, String state, int progress, String elapsed,
			String workerAddress, Map<String, String> details) {
	}

	@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Snapshot collections are immutable defensive copies")
	public record Snapshot(String jobId, String plugin, String stage, int progress, String elapsed,
			int configuredWorkers, int activeWorkers, int completedWorkUnits, int totalWorkUnits, String error,
			String completedAt, Map<String, String> details, List<WorkUnitSnapshot> workUnits, List<String> events) {
	}

	private static final int MAX_EVENTS = 40;
	private final Clock clock;
	private final Instant startedAt;
	private final String jobId;
	private final String plugin;
	private final Map<String, String> details;
	private final Map<String, WorkUnitState> workUnits = new LinkedHashMap<>();
	private final Deque<String> events = new ArrayDeque<>();
	private String stage = "STARTING";
	private String error = "";
	private int configuredWorkers;
	private Instant finishedAt;
	private Instant pausedAt;
	private Duration pausedDuration = Duration.ZERO;

	public InMemoryJobMonitor(String jobId, String plugin, Map<String, String> details) {
		this(jobId, plugin, details, Clock.systemUTC());
	}

	InMemoryJobMonitor(String jobId, String plugin, Map<String, String> details, Clock clock) {
		this.jobId = requireText(jobId, "Job ID");
		this.plugin = requireText(plugin, "Plugin");
		this.details = details == null ? Map.of() : Map.copyOf(details);
		this.clock = java.util.Objects.requireNonNull(clock, "clock");
		this.startedAt = clock.instant();
		addEvent("Job created");
	}

	@Override
	public synchronized void onStage(String stage) {
		this.stage = requireText(stage, "Stage");
		if (isTerminal(stage) && finishedAt == null) {
			finishedAt = now();
			finishPause(finishedAt);
		}
		addEvent("Stage: " + stage);
	}

	@Override
	public synchronized void onPlan(int configuredWorkers, List<WorkUnit> plan) {
		if (configuredWorkers < 0)
			throw new IllegalArgumentException("Configured workers cannot be negative");
		this.configuredWorkers = configuredWorkers;
		workUnits.clear();
		for (WorkUnit workUnit : List.copyOf(plan)) {
			if (workUnits.put(workUnit.id(), new WorkUnitState(workUnit)) != null)
				throw new IllegalArgumentException("Duplicate work-unit ID: " + workUnit.id());
		}
		addEvent("Planned " + workUnits.size() + " work unit(s) for " + configuredWorkers + " worker(s)");
	}

	@Override
	public synchronized void onWorkUnitStarted(String workUnitId, String workerAddress) {
		WorkUnitState state = requireWorkUnit(workUnitId);
		state.state = "RUNNING";
		state.startedAt = now();
		state.finishedAt = null;
		state.workerAddress = workerAddress == null || workerAddress.isBlank() ? "unknown" : workerAddress;
		addEvent(state.workUnit.label() + " started on " + state.workerAddress);
	}

	@Override
	public synchronized void onWorkUnitProgress(String workUnitId, int percent, Map<String, String> details) {
		WorkUnitState state = requireWorkUnit(workUnitId);
		state.progress = Math.max(state.progress, Math.clamp(percent, 0, 100));
		if (details != null && !details.isEmpty())
			state.details.putAll(details);
	}

	@Override
	public synchronized void onWorkUnitCompleted(String workUnitId) {
		WorkUnitState state = requireWorkUnit(workUnitId);
		state.state = "SUCCEEDED";
		state.progress = 100;
		state.finishedAt = now();
		addEvent(state.workUnit.label() + " completed");
	}

	@Override
	public synchronized void onWorkUnitFailed(String workUnitId, String message) {
		WorkUnitState state = requireWorkUnit(workUnitId);
		state.state = "FAILED";
		state.finishedAt = now();
		addEvent(state.workUnit.label() + " failed: " + message);
	}

	public synchronized void requeueWorkUnit(String workUnitId, String message) {
		WorkUnitState state = requireWorkUnit(workUnitId);
		state.state = "QUEUED";
		state.progress = 0;
		state.startedAt = null;
		state.finishedAt = null;
		state.workerAddress = "—";
		addEvent(state.workUnit.label() + " requeued: " + message);
	}

	public synchronized void fail(Throwable failure) {
		stage = "FAILED";
		if (finishedAt == null)
			finishedAt = now();
		error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
		addEvent("Job failed: " + error);
	}

	public synchronized void cancel(String message) {
		Instant cancelledAt = now();
		finishPause(cancelledAt);
		for (WorkUnitState state : workUnits.values()) {
			if (!"SUCCEEDED".equals(state.state) && !"FAILED".equals(state.state)) {
				state.state = "CANCELLED";
				state.finishedAt = cancelledAt;
			}
		}
		stage = "CANCELLED";
		finishedAt = cancelledAt;
		error = message == null ? "" : message;
		addEvent("Job cancelled" + (error.isBlank() ? "" : ": " + error));
	}

	public synchronized void pause() {
		if (isTerminal(stage) || "PAUSED".equals(stage))
			throw new IllegalStateException("Job cannot be paused from stage " + stage);
		Instant pauseTime = now();
		for (WorkUnitState state : workUnits.values()) {
			if (!"SUCCEEDED".equals(state.state) && !"FAILED".equals(state.state)) {
				state.state = "PAUSED";
				if (state.startedAt != null)
					state.finishedAt = pauseTime;
			}
		}
		stage = "PAUSED";
		pausedAt = pauseTime;
		addEvent("Job paused");
	}

	public synchronized void resume() {
		if (!"PAUSED".equals(stage))
			throw new IllegalStateException("Job is not paused");
		finishPause(now());
		for (WorkUnitState state : workUnits.values()) {
			if ("PAUSED".equals(state.state)) {
				state.state = "QUEUED";
				state.progress = 0;
				state.startedAt = null;
				state.finishedAt = null;
				state.workerAddress = "—";
			}
		}
		stage = "QUEUED";
		addEvent("Job resumed; unfinished work units requeued");
	}

	public synchronized Snapshot snapshot() {
		List<WorkUnitSnapshot> snapshots = new ArrayList<>();
		int active = 0;
		int completed = 0;
		double weightedProgress = 0;
		double totalWeight = 0;
		for (WorkUnitState state : workUnits.values()) {
			if ("RUNNING".equals(state.state))
				active++;
			if ("SUCCEEDED".equals(state.state))
				completed++;
			weightedProgress += state.workUnit.weight() * state.progress;
			totalWeight += state.workUnit.weight();
			Instant now = now();
			snapshots.add(new WorkUnitSnapshot(state.workUnit.id(), state.workUnit.label(), state.state, state.progress,
					formatDuration(state.startedAt == null
							? Duration.ZERO
							: Duration.between(state.startedAt, state.finishedAt == null ? now : state.finishedAt)),
					state.workerAddress, Map.copyOf(state.details)));
		}
		int progress = totalWeight == 0 ? 0 : (int) Math.round(weightedProgress / totalWeight);
		if ("ASSEMBLING".equals(stage) || "VALIDATING".equals(stage))
			progress = Math.max(progress, 99);
		if ("SUCCEEDED".equals(stage))
			progress = 100;
		Instant snapshotAt = finishedAt == null ? now() : finishedAt;
		Duration elapsed = Duration.between(startedAt, snapshotAt).minus(pausedDuration);
		if (pausedAt != null)
			elapsed = elapsed.minus(Duration.between(pausedAt, snapshotAt));
		return new Snapshot(jobId, plugin, stage, progress, formatDuration(elapsed), configuredWorkers, active,
				completed, workUnits.size(), error, finishedAt == null ? null : finishedAt.toString(), details,
				List.copyOf(snapshots), List.copyOf(events));
	}

	private WorkUnitState requireWorkUnit(String workUnitId) {
		WorkUnitState state = workUnits.get(workUnitId);
		if (state == null)
			throw new IllegalArgumentException("Unknown work-unit ID: " + workUnitId);
		return state;
	}

	private void addEvent(String message) {
		events.addFirst(now() + " " + message);
		while (events.size() > MAX_EVENTS)
			events.removeLast();
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank())
			throw new IllegalArgumentException(name + " is required");
		return value;
	}

	private Instant now() {
		return clock.instant();
	}

	private void finishPause(Instant resumedAt) {
		if (pausedAt != null) {
			pausedDuration = pausedDuration.plus(Duration.between(pausedAt, resumedAt));
			pausedAt = null;
		}
	}

	private static boolean isTerminal(String stage) {
		return "SUCCEEDED".equals(stage) || "FAILED".equals(stage) || "CANCELLED".equals(stage);
	}

	private static String formatDuration(Duration duration) {
		long seconds = Math.max(0, duration.toSeconds());
		return "%02d:%02d:%02d".formatted(seconds / 3600, seconds % 3600 / 60, seconds % 60);
	}

	private static final class WorkUnitState {
		private final WorkUnit workUnit;
		private final Map<String, String> details;
		private String state = "QUEUED";
		private int progress;
		private Instant startedAt;
		private Instant finishedAt;
		private String workerAddress = "—";

		private WorkUnitState(WorkUnit workUnit) {
			this.workUnit = workUnit;
			this.details = new LinkedHashMap<>(workUnit.details());
		}
	}
}
