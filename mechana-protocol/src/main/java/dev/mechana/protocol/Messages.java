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
			if (taskCount < 0 && taskDurationsMillis.isEmpty()) {
				throw new IllegalArgumentException("taskCount must not be negative");
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

	/** Server-provided schema used by generic job launchers. */
	public record JobLauncherDescriptor(String capabilityId, String displayName, String submitPath,
			List<SubmissionField> fields, OutputDescriptor output, String resourceEstimate, int availableWorkers,
			String observedAt) {
		public JobLauncherDescriptor {
			Objects.requireNonNull(capabilityId, "capabilityId");
			Objects.requireNonNull(displayName, "displayName");
			Objects.requireNonNull(submitPath, "submitPath");
			fields = List.copyOf(fields);
			Objects.requireNonNull(output, "output");
			resourceEstimate = resourceEstimate == null ? "Not reported" : resourceEstimate;
			Objects.requireNonNull(observedAt, "observedAt");
			if (availableWorkers < 1)
				throw new IllegalArgumentException("availableWorkers must be positive");
		}
	}

	public record SubmissionField(String name, String label, String type, boolean required, String defaultValue,
			Double minimum, Double maximum, List<String> choices, String help, List<String> acceptedExtensions) {
		public SubmissionField(String name, String label, String type, boolean required, String defaultValue,
				Double minimum, Double maximum, List<String> choices, String help) {
			this(name, label, type, required, defaultValue, minimum, maximum, choices, help, List.of());
		}

		public SubmissionField {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(label, "label");
			Objects.requireNonNull(type, "type");
			defaultValue = defaultValue == null ? "" : defaultValue;
			choices = choices == null ? List.of() : List.copyOf(choices);
			help = help == null ? "" : help;
			acceptedExtensions = acceptedExtensions == null
					? List.of()
					: List.copyOf(acceptedExtensions.stream()
							.map(extension -> extension.toLowerCase(java.util.Locale.ROOT).replaceFirst("^\\.", ""))
							.filter(extension -> !extension.isBlank()).distinct().toList());
			if (!acceptedExtensions.isEmpty() && !"file".equals(type))
				throw new IllegalArgumentException("acceptedExtensions requires a file field");
		}
	}

	public record OutputDescriptor(String provider, String kind, String label, boolean clientSelectable) {
		public OutputDescriptor {
			Objects.requireNonNull(provider, "provider");
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(label, "label");
		}
	}

	public record ArtifactReference(String provider, String key, long size, String url, boolean locallyOwned,
			String sha256) {
		public ArtifactReference(String provider, String key, long size, String url, boolean locallyOwned) {
			this(provider, key, size, url, locallyOwned, "");
		}

		public ArtifactReference {
			Objects.requireNonNull(provider, "provider");
			Objects.requireNonNull(key, "key");
			url = url == null ? "" : url;
			sha256 = sha256 == null ? "" : sha256;
		}
	}

	public record LauncherJob(String jobId, String plugin, String status, int progress, String completedAt,
			String summary, List<String> workerAssignments, List<ArtifactReference> artifacts, boolean purgeAllowed) {
		public LauncherJob {
			Objects.requireNonNull(jobId, "jobId");
			Objects.requireNonNull(plugin, "plugin");
			Objects.requireNonNull(status, "status");
			completedAt = completedAt == null ? "" : completedAt;
			summary = summary == null ? "" : summary;
			workerAssignments = workerAssignments == null ? List.of() : List.copyOf(workerAssignments);
			artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
		}
	}

	public record ClientVideoChunk(String inputUrl, double startSeconds, double endSeconds, long size, String sha256) {
		public ClientVideoChunk {
			Objects.requireNonNull(inputUrl, "inputUrl");
			Objects.requireNonNull(sha256, "sha256");
			if (!inputUrl.startsWith("http://") && !inputUrl.startsWith("https://"))
				throw new IllegalArgumentException("Client video chunk URL must use HTTP or HTTPS");
			if (startSeconds < 0 || endSeconds <= startSeconds || size < 0 || !sha256.matches("[0-9a-fA-F]{64}"))
				throw new IllegalArgumentException("Invalid client video chunk metadata");
		}

		public double durationSeconds() {
			return endSeconds - startSeconds;
		}
	}

	public record VideoJobSubmitRequest(String sourcePath, double durationSeconds, int segmentCount,
			double targetSizeRatio, String storageProvider, String sourceUploadToken,
			List<ClientVideoChunk> clientChunks, String clientOutputUrl, long videoBitrate) {
		public VideoJobSubmitRequest(String sourcePath, double durationSeconds, int segmentCount,
				double targetSizeRatio) {
			this(sourcePath, durationSeconds, segmentCount, targetSizeRatio, "server-local", "", List.of(), "", 0);
		}

		public VideoJobSubmitRequest(String sourcePath, double durationSeconds, int segmentCount,
				double targetSizeRatio, String storageProvider, String sourceUploadToken) {
			this(sourcePath, durationSeconds, segmentCount, targetSizeRatio, storageProvider, sourceUploadToken,
					List.of(), "", 0);
		}

		public VideoJobSubmitRequest {
			Objects.requireNonNull(sourcePath, "sourcePath");
			storageProvider = storageProvider == null || storageProvider.isBlank() ? "server-local" : storageProvider;
			sourceUploadToken = sourceUploadToken == null ? "" : sourceUploadToken;
			clientChunks = clientChunks == null ? List.of() : List.copyOf(clientChunks);
			clientOutputUrl = clientOutputUrl == null ? "" : clientOutputUrl;
			if (durationSeconds <= 0 || segmentCount < 0 || targetSizeRatio <= 0 || targetSizeRatio >= 1)
				throw new IllegalArgumentException("Invalid video job options");
			if (!Set.of("server-local", "client-local").contains(storageProvider))
				throw new IllegalArgumentException("Unsupported video storage provider: " + storageProvider);
			if ("client-local".equals(storageProvider) && sourceUploadToken.isBlank() && clientChunks.isEmpty())
				throw new IllegalArgumentException("Client-local video requires uploaded input or client chunks");
			if (!clientChunks.isEmpty()) {
				if (clientChunks.size() != segmentCount || clientOutputUrl.isBlank() || videoBitrate <= 0)
					throw new IllegalArgumentException("Direct client-local video metadata is incomplete");
				if (!clientOutputUrl.startsWith("http://") && !clientOutputUrl.startsWith("https://"))
					throw new IllegalArgumentException("Client output URL must use HTTP or HTTPS");
			}
		}
	}

	public record VideoAssemblyManifest(String jobId, List<ArtifactReference> segments) {
		public VideoAssemblyManifest {
			Objects.requireNonNull(jobId, "jobId");
			segments = List.copyOf(segments);
		}
	}

	public record ClientAssemblyCompletion(String provider, String key, String name, long size, String sha256) {
		public ClientAssemblyCompletion {
			Objects.requireNonNull(provider, "provider");
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(sha256, "sha256");
			if (!"client-local".equals(provider) || size < 0 || !sha256.matches("[0-9a-fA-F]{64}"))
				throw new IllegalArgumentException("Invalid client assembly artifact metadata");
		}
	}

	public record FractalJobSubmitRequest(int imageCount, int taskCount, int width, int height, int maxIterations,
			long seed, String storageProvider, String clientOutputUrl) {
		public FractalJobSubmitRequest(int imageCount, int taskCount, int width, int height, int maxIterations,
				long seed) {
			this(imageCount, taskCount, width, height, maxIterations, seed, "server-local", "");
		}
		public FractalJobSubmitRequest(int imageCount, int taskCount, int width, int height, int maxIterations,
				long seed, String storageProvider) {
			this(imageCount, taskCount, width, height, maxIterations, seed, storageProvider, "");
		}
		public FractalJobSubmitRequest {
			storageProvider = storageProvider == null || storageProvider.isBlank() ? "server-local" : storageProvider;
			clientOutputUrl = clientOutputUrl == null ? "" : clientOutputUrl;
			if (!Set.of("server-local", "client-local").contains(storageProvider))
				throw new IllegalArgumentException("Unsupported fractal storage provider: " + storageProvider);
			if (imageCount < 1 || taskCount < 0 || taskCount > imageCount)
				throw new IllegalArgumentException("Invalid fractal image or task count");
			if (width < 64 || height < 64 || width > 8192 || height > 8192)
				throw new IllegalArgumentException("Fractal dimensions must be between 64 and 8192 pixels");
			if (maxIterations < 16 || maxIterations > 100_000)
				throw new IllegalArgumentException("maxIterations must be between 16 and 100000");
			validateClientOutput(storageProvider, clientOutputUrl);
		}
	}

	public record OcrJobSubmitRequest(String sourcePath, int taskCount, int dpi, String language, String title,
			int firstPage, int pageCount, String storageProvider, List<ArtifactReference> clientPages,
			String clientOutputUrl) {
		public OcrJobSubmitRequest(String sourcePath, int taskCount, int dpi, String language, String title) {
			this(sourcePath, taskCount, dpi, language, title, 1, 0, "server-local", List.of(), "");
		}
		public OcrJobSubmitRequest(String sourcePath, int taskCount, int dpi, String language, String title,
				int firstPage, int pageCount) {
			this(sourcePath, taskCount, dpi, language, title, firstPage, pageCount, "server-local", List.of(), "");
		}
		public OcrJobSubmitRequest(String sourcePath, int taskCount, int dpi, String language, String title,
				int firstPage, int pageCount, String storageProvider) {
			this(sourcePath, taskCount, dpi, language, title, firstPage, pageCount, storageProvider, List.of(), "");
		}

		public OcrJobSubmitRequest {
			Objects.requireNonNull(sourcePath, "sourcePath");
			language = language == null || language.isBlank() ? "eng" : language;
			title = title == null || title.isBlank() ? "OCR Document" : title;
			storageProvider = storageProvider == null || storageProvider.isBlank() ? "server-local" : storageProvider;
			clientPages = clientPages == null ? List.of() : List.copyOf(clientPages);
			clientOutputUrl = clientOutputUrl == null ? "" : clientOutputUrl;
			if (!Set.of("server-local", "client-local").contains(storageProvider))
				throw new IllegalArgumentException("Unsupported OCR storage provider: " + storageProvider);
			if (taskCount < 0)
				throw new IllegalArgumentException("taskCount must not be negative");
			firstPage = firstPage == 0 ? 1 : firstPage;
			if (firstPage < 1 || pageCount < 0)
				throw new IllegalArgumentException("Invalid OCR page range");
			if (dpi < 150 || dpi > 600)
				throw new IllegalArgumentException("dpi must be between 150 and 600");
			if (!language.matches("[A-Za-z0-9_+.-]+"))
				throw new IllegalArgumentException("Invalid OCR language expression");
			if ("client-local".equals(storageProvider) && clientPages.isEmpty())
				throw new IllegalArgumentException("Client-local OCR requires rasterized page artifacts");
			validateClientOutput(storageProvider, clientOutputUrl);
		}
	}

	public record BlenderJobSubmitRequest(String sourcePath, int firstFrame, int lastFrame, int taskCount, int width,
			int height, int samples, int fps, String storageProvider, ArtifactReference clientScene,
			String clientOutputUrl) {
		public BlenderJobSubmitRequest(String sourcePath, int firstFrame, int lastFrame, int taskCount, int width,
				int height, int samples, int fps) {
			this(sourcePath, firstFrame, lastFrame, taskCount, width, height, samples, fps, "server-local", null, "");
		}
		public BlenderJobSubmitRequest(String sourcePath, int firstFrame, int lastFrame, int taskCount, int width,
				int height, int samples, int fps, String storageProvider) {
			this(sourcePath, firstFrame, lastFrame, taskCount, width, height, samples, fps, storageProvider, null, "");
		}
		public BlenderJobSubmitRequest {
			Objects.requireNonNull(sourcePath, "sourcePath");
			storageProvider = storageProvider == null || storageProvider.isBlank() ? "server-local" : storageProvider;
			clientOutputUrl = clientOutputUrl == null ? "" : clientOutputUrl;
			if (!Set.of("server-local", "client-local").contains(storageProvider))
				throw new IllegalArgumentException("Unsupported Blender storage provider: " + storageProvider);
			if (firstFrame < 0 || lastFrame < firstFrame || taskCount < 0 || taskCount > lastFrame - firstFrame + 1)
				throw new IllegalArgumentException("Invalid Blender frame range or task count");
			if (width < 64 || height < 64 || width > 8192 || height > 8192 || samples < 1 || samples > 4096 || fps < 1
					|| fps > 240)
				throw new IllegalArgumentException("Invalid Blender render options");
			if ("client-local".equals(storageProvider) && clientScene == null)
				throw new IllegalArgumentException("Client-local Blender requires a scene artifact");
			validateClientOutput(storageProvider, clientOutputUrl);
		}
	}

	private static void validateClientOutput(String provider, String outputUrl) {
		if ("client-local".equals(provider) && (!outputUrl.startsWith("http://") && !outputUrl.startsWith("https://")))
			throw new IllegalArgumentException("Client output URL must use HTTP or HTTPS");
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

	public record TaskCompletion(String leaseToken, long inputBytes, long outputBytes, long pluginBytes) {
		public TaskCompletion(String leaseToken) {
			this(leaseToken, 0, 0, 0);
		}
		public TaskCompletion {
			Objects.requireNonNull(leaseToken, "leaseToken");
			if (inputBytes < 0 || outputBytes < 0 || pluginBytes < 0)
				throw new IllegalArgumentException("Task transfer counters must not be negative");
		}
	}

	public record TaskFailure(String leaseToken, String message) {
		public TaskFailure {
			Objects.requireNonNull(leaseToken, "leaseToken");
			Objects.requireNonNull(message, "message");
		}
	}
}
