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

import dev.mechana.api.WorkUnit;
import dev.mechana.protocol.Messages.JobStatusResponse;
import dev.mechana.protocol.Messages.TaskLease;
import dev.mechana.protocol.Messages.TaskStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory scheduler with renewable leases and automatic abandoned-task
 * recovery.
 */
public final class Scheduler {

	public static final String SLEEP_PLUGIN_ID = "sleep";
	public static final String SLEEP_PLUGIN_VERSION = "1.0.0";
	public static final String SLEEP_PLUGIN_ENTRYPOINT = "dev.mechana.plugins.sleep.SleepPlugin";
	public static final String VIDEO_PLUGIN_ID = "video-ffmpeg";
	public static final String VIDEO_PLUGIN_VERSION = "1.0.0";
	public static final String VIDEO_PLUGIN_ENTRYPOINT = "dev.mechana.plugins.video.DistributedVideoSegmentPlugin";

	private final Clock clock;
	private final long leaseMillis;
	private final Map<String, Job> jobs = new LinkedHashMap<>();
	private final Map<String, WorkerRegistration> workers = new LinkedHashMap<>();

	public Scheduler(long leaseMillis) {
		this(leaseMillis, Clock.systemUTC());
	}

	Scheduler(long leaseMillis, Clock clock) {
		if (leaseMillis < 1) {
			throw new IllegalArgumentException("leaseMillis must be positive");
		}
		this.leaseMillis = leaseMillis;
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public synchronized String submit(int taskCount, long durationMillis) {
		return submit(java.util.Collections.nCopies(taskCount, durationMillis));
	}

	public synchronized String submit(List<Long> durationsMillis) {
		if (durationsMillis.isEmpty() || durationsMillis.stream().anyMatch(duration -> duration < 1))
			throw new IllegalArgumentException("Task durations must be positive");
		String jobId = UUID.randomUUID().toString();
		List<Task> tasks = new ArrayList<>(durationsMillis.size());
		for (int index = 0; index < durationsMillis.size(); index++) {
			tasks.add(new Task(jobId, jobId + "-" + (index + 1), durationsMillis.get(index)));
		}
		String durationSummary = durationsMillis.stream().distinct().count() == 1
				? durationsMillis.getFirst() + "ms"
				: durationsMillis.stream().map(duration -> duration + "ms")
						.collect(java.util.stream.Collectors.joining(","));
		InMemoryJobMonitor monitor = new InMemoryJobMonitor(jobId, SLEEP_PLUGIN_ID,
				Map.of("taskDurations", durationSummary));
		monitor.onPlan(tasks.size(), tasks.stream().map(task -> new WorkUnit(task.id, "Task " + task.id,
				task.durationMillis, Map.of("duration", task.durationMillis + "ms"))).toList());
		monitor.onStage("QUEUED");
		jobs.put(jobId, new Job(jobId, tasks, monitor));
		return jobId;
	}

	public synchronized String submitVideo(List<WorkSpec> work, Map<String, String> details, PluginLocation location) {
		if (work.isEmpty())
			throw new IllegalArgumentException("Video work must not be empty");
		return submitPlugin(VIDEO_PLUGIN_ID, VIDEO_PLUGIN_VERSION, VIDEO_PLUGIN_ENTRYPOINT, work, details, location);
	}

	public synchronized String submitPlugin(String pluginId, String pluginVersion, String pluginEntrypoint,
			List<WorkSpec> work, Map<String, String> details, PluginLocation location) {
		Objects.requireNonNull(pluginId, "pluginId");
		Objects.requireNonNull(pluginVersion, "pluginVersion");
		Objects.requireNonNull(pluginEntrypoint, "pluginEntrypoint");
		Objects.requireNonNull(location, "location");
		if (work.isEmpty())
			throw new IllegalArgumentException("Plugin work must not be empty");
		String jobId = UUID.randomUUID().toString();
		List<Task> tasks = new ArrayList<>(work.size());
		for (int index = 0; index < work.size(); index++) {
			WorkSpec spec = work.get(index);
			tasks.add(new Task(jobId, jobId + "-" + (index + 1), spec.durationMillis(), pluginId, pluginVersion,
					pluginEntrypoint, spec.parameters(), location));
		}
		InMemoryJobMonitor monitor = new InMemoryJobMonitor(jobId, pluginId, details);
		monitor.onPlan(work.size(), java.util.stream.IntStream.range(0, tasks.size()).mapToObj(index -> {
			Task task = tasks.get(index);
			WorkSpec spec = work.get(index);
			return new WorkUnit(task.id, spec.name(), task.durationMillis, spec.displayDetails());
		}).toList());
		monitor.onStage("QUEUED");
		jobs.put(jobId, new Job(jobId, tasks, monitor));
		return jobId;
	}

	public synchronized void register(String workerId, Set<String> supportedPlugins) {
		workers.put(workerId, new WorkerRegistration(Set.copyOf(supportedPlugins), now()));
	}

	public synchronized Optional<TaskLease> lease(String workerId, Set<String> supportedPlugins,
			PluginLocation plugin) {
		expireLeases();
		register(workerId, supportedPlugins);
		for (Job job : jobs.values()) {
			for (Task task : job.tasks) {
				if (task.state == TaskState.QUEUED && supportedPlugins.contains(task.pluginId)) {
					task.state = TaskState.RUNNING;
					task.workerId = workerId;
					task.leaseToken = UUID.randomUUID().toString();
					task.leaseExpiresAt = now() + leaseMillis;
					task.attempt++;
					job.monitor.onStage("EXECUTING");
					job.monitor.onWorkUnitStarted(task.id, workerId);
					PluginLocation taskPlugin = task.pluginLocation == null ? plugin : task.pluginLocation;
					return Optional.of(new TaskLease(job.id, task.id, task.pluginId, task.pluginVersion,
							task.pluginEntrypoint, taskPlugin.url(), taskPlugin.sha256(), task.durationMillis,
							task.leaseToken, leaseMillis, task.attempt, task.parameters));
				}
			}
		}
		return Optional.empty();
	}

	public synchronized boolean progress(String workerId, String taskId, String leaseToken, int percent) {
		Task task = findTask(taskId);
		if (!ownsLease(task, workerId, leaseToken)) {
			return false;
		}
		task.progress = Math.max(task.progress, percent);
		jobs.get(task.jobId).monitor.onWorkUnitProgress(task.id, percent,
				Map.of("attempt", Integer.toString(task.attempt)));
		task.leaseExpiresAt = now() + leaseMillis;
		touch(workerId);
		return true;
	}

	public synchronized boolean heartbeat(String workerId, String taskId, String leaseToken) {
		Task task = findTask(taskId);
		if (!ownsLease(task, workerId, leaseToken))
			return false;
		task.leaseExpiresAt = now() + leaseMillis;
		touch(workerId);
		return true;
	}

	public synchronized boolean complete(String workerId, String taskId, String leaseToken) {
		Task task = findTask(taskId);
		if (!ownsLease(task, workerId, leaseToken)) {
			return false;
		}
		task.progress = 100;
		task.state = TaskState.SUCCEEDED;
		task.leaseExpiresAt = 0;
		Job job = jobs.get(task.jobId);
		job.monitor.onWorkUnitCompleted(task.id);
		if (job.tasks.stream().allMatch(candidate -> candidate.state == TaskState.SUCCEEDED))
			job.monitor.onStage(SLEEP_PLUGIN_ID.equals(job.monitor.snapshot().plugin()) ? "SUCCEEDED" : "ASSEMBLING");
		touch(workerId);
		return true;
	}

	public synchronized boolean acceptsArtifact(String workerId, String taskId, String leaseToken) {
		return ownsLease(findTask(taskId), workerId, leaseToken);
	}

	public synchronized void finishVideo(String jobId, String error) {
		finishAssembly(jobId, error);
	}

	public synchronized void finishAssembly(String jobId, String error) {
		Job job = requireJob(jobId);
		if (!"ASSEMBLING".equals(job.monitor.snapshot().stage()))
			throw new IllegalArgumentException("Job is not ready for assembly: " + jobId);
		if (error == null)
			job.monitor.onStage("SUCCEEDED");
		else
			job.monitor.fail(new IllegalStateException(error));
	}

	public synchronized boolean fail(String workerId, String taskId, String leaseToken) {
		Task task = findTask(taskId);
		if (!ownsLease(task, workerId, leaseToken)) {
			return false;
		}
		requeue(task, "worker reported failure");
		touch(workerId);
		return true;
	}

	public synchronized boolean abort(String jobId) {
		Job job = jobs.get(jobId);
		if (job == null)
			throw new IllegalArgumentException("Unknown job: " + jobId);
		if (isTerminal(job.monitor.snapshot().stage()))
			return false;
		for (Task task : job.tasks) {
			if (task.state != TaskState.SUCCEEDED) {
				task.state = TaskState.CANCELLED;
				task.leaseToken = null;
				task.leaseExpiresAt = 0;
			}
		}
		job.monitor.cancel("Aborted from dashboard");
		return true;
	}

	public synchronized boolean pause(String jobId) {
		Job job = requireJob(jobId);
		String stage = job.monitor.snapshot().stage();
		if (isTerminal(stage) || "PAUSED".equals(stage))
			return false;
		for (Task task : job.tasks) {
			if (task.state == TaskState.QUEUED || task.state == TaskState.RUNNING) {
				task.state = TaskState.PAUSED;
				task.leaseToken = null;
				task.leaseExpiresAt = 0;
			}
		}
		job.monitor.pause();
		return true;
	}

	public synchronized boolean resume(String jobId) {
		Job job = requireJob(jobId);
		if (!"PAUSED".equals(job.monitor.snapshot().stage()))
			return false;
		for (Task task : job.tasks) {
			if (task.state == TaskState.PAUSED) {
				task.state = TaskState.QUEUED;
				task.progress = 0;
				task.workerId = null;
			}
		}
		job.monitor.resume();
		return true;
	}

	public synchronized String resumeAsNew(InMemoryJobMonitor.Snapshot source) {
		Objects.requireNonNull(source, "source");
		if (!SLEEP_PLUGIN_ID.equals(source.plugin()))
			throw new IllegalArgumentException("Unsupported resumable plugin: " + source.plugin());
		if (!"CANCELLED".equals(source.stage()) && !"FAILED".equals(source.stage()))
			throw new IllegalArgumentException("Only cancelled or failed jobs can be resumed as new");
		List<Long> sourceDurations = parseDurations(source.details(), source.totalWorkUnits());
		String jobId = UUID.randomUUID().toString();
		List<Task> tasks = new ArrayList<>(source.totalWorkUnits());
		for (int index = 0; index < source.totalWorkUnits(); index++) {
			Task task = new Task(jobId, jobId + "-" + (index + 1), sourceDurations.get(index));
			if (index < source.workUnits().size() && "SUCCEEDED".equals(source.workUnits().get(index).state())) {
				task.state = TaskState.SUCCEEDED;
				task.progress = 100;
			}
			tasks.add(task);
		}
		long reused = tasks.stream().filter(task -> task.state == TaskState.SUCCEEDED).count();
		String durationSummary = sourceDurations.stream().map(duration -> duration + "ms")
				.collect(java.util.stream.Collectors.joining(","));
		InMemoryJobMonitor monitor = new InMemoryJobMonitor(jobId, SLEEP_PLUGIN_ID, Map.of("taskDurations",
				durationSummary, "resumedFromJobId", source.jobId(), "reusedWorkUnits", Long.toString(reused)));
		monitor.onPlan(tasks.size(), tasks.stream().map(task -> new WorkUnit(task.id, "Task " + task.id,
				task.durationMillis, Map.of("duration", task.durationMillis + "ms"))).toList());
		for (Task task : tasks)
			if (task.state == TaskState.SUCCEEDED)
				monitor.onWorkUnitCompleted(task.id);
		monitor.onStage(tasks.stream().allMatch(task -> task.state == TaskState.SUCCEEDED) ? "SUCCEEDED" : "QUEUED");
		jobs.put(jobId, new Job(jobId, tasks, monitor));
		return jobId;
	}

	public synchronized JobStatusResponse status(String jobId) {
		expireLeases();
		Job job = jobs.get(jobId);
		if (job == null) {
			throw new IllegalArgumentException("Unknown job: " + jobId);
		}
		List<TaskStatus> taskStatuses = job.tasks.stream()
				.map(task -> new TaskStatus(task.id, task.state.name(), task.progress, task.attempt, task.workerId))
				.toList();
		int progress = (int) Math.round(job.tasks.stream().mapToInt(task -> task.progress).average().orElse(0));
		String monitorStage = job.monitor.snapshot().stage();
		String state = Set.of("ASSEMBLING", "VALIDATING", "FAILED").contains(monitorStage)
				? monitorStage
				: job.tasks.stream().anyMatch(task -> task.state == TaskState.CANCELLED)
						? "CANCELLED"
						: job.tasks.stream().anyMatch(task -> task.state == TaskState.PAUSED)
								? "PAUSED"
								: job.tasks.stream().allMatch(task -> task.state == TaskState.SUCCEEDED)
										? "SUCCEEDED"
										: job.tasks.stream().anyMatch(task -> task.state == TaskState.RUNNING)
												? "RUNNING"
												: "QUEUED";
		return new JobStatusResponse(job.id, state, progress, taskStatuses);
	}

	public synchronized JobStatusResponse statusForTask(String taskId) {
		for (Job job : jobs.values()) {
			if (job.tasks.stream().anyMatch(task -> task.id.equals(taskId))) {
				return status(job.id);
			}
		}
		throw new IllegalArgumentException("Unknown task: " + taskId);
	}

	public synchronized InMemoryJobMonitor.Snapshot dashboard(String jobId) {
		Job job = jobs.get(jobId);
		if (job == null)
			throw new IllegalArgumentException("Unknown job: " + jobId);
		return job.monitor.snapshot();
	}

	public synchronized List<InMemoryJobMonitor.Snapshot> dashboards() {
		List<InMemoryJobMonitor.Snapshot> snapshots = jobs.values().stream().map(job -> job.monitor.snapshot())
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		java.util.Collections.reverse(snapshots);
		return List.copyOf(snapshots);
	}

	public synchronized boolean purgeCompleted(String jobId) {
		Job job = jobs.get(jobId);
		if (job == null)
			return false;
		if (!isTerminal(job.monitor.snapshot().stage()))
			throw new IllegalArgumentException("Cannot purge an active job: " + jobId);
		jobs.remove(jobId);
		return true;
	}

	public synchronized int expireLeases() {
		int expired = 0;
		long currentTime = now();
		for (Job job : jobs.values()) {
			for (Task task : job.tasks) {
				if (task.state == TaskState.RUNNING && task.leaseExpiresAt <= currentTime) {
					requeue(task, "lease expired");
					expired++;
				}
			}
		}
		return expired;
	}

	public long leaseMillis() {
		return leaseMillis;
	}

	private long now() {
		return clock.millis();
	}

	private Task findTask(String taskId) {
		return jobs.values().stream().flatMap(job -> job.tasks.stream()).filter(task -> task.id.equals(taskId))
				.findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown task: " + taskId));
	}

	private Job requireJob(String jobId) {
		Job job = jobs.get(jobId);
		if (job == null)
			throw new IllegalArgumentException("Unknown job: " + jobId);
		return job;
	}

	private static List<Long> parseDurations(Map<String, String> details, int taskCount) {
		String value = details.getOrDefault("taskDurations", details.get("taskDuration"));
		if (value == null || value.isBlank())
			throw new IllegalArgumentException("Source job has no resumable task duration");
		List<Long> parsed = java.util.Arrays.stream(value.split(",")).map(String::trim)
				.map(duration -> duration.endsWith("ms") ? duration.substring(0, duration.length() - 2) : duration)
				.map(Long::parseLong).toList();
		return parsed.size() == 1 ? java.util.Collections.nCopies(taskCount, parsed.getFirst()) : parsed;
	}

	private boolean ownsLease(Task task, String workerId, String leaseToken) {
		return task.state == TaskState.RUNNING && Objects.equals(task.workerId, workerId)
				&& Objects.equals(task.leaseToken, leaseToken) && task.leaseExpiresAt > now();
	}

	private void touch(String workerId) {
		WorkerRegistration worker = workers.get(workerId);
		if (worker != null) {
			workers.put(workerId, new WorkerRegistration(worker.supportedPlugins, now()));
		}
	}

	private void requeue(Task task, String reason) {
		task.state = TaskState.QUEUED;
		task.progress = 0;
		task.workerId = null;
		task.leaseToken = null;
		task.leaseExpiresAt = 0;
		Job job = jobs.get(task.jobId);
		job.monitor.requeueWorkUnit(task.id, reason);
		if (job.tasks.stream().noneMatch(candidate -> candidate.state == TaskState.RUNNING))
			job.monitor.onStage("QUEUED");
	}

	private static boolean isTerminal(String stage) {
		return "SUCCEEDED".equals(stage) || "FAILED".equals(stage) || "CANCELLED".equals(stage);
	}

	public record PluginLocation(String url, String sha256) {
		public PluginLocation {
			Objects.requireNonNull(url, "url");
			Objects.requireNonNull(sha256, "sha256");
		}
	}

	public record WorkSpec(long durationMillis, Map<String, String> parameters, String name,
			Map<String, String> displayDetails) {
		public WorkSpec(long durationMillis, Map<String, String> parameters) {
			this(durationMillis, parameters, "Task", Map.of());
		}

		public WorkSpec {
			if (durationMillis < 1)
				throw new IllegalArgumentException("Work duration must be positive");
			parameters = Map.copyOf(parameters);
			Objects.requireNonNull(name, "name");
			displayDetails = Map.copyOf(displayDetails);
		}
	}

	private record Job(String id, List<Task> tasks, InMemoryJobMonitor monitor) {
	}

	private record WorkerRegistration(Set<String> supportedPlugins, long lastSeenAt) {
	}

	private enum TaskState {
		QUEUED, RUNNING, PAUSED, SUCCEEDED, CANCELLED
	}

	private static final class Task {
		private final String jobId;
		private final String id;
		private final long durationMillis;
		private final String pluginId;
		private final String pluginVersion;
		private final String pluginEntrypoint;
		private final Map<String, String> parameters;
		private final PluginLocation pluginLocation;
		private TaskState state = TaskState.QUEUED;
		private int progress;
		private int attempt;
		private String workerId;
		private String leaseToken;
		private long leaseExpiresAt;

		private Task(String jobId, String id, long durationMillis) {
			this(jobId, id, durationMillis, SLEEP_PLUGIN_ID, SLEEP_PLUGIN_VERSION, SLEEP_PLUGIN_ENTRYPOINT, Map.of(),
					null);
		}

		private Task(String jobId, String id, long durationMillis, String pluginId, String pluginVersion,
				String pluginEntrypoint, Map<String, String> parameters, PluginLocation pluginLocation) {
			this.jobId = jobId;
			this.id = id;
			this.durationMillis = durationMillis;
			this.pluginId = pluginId;
			this.pluginVersion = pluginVersion;
			this.pluginEntrypoint = pluginEntrypoint;
			this.parameters = Map.copyOf(parameters);
			this.pluginLocation = pluginLocation;
		}
	}
}
