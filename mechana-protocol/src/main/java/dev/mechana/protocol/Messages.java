package dev.mechana.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** HTTP/JSON messages exchanged by Mechana clients, servers, and workers. */
public final class Messages {

	private Messages() {
	}

	public record JobSubmitRequest(int taskCount, long durationMillis) {
		public JobSubmitRequest {
			if (taskCount < 1) {
				throw new IllegalArgumentException("taskCount must be positive");
			}
			if (durationMillis < 1) {
				throw new IllegalArgumentException("durationMillis must be positive");
			}
		}
	}

	public record JobSubmission(String jobId) {
		public JobSubmission {
			Objects.requireNonNull(jobId, "jobId");
		}
	}

	public record TaskStatus(String taskId, String state, int progress, int attempt, String workerId) {
		public TaskStatus {
			Objects.requireNonNull(taskId, "taskId");
			Objects.requireNonNull(state, "state");
		}
	}

	public record JobStatusResponse(String jobId, String state, int progress, List<TaskStatus> tasks) {
		public JobStatusResponse {
			Objects.requireNonNull(jobId, "jobId");
			Objects.requireNonNull(state, "state");
			tasks = List.copyOf(tasks);
		}
	}

	public record WorkerRegistration(String workerId, String workerAddress, Set<String> supportedPlugins) {
		public WorkerRegistration {
			Objects.requireNonNull(workerId, "workerId");
			Objects.requireNonNull(workerAddress, "workerAddress");
			supportedPlugins = Set.copyOf(supportedPlugins);
		}
	}

	public record WorkerRegistrationResponse(long leaseMillis) {
	}

	public record LeaseRequest(String workerAddress, Set<String> supportedPlugins) {
		public LeaseRequest {
			Objects.requireNonNull(workerAddress, "workerAddress");
			supportedPlugins = Set.copyOf(supportedPlugins);
		}
	}

	public record TaskLease(String jobId, String taskId, String pluginId, String pluginVersion, String pluginEntrypoint,
			String pluginUrl, String pluginSha256, long durationMillis, String leaseToken, long leaseMillis,
			int attempt) {
		public TaskLease {
			Objects.requireNonNull(jobId, "jobId");
			Objects.requireNonNull(taskId, "taskId");
			Objects.requireNonNull(pluginId, "pluginId");
			Objects.requireNonNull(pluginVersion, "pluginVersion");
			Objects.requireNonNull(pluginEntrypoint, "pluginEntrypoint");
			Objects.requireNonNull(pluginUrl, "pluginUrl");
			Objects.requireNonNull(pluginSha256, "pluginSha256");
			Objects.requireNonNull(leaseToken, "leaseToken");
		}
	}

	public record ProgressUpdate(String leaseToken, int percent) {
		public ProgressUpdate {
			Objects.requireNonNull(leaseToken, "leaseToken");
			if (percent < 0 || percent > 100) {
				throw new IllegalArgumentException("percent must be between 0 and 100");
			}
		}
	}

	public record TaskCompletion(String leaseToken) {
		public TaskCompletion {
			Objects.requireNonNull(leaseToken, "leaseToken");
		}
	}

	public record TaskFailure(String leaseToken, String message) {
		public TaskFailure {
			Objects.requireNonNull(leaseToken, "leaseToken");
			Objects.requireNonNull(message, "message");
		}
	}
}
