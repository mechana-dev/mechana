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

package dev.mechana.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mechana.api.PluginDescriptor;
import dev.mechana.api.TaskContext;
import dev.mechana.api.TaskPlugin;
import dev.mechana.pluginhost.HostEvent;
import dev.mechana.pluginhost.HostRequest;
import dev.mechana.protocol.Messages.LeaseRequest;
import dev.mechana.protocol.Messages.ProgressUpdate;
import dev.mechana.protocol.Messages.TaskCompletion;
import dev.mechana.protocol.Messages.TaskFailure;
import dev.mechana.protocol.Messages.TaskHeartbeat;
import dev.mechana.protocol.Messages.TaskLease;
import dev.mechana.protocol.Messages.WorkerRegistration;
import dev.mechana.runtime.plugin.AttemptWorkspace;
import dev.mechana.runtime.plugin.MacOsSandbox;
import dev.mechana.runtime.plugin.PluginRuntimeManager;
import dev.mechana.runtime.plugin.ProcessSandbox;
import dev.mechana.runtime.plugin.SandboxPolicy;
import dev.mechana.runtime.plugin.SandboxRequest;
import dev.mechana.runtime.plugin.SandboxResult;
import dev.mechana.runtime.plugin.TrustMode;
import java.io.IOException;
import java.io.File;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pull-based worker that downloads each assigned plugin into ephemeral storage.
 */
public final class WorkerAgent {

	private final ObjectMapper json = new ObjectMapper();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final URI server;
	private final String workerId;
	private final String workerAddress;
	private final Set<String> supportedPlugins;
	private final AtomicBoolean running = new AtomicBoolean(true);

	public WorkerAgent(URI server, String workerId, Set<String> supportedPlugins) {
		this(server, workerId, localAddress(), supportedPlugins);
	}

	WorkerAgent(URI server, String workerId, String workerAddress, Set<String> supportedPlugins) {
		this.server = URI.create(stripTrailingSlash(Objects.requireNonNull(server, "server").toString()));
		this.workerId = Objects.requireNonNull(workerId, "workerId");
		this.workerAddress = Objects.requireNonNull(workerAddress, "workerAddress");
		this.supportedPlugins = Set.copyOf(supportedPlugins);
	}

	public void runForever() {
		Thread presenceHeartbeat = startPresenceHeartbeat();
		try {
			while (running.get() && !Thread.currentThread().isInterrupted()) {
				try {
					register();
					while (running.get() && !Thread.currentThread().isInterrupted()) {
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
		} finally {
			presenceHeartbeat.interrupt();
		}
	}

	public void disconnect() throws IOException, InterruptedException {
		running.set(false);
		Response response = post("/api/workers/" + workerId + "/disconnect", Set.of());
		requireStatus(response, 204);
		System.out.printf("Worker %s disconnected from server%n", workerId);
	}

	public void register() throws IOException, InterruptedException {
		Response response = post("/api/workers/register",
				new WorkerRegistration(workerId, workerAddress, supportedPlugins));
		requireStatus(response, 200);
	}

	public boolean runOne() throws IOException, InterruptedException {
		Response response = post("/api/workers/" + workerId + "/lease",
				new LeaseRequest(workerAddress, supportedPlugins));
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
		AtomicBoolean cancelled = new AtomicBoolean();
		AtomicBoolean finished = new AtomicBoolean();
		Thread leaseHeartbeat = startLeaseHeartbeat(lease, cancelled, finished);
		Path pluginFile = null;
		try {
			pluginFile = downloadPlugin(lease);
			if ("fractal-render".equals(lease.pluginId())) {
				executeSandboxed(lease, pluginFile, cancelled);
			} else
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
				}
		} catch (IOException | ReflectiveOperationException | RuntimeException
				| dev.mechana.api.PluginExecutionException failure) {
			cancelled.set(true);
			try {
				post(taskPath(lease, "fail"), new TaskFailure(lease.leaseToken(), safeMessage(failure)));
			} catch (IOException ignored) {
				// The server will reclaim the task when its lease expires.
			}
			System.err.printf("Worker %s finished task %s with failure: %s%n", workerId, lease.taskId(),
					safeMessage(failure));
		} finally {
			finished.set(true);
			leaseHeartbeat.interrupt();
			if (pluginFile != null)
				Files.deleteIfExists(pluginFile);
		}
	}

	private void executeSandboxed(TaskLease lease, Path downloadedPlugin, AtomicBoolean cancelled)
			throws IOException, InterruptedException {
		Path sandboxRoot = Path.of(System.getProperty("mechana.sandbox.root", "/private/tmp/mechana-sandbox"));
		AttemptWorkspace workspace = AttemptWorkspace.create(sandboxRoot, lease.jobId(),
				lease.taskId() + "-" + lease.attempt());
		RemoteTaskContext context = new RemoteTaskContext(lease, cancelled);
		AtomicReference<Throwable> protocolFailure = new AtomicReference<>();
		AtomicBoolean completed = new AtomicBoolean();
		try {
			Path plugin = workspace.input().resolve("plugin.jar");
			Files.copy(downloadedPlugin, plugin);
			HostRequest hostRequest = new HostRequest(plugin.toString(), lease.pluginEntrypoint(), lease.pluginId(),
					lease.pluginVersion(), lease.durationMillis(), lease.parameters(), workspace.output().toString());
			Path requestFrame = workspace.input().resolve("request.ndjson");
			Files.writeString(requestFrame, json.writeValueAsString(hostRequest) + System.lineSeparator());
			String hostClasspath = stageHostClasspath(workspace.input().resolve("runtime"));
			Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
			SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, false,
					Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(),
					10L * 1024 * 1024 * 1024, Duration.ofHours(6), 1);
			SandboxRequest request = new SandboxRequest(
					java.util.List.of(javaBinary.toString(), "-Djava.awt.headless=true",
							"-Djava.io.tmpdir=" + workspace.work(), "-cp", hostClasspath,
							"dev.mechana.pluginhost.PluginHostMain"),
					java.util.Map.of("PATH", "/usr/bin:/bin"), workspace, policy, requestFrame,
					line -> handleHostEvent(line, context, completed, protocolFailure));
			MacOsSandbox macOs = new MacOsSandbox();
			if (!macOs.supportsCurrentHost())
				throw new IOException("SANDBOXED fractal execution currently requires the macOS backend");
			System.out.printf("Worker %s running task %s in %s%n", workerId, lease.taskId(),
					macOs.capabilities(policy).backend());
			SandboxResult result = new PluginRuntimeManager(new ProcessSandbox(), macOs).execute(request, cancelled);
			if (protocolFailure.get() != null)
				throw new IOException(
						"Plugin-host protocol failed: " + safeMessage(protocolFailure.get()) + diagnostic(result),
						protocolFailure.get());
			if (result.timedOut())
				throw new IOException("Sandboxed plugin timed out");
			if (result.cancelled())
				throw new IOException("Sandboxed plugin was cancelled");
			if (result.exitCode() != 0 || !completed.get())
				throw new IOException(
						"Sandboxed plugin host exited with code " + result.exitCode() + diagnostic(result));
			Response completion = post(taskPath(lease, "complete"), new TaskCompletion(lease.leaseToken()));
			requireStatus(completion, 204);
			System.out.printf("Worker %s finished sandboxed task %s successfully%n", workerId, lease.taskId());
		} finally {
			deleteTree(workspace.root());
		}
	}

	private static String stageHostClasspath(Path runtimeDirectory) throws IOException {
		Files.createDirectories(runtimeDirectory);
		String[] entries = System.getProperty("java.class.path")
				.split(java.util.regex.Pattern.quote(File.pathSeparator));
		java.util.List<String> staged = new java.util.ArrayList<>();
		for (int index = 0; index < entries.length; index++) {
			Path source = Path.of(entries[index]).toAbsolutePath().normalize();
			Path target = runtimeDirectory.resolve(index + "-" + source.getFileName());
			if (Files.isDirectory(source))
				copyTree(source, target);
			else
				Files.copy(source, target);
			staged.add(target.toString());
		}
		return String.join(File.pathSeparator, staged);
	}

	private static void copyTree(Path source, Path target) throws IOException {
		try (var paths = Files.walk(source)) {
			for (Path path : paths.toList()) {
				Path destination = target.resolve(source.relativize(path));
				if (Files.isDirectory(path))
					Files.createDirectories(destination);
				else
					Files.copy(path, destination);
			}
		}
	}

	private void handleHostEvent(String line, RemoteTaskContext context, AtomicBoolean completed,
			AtomicReference<Throwable> failure) {
		try {
			HostEvent event = json.readValue(line, HostEvent.class);
			switch (event.type()) {
				case "progress" -> context.reportProgress(event.progress());
				case "artifact" ->
					context.publishArtifact(event.details().get("name"), Path.of(event.details().get("path")));
				case "completed" -> completed.set(true);
				case "failed" -> failure.compareAndSet(null, new IOException(event.message()));
				default -> failure.compareAndSet(null, new IOException("Unknown plugin-host event: " + event.type()));
			}
		} catch (Throwable eventFailure) {
			failure.compareAndSet(null, eventFailure);
		}
	}

	private static String diagnostic(SandboxResult result) {
		try {
			String stderr = Files.readString(result.stderr()).trim();
			return stderr.isEmpty() ? "" : ": " + stderr;
		} catch (IOException ignored) {
			return "";
		}
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root))
			return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}

	private Thread startPresenceHeartbeat() {
		return Thread.ofVirtual().name("mechana-worker-heartbeat-" + workerId).start(() -> {
			while (running.get() && !Thread.currentThread().isInterrupted()) {
				try {
					post("/api/workers/" + workerId + "/heartbeat", new LeaseRequest(workerAddress, supportedPlugins));
				} catch (IOException ignored) {
					// The registration/lease loop reports connectivity failures.
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				sleep(3_000);
			}
		});
	}

	private Thread startLeaseHeartbeat(TaskLease lease, AtomicBoolean cancelled, AtomicBoolean finished) {
		long intervalMillis = Math.max(250, Math.min(1_000, lease.leaseMillis() / 3));
		return Thread.ofVirtual().name("mechana-task-heartbeat-" + lease.taskId()).start(() -> {
			while (!finished.get() && !Thread.currentThread().isInterrupted()) {
				try {
					Response response = post(taskPath(lease, "heartbeat"), new TaskHeartbeat(lease.leaseToken()));
					if (response.status != 204) {
						cancelled.set(true);
						return;
					}
				} catch (IOException ignored) {
					// A later heartbeat can still renew the lease before it expires.
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
				sleep(intervalMillis);
			}
		});
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

	private static String localAddress() {
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces.hasMoreElements()) {
				NetworkInterface network = interfaces.nextElement();
				if (!network.isUp() || network.isLoopback())
					continue;
				Enumeration<InetAddress> addresses = network.getInetAddresses();
				while (addresses.hasMoreElements()) {
					InetAddress address = addresses.nextElement();
					if (address instanceof Inet4Address && address.isSiteLocalAddress())
						return address.getHostAddress();
				}
			}
			return InetAddress.getLocalHost().getHostAddress();
		} catch (IOException failure) {
			return "unknown";
		}
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
		public java.util.Map<String, String> parameters() {
			return lease.parameters();
		}

		@Override
		public void publishArtifact(String name, Path file) {
			try {
				HttpRequest request = HttpRequest.newBuilder(server.resolve(taskPath(lease, "artifacts/") + name))
						.timeout(Duration.ofMinutes(10)).header("X-Mechana-Lease", lease.leaseToken())
						.PUT(HttpRequest.BodyPublishers.ofFile(file)).build();
				HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
				if (response.statusCode() != 204)
					throw new IOException("Artifact upload returned HTTP " + response.statusCode());
			} catch (IOException failure) {
				throw new IllegalStateException("Could not publish artifact", failure);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Artifact publication was interrupted", interrupted);
			}
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
