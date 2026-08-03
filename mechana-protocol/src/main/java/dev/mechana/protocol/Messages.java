package dev.mechana.protocol;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** HTTP/JSON messages exchanged by Mechana clients, servers, and workers. */
public final class Messages {

	private Messages() {
	}

	public record JobSubmitRequest(int taskCount, long durationMillis, List<Long> taskDurationsMillis) {
		public JobSubmitRequest(int taskCount, long durationMillis) {
			this(taskCount, durationMillis, List.of());
		}

		public JobSubmitRequest {
			taskDurationsMillis = taskDurationsMillis == null ? List.of() : List.copyOf(taskDurationsMillis);
			if (taskCount < 1 && taskDurationsMillis.isEmpty()) {
				throw new IllegalArgumentException("taskCount must be positive");
			}
			if (taskDurationsMillis.isEmpty() && durationMillis < 1) {
				throw new IllegalArgumentException("durationMillis must be positive");
			}
			if (!taskDurationsMillis.isEmpty() && taskDurationsMillis.stream().anyMatch(duration -> duration < 1))
				throw new IllegalArgumentException("task durations must be positive");
		}
	}

	public record JobSubmission(String jobId) {
		public JobSubmission {
			Objects.requireNonNull(jobId, "jobId");
		}
	}

	public record VideoJobSubmitRequest(String sourcePath, double durationSeconds, int segmentCount,
			double targetSizeRatio) {
		public VideoJobSubmitRequest {
			Objects.requireNonNull(sourcePath, "sourcePath");
			if (durationSeconds <= 0 || segmentCount < 1 || targetSizeRatio <= 0 || targetSizeRatio >= 1)
				throw new IllegalArgumentException("Invalid video job options");
		}
	}

	public record FractalJobSubmitRequest(int imageCount, int taskCount, int width, int height, int maxIterations,
			long seed) {
		public FractalJobSubmitRequest {
			if (imageCount < 1 || taskCount < 0 || taskCount > imageCount)
				throw new IllegalArgumentException("Invalid fractal image or task count");
			if (width < 64 || height < 64 || width > 8192 || height > 8192)
				throw new IllegalArgumentException("Fractal dimensions must be between 64 and 8192 pixels");
			if (maxIterations < 16 || maxIterations > 100_000)
				throw new IllegalArgumentException("maxIterations must be between 16 and 100000");
		}
	}

	public record OcrJobSubmitRequest(String sourcePath, int taskCount, int dpi, String language, String title,
			int firstPage, int pageCount) {
		public OcrJobSubmitRequest(String sourcePath, int taskCount, int dpi, String language, String title) {
			this(sourcePath, taskCount, dpi, language, title, 1, 0);
		}

		public OcrJobSubmitRequest {
			Objects.requireNonNull(sourcePath, "sourcePath");
			language = language == null || language.isBlank() ? "eng" : language;
			title = title == null || title.isBlank() ? "OCR Document" : title;
			if (taskCount < 0)
				throw new IllegalArgumentException("taskCount must not be negative");
			firstPage = firstPage == 0 ? 1 : firstPage;
			if (firstPage < 1 || pageCount < 0)
				throw new IllegalArgumentException("Invalid OCR page range");
			if (dpi < 150 || dpi > 600)
				throw new IllegalArgumentException("dpi must be between 150 and 600");
			if (!language.matches("[A-Za-z0-9_+.-]+"))
				throw new IllegalArgumentException("Invalid OCR language expression");
		}
	}

	public record BlenderJobSubmitRequest(String sourcePath, int firstFrame, int lastFrame, int taskCount, int width,
			int height, int samples, int fps) {
		public BlenderJobSubmitRequest {
			Objects.requireNonNull(sourcePath, "sourcePath");
			if (firstFrame < 0 || lastFrame < firstFrame || taskCount < 1 || taskCount > lastFrame - firstFrame + 1)
				throw new IllegalArgumentException("Invalid Blender frame range or task count");
			if (width < 64 || height < 64 || width > 8192 || height > 8192 || samples < 1 || samples > 4096 || fps < 1
					|| fps > 240)
				throw new IllegalArgumentException("Invalid Blender render options");
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
			int attempt, Map<String, String> parameters) {
		public TaskLease {
			Objects.requireNonNull(jobId, "jobId");
			Objects.requireNonNull(taskId, "taskId");
			Objects.requireNonNull(pluginId, "pluginId");
			Objects.requireNonNull(pluginVersion, "pluginVersion");
			Objects.requireNonNull(pluginEntrypoint, "pluginEntrypoint");
			Objects.requireNonNull(pluginUrl, "pluginUrl");
			Objects.requireNonNull(pluginSha256, "pluginSha256");
			Objects.requireNonNull(leaseToken, "leaseToken");
			parameters = Map.copyOf(parameters);
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

	public record TaskHeartbeat(String leaseToken) {
		public TaskHeartbeat {
			Objects.requireNonNull(leaseToken, "leaseToken");
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
