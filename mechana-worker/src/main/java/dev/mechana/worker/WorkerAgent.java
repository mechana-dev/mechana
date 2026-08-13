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
import dev.mechana.runtime.plugin.LinuxSandbox;
import dev.mechana.runtime.plugin.MacOsSandbox;
import dev.mechana.runtime.plugin.OwnedAttemptWorkspace;
import dev.mechana.runtime.plugin.PluginSandbox;
import dev.mechana.runtime.plugin.PluginRuntimeManager;
import dev.mechana.runtime.plugin.ProcessSandbox;
import dev.mechana.runtime.plugin.SandboxPolicy;
import dev.mechana.runtime.plugin.SandboxRequest;
import dev.mechana.runtime.plugin.SandboxResult;
import dev.mechana.runtime.plugin.SandboxControl;
import dev.mechana.runtime.plugin.TrustMode;
import dev.mechana.runtime.plugin.WindowsSandbox;
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
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pull-based worker that downloads each assigned plugin into ephemeral storage.
 */
public final class WorkerAgent {
	private static final int DOWNLOAD_ATTEMPTS = 4;

	private final ObjectMapper json = new ObjectMapper();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final URI server;
	private final String workerId;
	private final String workerAddress;
	private final Set<String> supportedPlugins;
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final AtomicReference<AtomicBoolean> activeCancellation = new AtomicReference<>();
	private final AtomicReference<Thread> activeExecution = new AtomicReference<>();

	public WorkerAgent(URI server, String workerId, Set<String> supportedPlugins) {
		this(server, workerId, localAddress(), supportedPlugins);
	}

	WorkerAgent(URI server, String workerId, String workerAddress, Set<String> supportedPlugins) {
		this.server = URI.create(stripTrailingSlash(Objects.requireNonNull(server, "server").toString()));
		this.workerId = Objects.requireNonNull(workerId, "workerId");
		this.workerAddress = Objects.requireNonNull(workerAddress, "workerAddress");
		this.supportedPlugins = advertisedCapabilities(supportedPlugins);
		reclaimAbandonedAttempts();
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
		cancelAndAwaitActiveAttempt();
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
		activeCancellation.set(cancelled);
		activeExecution.set(Thread.currentThread());
		AtomicBoolean finished = new AtomicBoolean();
		Thread leaseHeartbeat = startLeaseHeartbeat(lease, cancelled, finished);
		Path pluginFile = null;
		try {
			pluginFile = downloadPlugin(lease);
			long pluginBytes = Files.size(pluginFile);
			if (sandboxedExecution()) {
				executeSandboxed(lease, pluginFile, pluginBytes, cancelled);
			} else
				try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{pluginFile.toUri().toURL()},
						TaskPlugin.class.getClassLoader())) {
					Class<? extends TaskPlugin> pluginType = Class.forName(lease.pluginEntrypoint(), true, loader)
							.asSubclass(TaskPlugin.class);
					TaskPlugin plugin = pluginType.getConstructor().newInstance();
					verifyDescriptor(plugin.descriptor(), lease);
					System.out.printf("Worker %s running task %s%n", workerId, lease.taskId());
					RemoteTaskContext context = new RemoteTaskContext(lease, cancelled);
					plugin.execute(context);
					Response completion = post(taskPath(lease, "complete"), context.completion(pluginBytes));
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
			activeExecution.compareAndSet(Thread.currentThread(), null);
			activeCancellation.compareAndSet(cancelled, null);
		}
	}

	private void executeSandboxed(TaskLease lease, Path downloadedPlugin, long pluginBytes, AtomicBoolean cancelled)
			throws IOException, InterruptedException {
		try (OwnedAttemptWorkspace owned = OwnedAttemptWorkspace.create(sandboxRoot(), lease.jobId(),
				lease.taskId() + "-" + lease.attempt(), workerId)) {
			AttemptWorkspace workspace = owned.workspace();
			RemoteTaskContext context = new RemoteTaskContext(lease, cancelled);
			AtomicReference<Throwable> protocolFailure = new AtomicReference<>();
			AtomicBoolean completed = new AtomicBoolean();
			Path plugin = workspace.input().resolve("plugin.jar");
			Files.copy(downloadedPlugin, plugin);
			Map<String, String> parameters = prepareSandboxParameters(lease, workspace, context);
			HostRequest hostRequest = new HostRequest(plugin.toString(), lease.pluginEntrypoint(), lease.pluginId(),
					lease.pluginVersion(), lease.durationMillis(), parameters, workspace.output().toString());
			Path requestFrame = workspace.input().resolve("request.ndjson");
			Files.writeString(requestFrame, json.writeValueAsString(hostRequest) + System.lineSeparator());
			String hostClasspath = stageHostClasspath(workspace.input().resolve("runtime"));
			Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
			SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, false,
					Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(),
					10L * 1024 * 1024 * 1024, Duration.ofHours(6), sandboxMaxProcesses(lease.pluginId()));
			SandboxRequest request = new SandboxRequest(
					java.util.List.of(javaBinary.toString(), "-Djava.awt.headless=true",
							"-Djava.io.tmpdir=" + workspace.work(), "-cp", hostClasspath,
							"dev.mechana.pluginhost.PluginHostMain"),
					sandboxEnvironment(workspace), workspace, policy, requestFrame,
					line -> handleHostEvent(line, context, completed, protocolFailure),
					sandboxRuntimePaths(lease.pluginId()));
			PluginSandbox platformSandbox = platformSandbox();
			System.out.printf("Worker %s running task %s in %s%n", workerId, lease.taskId(),
					platformSandbox.capabilities(policy).backend());
			SandboxResult result = new PluginRuntimeManager(new ProcessSandbox(), platformSandbox).execute(request,
					cancelled);
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
			Response completion = post(taskPath(lease, "complete"), context.completion(pluginBytes));
			requireStatus(completion, 204);
			System.out.printf("Worker %s finished sandboxed task %s successfully%n", workerId, lease.taskId());
		}
	}

	private static PluginSandbox platformSandbox() throws IOException {
		MacOsSandbox macOs = new MacOsSandbox();
		if (macOs.supportsCurrentHost())
			return macOs;
		LinuxSandbox linux = new LinuxSandbox();
		if (linux.supportsCurrentHost())
			return linux;
		WindowsSandbox windows = new WindowsSandbox();
		if (windows.supportsCurrentHost())
			return windows;
		throw new IOException("No verified OS sandbox backend is available on this host");
	}

	private static Map<String, String> sandboxEnvironment(AttemptWorkspace workspace) {
		Map<String, String> environment = new HashMap<>();
		environment.put("PATH", Path.of(System.getProperty("java.home"), "bin").toString());
		environment.put("HOME", workspace.work().toString());
		environment.put("TMPDIR", workspace.work().toString());
		environment.put("TMP", workspace.work().toString());
		environment.put("TEMP", workspace.work().toString());
		environment.put("USERPROFILE", workspace.work().toString());
		for (String name : java.util.List.of("ALLUSERSPROFILE", "APPDATA", "CommonProgramFiles",
				"CommonProgramFiles(x86)", "CommonProgramW6432", "COMPUTERNAME", "ComSpec", "DriverData",
				"LOCALAPPDATA", "NUMBER_OF_PROCESSORS", "OS", "PATHEXT", "PROCESSOR_ARCHITECTURE",
				"PROCESSOR_IDENTIFIER", "PROCESSOR_LEVEL", "PROCESSOR_REVISION", "ProgramData", "ProgramFiles",
				"ProgramFiles(x86)", "ProgramW6432", "PUBLIC", "SystemDrive", "SystemRoot", "USERDOMAIN", "USERNAME",
				"WINDIR"))
			copyEnvironment(environment, name);
		return Map.copyOf(environment);
	}

	private static void copyEnvironment(Map<String, String> target, String name) {
		String value = System.getenv(name);
		if (value != null && !value.isBlank())
			target.put(name, value);
	}

	private static Set<String> advertisedCapabilities(Set<String> plugins) {
		java.util.HashSet<String> advertised = new java.util.HashSet<>(plugins);
		if (!plugins.isEmpty())
			advertised.add("storage.client-direct-artifacts.v1");
		if (plugins.contains("video-ffmpeg"))
			advertised.add("storage.client-direct-video.v1");
		if (!sandboxedExecution())
			return Set.copyOf(advertised);
		try {
			SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, false, 1, 1, 1, Duration.ofSeconds(1), 1);
			var capabilities = platformSandbox().capabilities(policy);
			advertised.add("sandbox.backend." + capabilities.backend());
			capabilities.enforced().entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey)
					.map(SandboxControl::name).map(String::toLowerCase).map(name -> "sandbox.control." + name)
					.forEach(advertised::add);
			return Set.copyOf(advertised);
		} catch (IOException unavailable) {
			throw new IllegalStateException("Sandboxed worker cannot start without a verified OS backend", unavailable);
		}
	}

	private Map<String, String> prepareSandboxParameters(TaskLease lease, AttemptWorkspace workspace,
			RemoteTaskContext context) throws IOException, InterruptedException {
		Map<String, String> parameters = new HashMap<>(lease.parameters());
		switch (lease.pluginId()) {
			case "video-ffmpeg" -> {
				parameters.put("inputPath",
						stageRemoteInput(parameters.remove("inputUrl"), workspace.input(), "input.mp4", context));
				parameters.put("ffmpegCommand", requiredRuntime("ffmpeg"));
				parameters.put("ffprobeCommand", requiredRuntime("ffprobe"));
			}
			case "ocr-tesseract" -> {
				int pageCount = Integer.parseInt(parameters.get("pageCount"));
				for (int index = 0; index < pageCount; index++)
					parameters.put("pagePath." + index, stageRemoteInput(parameters.remove("pageUrl." + index),
							workspace.input(), "page-%06d.png".formatted(index), context));
				parameters.put("tesseractCommand", requiredRuntime("tesseract"));
			}
			case "blender-render" -> {
				parameters.put("inputPath",
						stageRemoteInput(parameters.remove("inputUrl"), workspace.input(), "scene.blend", context));
				parameters.put("blenderCommand", requiredRuntime("blender"));
			}
			case "audio-convolution-reverb" -> {
				parameters.put("dryPath",
						stageRemoteInput(parameters.remove("dryUrl"), workspace.input(), "dry.wav", context));
				parameters.put("irPath",
						stageRemoteInput(parameters.remove("irUrl"), workspace.input(), "ir.wav", context));
			}
			case "audio-ir-deconvolution" -> {
				parameters.put("sweepPath",
						stageRemoteInput(parameters.remove("sweepUrl"), workspace.input(), "sweep.wav", context));
				parameters.put("recordedReturnPath", stageRemoteInput(parameters.remove("recordedReturnUrl"),
						workspace.input(), "recorded-return.wav", context));
			}
			case "sleep", "fractal-render" -> {
				// Pure-Java plugins need no staged input or native runtime grant.
			}
			default -> throw new IOException("Plugin is not approved for sandboxed execution: " + lease.pluginId());
		}
		return Map.copyOf(parameters);
	}

	private String stageRemoteInput(String url, Path input, String fileName, RemoteTaskContext context)
			throws IOException, InterruptedException {
		if (url == null || url.isBlank())
			throw new IOException("Sandbox input URL is missing");
		Path destination = input.resolve(fileName);
		byte[] bytes = downloadBytes(http, resolveCoordinatorUri(server, url), Duration.ofMinutes(20),
				"Sandbox input download");
		Files.write(destination, bytes);
		context.inputBytes.addAndGet(bytes.length);
		return destination.toAbsolutePath().normalize().toString();
	}

	static byte[] downloadBytes(HttpClient client, URI uri, Duration timeout, String description)
			throws IOException, InterruptedException {
		IOException lastFailure = null;
		for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
			try {
				HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
				HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
				if (response.statusCode() == 200) {
					byte[] body = response.body();
					String expectedHash = response.headers().firstValue("X-Checksum-Sha256").orElse("");
					if (!expectedHash.isBlank() && !sha256(body).equalsIgnoreCase(expectedHash))
						throw new IOException(description + " failed SHA-256 validation");
					long expectedSize = response.headers().firstValueAsLong("Content-Length").orElse(-1);
					if (expectedSize >= 0 && body.length != expectedSize)
						throw new IOException(description + " failed size validation");
					return body;
				}
				if (response.statusCode() < 500 && response.statusCode() != 408 && response.statusCode() != 429)
					throw new IOException(description + " returned HTTP " + response.statusCode());
				lastFailure = new IOException(description + " returned HTTP " + response.statusCode());
			} catch (IOException failure) {
				lastFailure = failure;
			}
			if (attempt < DOWNLOAD_ATTEMPTS)
				Thread.sleep(250L << (attempt - 1));
		}
		throw new IOException(description + " failed after " + DOWNLOAD_ATTEMPTS + " attempts against "
				+ uri.getScheme() + "://" + uri.getAuthority() + ": " + safeMessage(lastFailure), lastFailure);
	}

	static URI resolveCoordinatorUri(URI coordinator, String supplied) {
		URI candidate = URI.create(supplied);
		String host = candidate.getHost();
		if (host == null || !(host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1")))
			return candidate;
		String path = candidate.getRawPath();
		if (candidate.getRawQuery() != null)
			path += "?" + candidate.getRawQuery();
		return coordinator.resolve(path);
	}

	private static String requiredRuntime(String name) throws IOException {
		String configured = System.getProperty("mechana.runtime." + name, "").strip();
		if (configured.isEmpty())
			throw new IOException("Sandboxed " + name + " requires -Dmechana.runtime." + name + "=/absolute/path");
		Path executable = Path.of(configured).toAbsolutePath().normalize();
		if (!Files.isExecutable(executable))
			throw new IOException("Configured sandbox runtime is not executable: " + executable);
		return executable.toString();
	}

	private static List<Path> sandboxRuntimePaths(String pluginId) throws IOException {
		return switch (pluginId) {
			case "video-ffmpeg" -> List.of(requiredRuntimePath("ffmpeg"), requiredRuntimePath("ffprobe"));
			case "ocr-tesseract" -> List.of(requiredRuntimePath("tesseract"));
			case "blender-render" -> List.of(requiredRuntimePath("blender"));
			case "sleep", "fractal-render", "audio-convolution-reverb", "audio-ir-deconvolution" -> List.of();
			default -> throw new IOException("Plugin is not approved for sandboxed execution: " + pluginId);
		};
	}

	private static int sandboxMaxProcesses(String pluginId) throws IOException {
		return switch (pluginId) {
			case "sleep", "fractal-render", "audio-convolution-reverb", "audio-ir-deconvolution" -> 1;
			case "video-ffmpeg", "ocr-tesseract" -> 4;
			case "blender-render" -> 16;
			default -> throw new IOException("Plugin is not approved for sandboxed execution: " + pluginId);
		};
	}

	private static Path requiredRuntimePath(String name) throws IOException {
		return Path.of(requiredRuntime(name)).getParent();
	}

	private static boolean sandboxedExecution() {
		return "sandboxed".equalsIgnoreCase(System.getProperty("mechana.execution.mode", "legacy"));
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

	private void cancelAndAwaitActiveAttempt() throws InterruptedException {
		AtomicBoolean cancellation = activeCancellation.get();
		if (cancellation != null)
			cancellation.set(true);
		Thread execution = activeExecution.get();
		if (execution != null && execution != Thread.currentThread())
			execution.join(10_000);
	}

	private void reclaimAbandonedAttempts() {
		try {
			int reclaimed = OwnedAttemptWorkspace.reclaimAbandoned(sandboxRoot());
			if (reclaimed > 0)
				System.out.printf("Worker %s reclaimed %d abandoned sandbox attempt(s)%n", workerId, reclaimed);
		} catch (IOException failure) {
			System.err.printf("Worker %s could not reclaim abandoned sandbox attempts: %s%n", workerId,
					failure.getMessage());
		}
	}

	private static Path sandboxRoot() {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		String defaultRoot;
		if (os.contains("mac"))
			defaultRoot = "/private/tmp/mechana-sandbox";
		else if (os.contains("windows"))
			defaultRoot = Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "Mechana", "sandbox")
					.toString();
		else
			defaultRoot = Path.of(System.getProperty("java.io.tmpdir"), "mechana-sandbox").toString();
		return Path.of(System.getProperty("mechana.sandbox.root", defaultRoot));
	}

	private Thread startPresenceHeartbeat() {
		return heartbeatThread("mechana-worker-heartbeat-" + workerId, () -> {
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
		return heartbeatThread("mechana-task-heartbeat-" + lease.taskId(), () -> {
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

	static Thread heartbeatThread(String name, Runnable task) {
		Thread heartbeat = Thread.ofPlatform().daemon(true).name(name).unstarted(task);
		heartbeat.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
		heartbeat.start();
		return heartbeat;
	}

	private Path downloadPlugin(TaskLease lease) throws IOException, InterruptedException {
		byte[] plugin = downloadBytes(http, resolveCoordinatorUri(server, lease.pluginUrl()), Duration.ofSeconds(30),
				"Plugin download");
		String actualChecksum = sha256(plugin);
		if (!actualChecksum.equalsIgnoreCase(lease.pluginSha256())) {
			throw new IOException("Plugin checksum did not match assignment");
		}
		Path temporaryJar = Files.createTempFile("mechana-plugin-", ".jar");
		Files.write(temporaryJar, plugin);
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

	static Optional<URI> directArtifactDestination(TaskLease lease, String name) {
		String supplied = lease.parameters().get("artifactUploadUrl");
		if (supplied == null || supplied.isBlank())
			return Optional.empty();
		if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
			throw new IllegalArgumentException("Direct artifact name is unsafe");
		String origin = lease.parameters().getOrDefault("artifactTransferOrigin", lease.parameters().get("inputUrl"));
		URI authority = URI.create(Objects.requireNonNull(origin, "Direct artifact transfer origin"));
		URI output = URI.create(supplied);
		boolean sameOrigin = Set.of("http", "https").contains(output.getScheme())
				&& Objects.equals(authority.getScheme(), output.getScheme())
				&& Objects.equals(authority.getHost(), output.getHost())
				&& effectivePort(authority) == effectivePort(output);
		if (!sameOrigin || output.getUserInfo() != null || output.getQuery() != null || output.getFragment() != null
				|| output.getPath() == null || !output.getPath().contains("/client-artifacts/")
				|| !output.getPath().contains("/outputs/"))
			throw new IllegalArgumentException("Direct artifact destination does not match the authorized origin");
		return Optional.of(output);
	}

	private static int effectivePort(URI uri) {
		if (uri.getPort() >= 0)
			return uri.getPort();
		return "https".equals(uri.getScheme()) ? 443 : 80;
	}

	private final class RemoteTaskContext implements TaskContext {
		private final TaskLease lease;
		private final AtomicBoolean cancelled;
		private final AtomicLong inputBytes = new AtomicLong();
		private final AtomicLong outputBytes = new AtomicLong();

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
				URI destination = directArtifactDestination(lease, name)
						.orElseGet(() -> server.resolve(taskPath(lease, "artifacts/") + name));
				HttpRequest request = HttpRequest.newBuilder(destination).timeout(Duration.ofMinutes(10))
						.header("X-Mechana-Lease", lease.leaseToken()).header("X-Mechana-Artifact-Name", name)
						.PUT(HttpRequest.BodyPublishers.ofFile(file)).build();
				HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
				if (response.statusCode() != 204)
					throw new IOException("Artifact upload returned HTTP " + response.statusCode());
				outputBytes.addAndGet(Files.size(file));
			} catch (IOException failure) {
				throw new IllegalStateException("Could not publish artifact", failure);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Artifact publication was interrupted", interrupted);
			}
		}

		TaskCompletion completion(long pluginBytes) {
			return new TaskCompletion(lease.leaseToken(), inputBytes.get(), outputBytes.get(), pluginBytes);
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
