package dev.mechana.coordinator;

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
		if (taskCount < 1 || durationMillis < 1) {
			throw new IllegalArgumentException("Task count and duration must be positive");
		}
		String jobId = UUID.randomUUID().toString();
		List<Task> tasks = new ArrayList<>(taskCount);
		for (int index = 0; index < taskCount; index++) {
			tasks.add(new Task(jobId + "-" + (index + 1), durationMillis));
		}
		jobs.put(jobId, new Job(jobId, tasks));
		return jobId;
	}

	public synchronized void register(String workerId, Set<String> supportedPlugins) {
		workers.put(workerId, new WorkerRegistration(Set.copyOf(supportedPlugins), now()));
	}

	public synchronized Optional<TaskLease> lease(String workerId, Set<String> supportedPlugins,
			PluginLocation plugin) {
		expireLeases();
		register(workerId, supportedPlugins);
		if (!supportedPlugins.contains(SLEEP_PLUGIN_ID)) {
			return Optional.empty();
		}
		for (Job job : jobs.values()) {
			for (Task task : job.tasks) {
				if (task.state == TaskState.QUEUED) {
					task.state = TaskState.RUNNING;
					task.workerId = workerId;
					task.leaseToken = UUID.randomUUID().toString();
					task.leaseExpiresAt = now() + leaseMillis;
					task.attempt++;
					return Optional.of(new TaskLease(job.id, task.id, SLEEP_PLUGIN_ID, SLEEP_PLUGIN_VERSION,
							SLEEP_PLUGIN_ENTRYPOINT, plugin.url(), plugin.sha256(), task.durationMillis,
							task.leaseToken, leaseMillis, task.attempt));
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
		touch(workerId);
		return true;
	}

	public synchronized boolean fail(String workerId, String taskId, String leaseToken) {
		Task task = findTask(taskId);
		if (!ownsLease(task, workerId, leaseToken)) {
			return false;
		}
		requeue(task);
		touch(workerId);
		return true;
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
		String state = job.tasks.stream().allMatch(task -> task.state == TaskState.SUCCEEDED)
				? "SUCCEEDED"
				: job.tasks.stream().anyMatch(task -> task.state == TaskState.RUNNING) ? "RUNNING" : "QUEUED";
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

	public synchronized int expireLeases() {
		int expired = 0;
		long currentTime = now();
		for (Job job : jobs.values()) {
			for (Task task : job.tasks) {
				if (task.state == TaskState.RUNNING && task.leaseExpiresAt <= currentTime) {
					requeue(task);
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

	private static void requeue(Task task) {
		task.state = TaskState.QUEUED;
		task.progress = 0;
		task.workerId = null;
		task.leaseToken = null;
		task.leaseExpiresAt = 0;
	}

	public record PluginLocation(String url, String sha256) {
		public PluginLocation {
			Objects.requireNonNull(url, "url");
			Objects.requireNonNull(sha256, "sha256");
		}
	}

	private record Job(String id, List<Task> tasks) {
	}

	private record WorkerRegistration(Set<String> supportedPlugins, long lastSeenAt) {
	}

	private enum TaskState {
		QUEUED, RUNNING, SUCCEEDED
	}

	private static final class Task {
		private final String id;
		private final long durationMillis;
		private TaskState state = TaskState.QUEUED;
		private int progress;
		private int attempt;
		private String workerId;
		private String leaseToken;
		private long leaseExpiresAt;

		private Task(String id, long durationMillis) {
			this.id = id;
			this.durationMillis = durationMillis;
		}
	}
}
