package dev.mechana.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.mechana.coordinator.InMemoryJobMonitor;
import dev.mechana.coordinator.Scheduler;
import dev.mechana.coordinator.Scheduler.PluginLocation;
import dev.mechana.protocol.Messages.JobStatusResponse;
import dev.mechana.protocol.Messages.JobSubmission;
import dev.mechana.protocol.Messages.JobSubmitRequest;
import dev.mechana.protocol.Messages.LeaseRequest;
import dev.mechana.protocol.Messages.ProgressUpdate;
import dev.mechana.protocol.Messages.TaskCompletion;
import dev.mechana.protocol.Messages.TaskFailure;
import dev.mechana.protocol.Messages.TaskLease;
import dev.mechana.protocol.Messages.WorkerRegistration;
import dev.mechana.protocol.Messages.WorkerRegistrationResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** HTTP adapter around the scheduler and authoritative plugin registry. */
public final class MechanaServer implements AutoCloseable {

	private static final String PLUGIN_PATH = "/api/plugins/sleep/1.0.0";
	private static final long WORKER_TIMEOUT_MILLIS = 3_000;
	private static final long HEARTBEAT_CHECK_MILLIS = 1_000;

	private final ObjectMapper json = new ObjectMapper();
	private final Instant startedAt = Instant.now();
	private final long processId = ProcessHandle.current().pid();
	private final Scheduler scheduler;
	private final CompletedJobStore completedJobs;
	private final HttpServer http;
	private final Path pluginJar;
	private final PluginLocation pluginLocation;
	private final ConcurrentMap<String, WorkerPresence> workers = new ConcurrentHashMap<>();
	private final ScheduledExecutorService leaseReaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "mechana-lease-reaper");
		thread.setDaemon(true);
		return thread;
	});

	public MechanaServer(int port, String publicUrl, Path pluginJar, long leaseMillis) throws IOException {
		this(port, publicUrl, pluginJar, leaseMillis, Path.of(".mechana", "server"));
	}

	public MechanaServer(int port, String publicUrl, Path pluginJar, long leaseMillis, Path dataDirectory)
			throws IOException {
		this.scheduler = new Scheduler(leaseMillis);
		this.completedJobs = new CompletedJobStore(dataDirectory, json);
		this.pluginJar = pluginJar.toAbsolutePath().normalize();
		if (!Files.isRegularFile(this.pluginJar)) {
			throw new IllegalArgumentException("Plugin JAR does not exist: " + this.pluginJar);
		}
		this.pluginLocation = new PluginLocation(stripTrailingSlash(publicUrl) + PLUGIN_PATH, sha256(this.pluginJar));
		this.http = HttpServer.create(new InetSocketAddress(port), 0);
		this.http.createContext("/api/jobs", new JobsHandler());
		this.http.createContext("/api/dashboard", this::serveServerDashboardStatus);
		this.http.createContext("/dashboard", this::serveDashboard);
		this.http.createContext("/api/workers", new WorkersHandler());
		this.http.createContext(PLUGIN_PATH, this::servePlugin);
		this.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
	}

	public void start() {
		http.start();
		leaseReaper.scheduleAtFixedRate(this::reapExpiredWorkersAndLeases, HEARTBEAT_CHECK_MILLIS,
				HEARTBEAT_CHECK_MILLIS, TimeUnit.MILLISECONDS);
	}

	public int port() {
		return http.getAddress().getPort();
	}

	@Override
	public void close() {
		leaseReaper.shutdownNow();
		http.stop(0);
	}

	private final class JobsHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try {
				String path = exchange.getRequestURI().getPath();
				if ("POST".equals(exchange.getRequestMethod()) && "/api/jobs".equals(path)) {
					JobSubmitRequest request = read(exchange, JobSubmitRequest.class);
					String jobId = scheduler.submit(request.taskCount(), request.durationMillis());
					System.out.printf("Client %s submitted job %s: tasks=%d, duration=%dms%n",
							exchange.getRemoteAddress(), jobId, request.taskCount(), request.durationMillis());
					logJobStatus(scheduler.status(jobId));
					sendJson(exchange, 202, new JobSubmission(jobId));
					return;
				}
				if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/api/jobs/")
						&& path.endsWith("/abort")) {
					requireLoopback(exchange);
					String jobId = path.substring("/api/jobs/".length(), path.length() - "/abort".length());
					if (!scheduler.abort(jobId)) {
						sendText(exchange, 409, "Job is already complete: " + jobId);
						return;
					}
					archiveIfTerminal(jobId);
					System.out.printf("Aborted job %s from dashboard%n", jobId);
					sendEmpty(exchange, 204);
					return;
				}
				if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/api/jobs/")) {
					if (path.endsWith("/dashboard")) {
						if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
							sendText(exchange, 403, "Dashboard access is loopback-only");
							return;
						}
						String jobId = path.substring("/api/jobs/".length(), path.length() - "/dashboard".length());
						exchange.getResponseHeaders().set("Cache-Control", "no-store");
						sendJobDashboard(exchange, jobId);
						return;
					}
					String artifactMarker = "/artifacts/";
					int artifactAt = path.indexOf(artifactMarker, "/api/jobs/".length());
					if (artifactAt >= 0) {
						requireLoopback(exchange);
						String jobId = path.substring("/api/jobs/".length(), artifactAt);
						serveArtifact(exchange,
								completedJobs.artifact(jobId, path.substring(artifactAt + artifactMarker.length())));
						return;
					}
					String jobId = path.substring("/api/jobs/".length());
					JobStatusResponse status = scheduler.status(jobId);
					System.out.printf("Client %s requested status for job %s%n", exchange.getRemoteAddress(), jobId);
					logJobStatus(status);
					sendJson(exchange, 200, status);
					return;
				}
				if ("DELETE".equals(exchange.getRequestMethod()) && path.startsWith("/api/jobs/")) {
					requireLoopback(exchange);
					String jobId = path.substring("/api/jobs/".length());
					if (!completedJobs.purge(jobId)) {
						sendText(exchange, 404, "Unknown completed job: " + jobId);
						return;
					}
					scheduler.purgeCompleted(jobId);
					System.out.printf("Purged completed job %s%n", jobId);
					sendEmpty(exchange, 204);
					return;
				}
				sendEmpty(exchange, 404);
			} catch (IllegalArgumentException invalid) {
				sendText(exchange, 400, invalid.getMessage());
			} catch (RuntimeException failure) {
				sendText(exchange, 500, failure.getMessage());
			}
		}
	}

	private void serveDashboard(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		if (!"GET".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 404);
			return;
		}
		if (!isLoopback(exchange)) {
			sendText(exchange, 403, "Dashboard access is loopback-only");
			return;
		}
		if ("/dashboard".equals(path) || "/dashboard/".equals(path)) {
			sendHtml(exchange, ServerDashboard.html("/api/dashboard"));
			return;
		}
		String prefix = "/dashboard/jobs/";
		if (!path.startsWith(prefix) || path.length() == prefix.length()) {
			sendEmpty(exchange, 404);
			return;
		}
		String jobId = path.substring(prefix.length());
		try {
			jobDashboard(jobId);
			sendHtml(exchange, JobDashboardServer.dashboardHtml("/api/jobs/" + jobId + "/dashboard", "/dashboard"));
		} catch (IllegalArgumentException unknown) {
			sendText(exchange, 404, unknown.getMessage());
		}
	}

	private void serveServerDashboardStatus(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 405);
			return;
		}
		if (!isLoopback(exchange)) {
			sendText(exchange, 403, "Dashboard access is loopback-only");
			return;
		}
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		sendJson(exchange, 200, serverDashboardSnapshot());
	}

	private final class WorkersHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try {
				String path = exchange.getRequestURI().getPath();
				if ("POST".equals(exchange.getRequestMethod()) && "/api/workers/register".equals(path)) {
					WorkerRegistration registration = read(exchange, WorkerRegistration.class);
					markWorkerSeen(registration.workerId(), advertisedOrRemote(registration.workerAddress(), exchange),
							registration.supportedPlugins());
					scheduler.register(registration.workerId(), registration.supportedPlugins());
					sendJson(exchange, 200, new WorkerRegistrationResponse(scheduler.leaseMillis()));
					return;
				}
				String[] segments = path.split("/");
				if (segments.length == 5 && "lease".equals(segments[4])) {
					lease(exchange, segments[3]);
					return;
				}
				if (segments.length == 5 && "disconnect".equals(segments[4])) {
					disconnect(exchange, segments[3]);
					return;
				}
				if (segments.length == 7 && "tasks".equals(segments[4])) {
					updateTask(exchange, segments[3], segments[5], segments[6]);
					return;
				}
				sendEmpty(exchange, 404);
			} catch (IllegalArgumentException invalid) {
				sendText(exchange, 400, invalid.getMessage());
			} catch (RuntimeException failure) {
				sendText(exchange, 500, failure.getMessage());
			}
		}

		private void lease(HttpExchange exchange, String workerId) throws IOException {
			requirePost(exchange);
			LeaseRequest request = read(exchange, LeaseRequest.class);
			markWorkerSeen(workerId, advertisedOrRemote(request.workerAddress(), exchange), request.supportedPlugins());
			Optional<TaskLease> lease = scheduler.lease(workerId, request.supportedPlugins(), pluginLocation);
			if (lease.isPresent()) {
				TaskLease assignment = lease.orElseThrow();
				System.out.printf("Assigned job %s task %s to worker %s (attempt %d)%n", assignment.jobId(),
						assignment.taskId(), workerId, assignment.attempt());
				logJobStatus(scheduler.status(assignment.jobId()));
				sendJson(exchange, 200, assignment);
			} else {
				sendEmpty(exchange, 204);
			}
		}

		private void disconnect(HttpExchange exchange, String workerId) throws IOException {
			requirePost(exchange);
			WorkerPresence disconnected = workers.computeIfPresent(workerId, (id,
					worker) -> new WorkerPresence(worker.address(), worker.capabilities(), worker.lastSeenAt(), false));
			if (disconnected != null) {
				System.out.printf("Worker %s disconnected (graceful shutdown)%n", workerId);
			}
			sendEmpty(exchange, 204);
		}

		private void updateTask(HttpExchange exchange, String workerId, String taskId, String action)
				throws IOException {
			requirePost(exchange);
			markWorkerSeen(workerId, null, Set.of());
			boolean accepted = switch (action) {
				case "progress" -> {
					ProgressUpdate update = read(exchange, ProgressUpdate.class);
					yield scheduler.progress(workerId, taskId, update.leaseToken(), update.percent());
				}
				case "complete" -> {
					TaskCompletion completion = read(exchange, TaskCompletion.class);
					yield scheduler.complete(workerId, taskId, completion.leaseToken());
				}
				case "fail" -> {
					TaskFailure failure = read(exchange, TaskFailure.class);
					yield scheduler.fail(workerId, taskId, failure.leaseToken());
				}
				default -> throw new IllegalArgumentException("Unknown task action: " + action);
			};
			if (accepted) {
				JobStatusResponse status = scheduler.statusForTask(taskId);
				archiveIfTerminal(status.jobId());
				System.out.printf("Worker %s reported task %s action=%s%n", workerId, taskId, action);
				logJobStatus(status);
			} else {
				System.out.printf("Rejected stale task update from worker %s for task %s action=%s%n", workerId, taskId,
						action);
			}
			sendEmpty(exchange, accepted ? 204 : 409);
		}
	}

	private void servePlugin(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 405);
			return;
		}
		long size = Files.size(pluginJar);
		exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
		exchange.getResponseHeaders().set("X-Checksum-Sha256", pluginLocation.sha256());
		exchange.sendResponseHeaders(200, size);
		try (var output = exchange.getResponseBody()) {
			Files.copy(pluginJar, output);
		}
	}

	private void sendJobDashboard(HttpExchange exchange, String jobId) throws IOException {
		InMemoryJobMonitor.Snapshot snapshot = jobDashboard(jobId);
		ObjectNode body = json.valueToTree(snapshot);
		List<CompletedJobStore.Artifact> artifacts = completedJobs.find(jobId).isPresent()
				? completedJobs.artifacts(jobId)
				: List.of();
		body.set("artifacts", json.valueToTree(artifacts.stream().map(artifact -> Map.of("name", artifact.name(),
				"size", artifact.size(), "url", "/api/jobs/" + jobId + "/artifacts/" + artifact.name())).toList()));
		body.put("completed", completedJobs.find(jobId).isPresent());
		body.put("abortable", !isTerminal(snapshot.stage()));
		sendJson(exchange, 200, body);
	}

	private InMemoryJobMonitor.Snapshot jobDashboard(String jobId) {
		try {
			return scheduler.dashboard(jobId);
		} catch (IllegalArgumentException unknownLiveJob) {
			return completedJobs.find(jobId).orElseThrow(() -> new IllegalArgumentException("Unknown job: " + jobId));
		}
	}

	private void archiveIfTerminal(String jobId) {
		InMemoryJobMonitor.Snapshot snapshot = scheduler.dashboard(jobId);
		if (!isTerminal(snapshot.stage()))
			return;
		try {
			completedJobs.archive(snapshot);
		} catch (IOException failure) {
			System.err.printf("Could not archive completed job %s: %s%n", jobId, failure.getMessage());
		}
	}

	private static void serveArtifact(HttpExchange exchange, Path artifact) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 405);
			return;
		}
		exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
		exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\""
				+ java.util.Objects.requireNonNull(artifact.getFileName()).toString().replace("\"", "") + "\"");
		exchange.sendResponseHeaders(200, Files.size(artifact));
		try (var output = exchange.getResponseBody()) {
			Files.copy(artifact, output);
		}
	}

	private void markWorkerSeen(String workerId, String workerAddress, Set<String> supportedPlugins) {
		long seenAt = System.currentTimeMillis();
		WorkerPresence previous = workers.get(workerId);
		workers.compute(workerId, (id, worker) -> new WorkerPresence(
				workerAddress == null && worker != null ? worker.address() : workerAddress,
				supportedPlugins.isEmpty() && worker != null ? worker.capabilities() : Set.copyOf(supportedPlugins),
				seenAt, true));
		if (previous == null || !previous.connected()) {
			System.out.printf("Worker %s connected; capabilities=%s%n", workerId, supportedPlugins);
		}
	}

	private void reapExpiredWorkersAndLeases() {
		int expiredTasks = scheduler.expireLeases();
		if (expiredTasks > 0) {
			System.out.printf("Requeued %d task(s) after worker lease expiration%n", expiredTasks);
		}
		long disconnectedBefore = System.currentTimeMillis() - WORKER_TIMEOUT_MILLIS;
		workers.forEach((workerId, worker) -> {
			if (worker.connected() && worker.lastSeenAt() < disconnectedBefore && workers.replace(workerId, worker,
					new WorkerPresence(worker.address(), worker.capabilities(), worker.lastSeenAt(), false))) {
				System.out.printf("Worker %s disconnected (heartbeat timeout)%n", workerId);
			}
		});
	}

	private ServerDashboard.Snapshot serverDashboardSnapshot() {
		Instant now = Instant.now();
		List<dev.mechana.coordinator.InMemoryJobMonitor.Snapshot> liveJobs = scheduler.dashboards();
		for (dev.mechana.coordinator.InMemoryJobMonitor.Snapshot job : liveJobs)
			if (isTerminal(job.stage()))
				archiveIfTerminal(job.jobId());
		List<dev.mechana.coordinator.InMemoryJobMonitor.Snapshot> activeJobItems = liveJobs.stream()
				.filter(job -> !isTerminal(job.stage())).toList();
		List<dev.mechana.coordinator.InMemoryJobMonitor.Snapshot> completedJobItems = completedJobs.snapshots();
		Map<String, String> activeJobsByWorker = new HashMap<>();
		for (dev.mechana.coordinator.InMemoryJobMonitor.Snapshot job : activeJobItems) {
			for (dev.mechana.coordinator.InMemoryJobMonitor.WorkUnitSnapshot workUnit : job.workUnits()) {
				if ("RUNNING".equals(workUnit.state()))
					activeJobsByWorker.put(workUnit.workerAddress(), job.jobId());
			}
		}
		List<ServerDashboard.WorkerSnapshot> workerSnapshots = workers.entrySet().stream()
				.sorted(Comparator.comparing(java.util.Map.Entry::getKey)).map(entry -> {
					String jobId = entry.getValue().connected() ? activeJobsByWorker.get(entry.getKey()) : null;
					String activity = !entry.getValue().connected() ? "OFFLINE" : jobId == null ? "IDLE" : "WORKING";
					return new ServerDashboard.WorkerSnapshot(entry.getKey(), entry.getValue().address(),
							entry.getValue().connected() ? "CONNECTED" : "DISCONNECTED", activity, jobId,
							entry.getValue().capabilities(),
							formatAge(Duration.between(Instant.ofEpochMilli(entry.getValue().lastSeenAt()), now)));
				}).toList();
		int connectedWorkers = (int) workerSnapshots.stream().filter(worker -> "CONNECTED".equals(worker.state()))
				.count();
		ZonedDateTime serverTime = ZonedDateTime.now();
		return new ServerDashboard.Snapshot(processId, serverTime.format(DateTimeFormatter.ISO_LOCAL_DATE),
				serverTime.format(DateTimeFormatter.ofPattern("HH:mm:ss z")),
				formatDuration(Duration.between(startedAt, now)), connectedWorkers, workerSnapshots.size(),
				activeJobItems.size(), completedJobItems.size(), workerSnapshots, activeJobItems, completedJobItems);
	}

	private static boolean isTerminal(String stage) {
		return "SUCCEEDED".equals(stage) || "FAILED".equals(stage) || "CANCELLED".equals(stage);
	}

	private static String formatAge(Duration duration) {
		long seconds = Math.max(0, duration.toSeconds());
		if (seconds < 60)
			return seconds + "s ago";
		return seconds / 60 + "m ago";
	}

	private static String formatDuration(Duration duration) {
		long seconds = Math.max(0, duration.toSeconds());
		long days = seconds / 86_400;
		String clock = "%02d:%02d:%02d".formatted(seconds % 86_400 / 3_600, seconds % 3_600 / 60, seconds % 60);
		return days == 0 ? clock : days + "d " + clock;
	}

	private static String advertisedOrRemote(String advertised, HttpExchange exchange) {
		return advertised == null || advertised.isBlank()
				? exchange.getRemoteAddress().getAddress().getHostAddress()
				: advertised;
	}

	private static boolean isLoopback(HttpExchange exchange) {
		return exchange.getRemoteAddress().getAddress().isLoopbackAddress();
	}

	private static void requireLoopback(HttpExchange exchange) {
		if (!isLoopback(exchange))
			throw new IllegalArgumentException("Dashboard access is loopback-only");
	}

	private static void logJobStatus(JobStatusResponse status) {
		long queued = status.tasks().stream().filter(task -> "QUEUED".equals(task.state())).count();
		long running = status.tasks().stream().filter(task -> "RUNNING".equals(task.state())).count();
		long succeeded = status.tasks().stream().filter(task -> "SUCCEEDED".equals(task.state())).count();
		System.out.printf("Job %s: state=%s, progress=%d%%, queued=%d, running=%d, succeeded=%d/%d%n", status.jobId(),
				status.state(), status.progress(), queued, running, succeeded, status.tasks().size());
	}

	private <T> T read(HttpExchange exchange, Class<T> type) throws IOException {
		try (InputStream input = exchange.getRequestBody()) {
			return json.readValue(input, type);
		}
	}

	private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] content = json.writeValueAsBytes(body);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, content.length);
		try (var output = exchange.getResponseBody()) {
			output.write(content);
		}
	}

	private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
		byte[] content = body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, content.length);
		try (var output = exchange.getResponseBody()) {
			output.write(content);
		}
	}

	private static void sendHtml(HttpExchange exchange, String body) throws IOException {
		byte[] content = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(200, content.length);
		try (var output = exchange.getResponseBody()) {
			output.write(content);
		}
	}

	private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
		exchange.sendResponseHeaders(status, -1);
		exchange.close();
	}

	private static void requirePost(HttpExchange exchange) {
		if (!"POST".equals(exchange.getRequestMethod())) {
			throw new IllegalArgumentException("POST required");
		}
	}

	private static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(file)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = input.read(buffer)) >= 0) {
					digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	private record WorkerPresence(String address, Set<String> capabilities, long lastSeenAt, boolean connected) {
		private WorkerPresence {
			address = address == null || address.isBlank() ? "unknown" : address;
			capabilities = Set.copyOf(capabilities);
		}
	}
}
