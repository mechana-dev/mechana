package dev.mechana.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mechana.api.PluginDescriptor;
import dev.mechana.api.TaskContext;
import dev.mechana.api.TaskPlugin;
import dev.mechana.protocol.Messages.LeaseRequest;
import dev.mechana.protocol.Messages.ProgressUpdate;
import dev.mechana.protocol.Messages.TaskCompletion;
import dev.mechana.protocol.Messages.TaskFailure;
import dev.mechana.protocol.Messages.TaskLease;
import dev.mechana.protocol.Messages.WorkerRegistration;
import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pull-based worker that downloads each assigned plugin into ephemeral storage.
 */
public final class WorkerAgent {

	private final ObjectMapper json = new ObjectMapper();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final URI server;
	private final String workerId;
	private final Set<String> supportedPlugins;

	public WorkerAgent(URI server, String workerId, Set<String> supportedPlugins) {
		this.server = URI.create(stripTrailingSlash(Objects.requireNonNull(server, "server").toString()));
		this.workerId = Objects.requireNonNull(workerId, "workerId");
		this.supportedPlugins = Set.copyOf(supportedPlugins);
	}

	public void runForever() {
		while (!Thread.currentThread().isInterrupted()) {
			try {
				register();
				while (!Thread.currentThread().isInterrupted()) {
					if (!runOne()) {
						sleep(500);
					}
				}
			} catch (IOException communicationFailure) {
				System.err.printf("Worker %s lost contact with server: %s%n", workerId,
						communicationFailure.getMessage());
				sleep(1_000);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public void register() throws IOException, InterruptedException {
		Response response = post("/api/workers/register", new WorkerRegistration(workerId, supportedPlugins));
		requireStatus(response, 200);
	}

	public boolean runOne() throws IOException, InterruptedException {
		Response response = post("/api/workers/" + workerId + "/lease", new LeaseRequest(supportedPlugins));
		if (response.status == 204) {
			return false;
		}
		requireStatus(response, 200);
		TaskLease lease = json.readValue(response.body, TaskLease.class);
		System.out.printf("Worker %s assigned task %s for job %s (attempt %d)%n", workerId, lease.taskId(),
				lease.jobId(), lease.attempt());
		execute(lease);
		return true;
	}

	private void execute(TaskLease lease) throws IOException, InterruptedException {
		Path pluginFile = downloadPlugin(lease);
		AtomicBoolean cancelled = new AtomicBoolean();
		try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{pluginFile.toUri().toURL()},
				TaskPlugin.class.getClassLoader())) {
			Class<? extends TaskPlugin> pluginType = Class.forName(lease.pluginEntrypoint(), true, loader)
					.asSubclass(TaskPlugin.class);
			TaskPlugin plugin = pluginType.getConstructor().newInstance();
			verifyDescriptor(plugin.descriptor(), lease);
			System.out.printf("Worker %s running task %s%n", workerId, lease.taskId());
			TaskContext context = new RemoteTaskContext(lease, cancelled);
			plugin.execute(context);
			Response completion = post(taskPath(lease, "complete"), new TaskCompletion(lease.leaseToken()));
			requireStatus(completion, 204);
			System.out.printf("Worker %s finished task %s successfully%n", workerId, lease.taskId());
		} catch (ReflectiveOperationException | RuntimeException | dev.mechana.api.PluginExecutionException failure) {
			cancelled.set(true);
			try {
				post(taskPath(lease, "fail"), new TaskFailure(lease.leaseToken(), safeMessage(failure)));
			} catch (IOException ignored) {
				// The server will reclaim the task when its lease expires.
			}
			System.err.printf("Worker %s finished task %s with failure: %s%n", workerId, lease.taskId(),
					safeMessage(failure));
		} finally {
			Files.deleteIfExists(pluginFile);
		}
	}

	private Path downloadPlugin(TaskLease lease) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(lease.pluginUrl())).timeout(Duration.ofSeconds(30))
				.GET().build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("Plugin download returned HTTP " + response.statusCode());
		}
		String actualChecksum = sha256(response.body());
		if (!actualChecksum.equalsIgnoreCase(lease.pluginSha256())) {
			throw new IOException("Plugin checksum did not match assignment");
		}
		Path temporaryJar = Files.createTempFile("mechana-plugin-", ".jar");
		Files.write(temporaryJar, response.body());
		return temporaryJar;
	}

	private Response post(String path, Object body) throws IOException, InterruptedException {
		byte[] content = json.writeValueAsBytes(body);
		HttpRequest request = HttpRequest.newBuilder(server.resolve(path)).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(content))
				.build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		return new Response(response.statusCode(), response.body());
	}

	private static void requireStatus(Response response, int expected) throws IOException {
		if (response.status != expected) {
			throw new IOException("Server returned HTTP " + response.status + ": "
					+ new String(response.body, java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	private static void verifyDescriptor(PluginDescriptor descriptor, TaskLease lease) {
		if (!descriptor.id().equals(lease.pluginId()) || !descriptor.version().equals(lease.pluginVersion())) {
			throw new IllegalArgumentException("Downloaded plugin identity does not match assignment");
		}
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private String taskPath(TaskLease lease, String action) {
		return "/api/workers/" + workerId + "/tasks/" + lease.taskId() + "/" + action;
	}

	private static String safeMessage(Throwable failure) {
		return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	private final class RemoteTaskContext implements TaskContext {
		private final TaskLease lease;
		private final AtomicBoolean cancelled;

		private RemoteTaskContext(TaskLease lease, AtomicBoolean cancelled) {
			this.lease = lease;
			this.cancelled = cancelled;
		}

		@Override
		public long durationMillis() {
			return lease.durationMillis();
		}

		@Override
		public void reportProgress(int percent) {
			try {
				Response response = post(taskPath(lease, "progress"), new ProgressUpdate(lease.leaseToken(), percent));
				if (response.status != 204) {
					cancelled.set(true);
					throw new IllegalStateException("Task lease is no longer valid");
				}
			} catch (IOException failure) {
				cancelled.set(true);
				throw new IllegalStateException("Could not renew task lease", failure);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				cancelled.set(true);
				throw new IllegalStateException("Task was interrupted", interrupted);
			}
		}

		@Override
		public boolean isCancellationRequested() {
			return cancelled.get();
		}
	}

	private record Response(int status, byte[] body) {
		private Response {
			body = body.clone();
		}

		@Override
		public byte[] body() {
			return body.clone();
		}
	}
}
