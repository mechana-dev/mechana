package dev.mechana.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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
import java.util.HexFormat;
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

	private final ObjectMapper json = new ObjectMapper();
	private final Scheduler scheduler;
	private final HttpServer http;
	private final Path pluginJar;
	private final PluginLocation pluginLocation;
	private final ConcurrentMap<String, Long> workerLastSeen = new ConcurrentHashMap<>();
	private final ScheduledExecutorService leaseReaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "mechana-lease-reaper");
		thread.setDaemon(true);
		return thread;
	});

	public MechanaServer(int port, String publicUrl, Path pluginJar, long leaseMillis) throws IOException {
		this.scheduler = new Scheduler(leaseMillis);
		this.pluginJar = pluginJar.toAbsolutePath().normalize();
		if (!Files.isRegularFile(this.pluginJar)) {
			throw new IllegalArgumentException("Plugin JAR does not exist: " + this.pluginJar);
		}
		this.pluginLocation = new PluginLocation(stripTrailingSlash(publicUrl) + PLUGIN_PATH, sha256(this.pluginJar));
		this.http = HttpServer.create(new InetSocketAddress(port), 0);
		this.http.createContext("/api/jobs", new JobsHandler());
		this.http.createContext("/api/workers", new WorkersHandler());
		this.http.createContext(PLUGIN_PATH, this::servePlugin);
		this.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
	}

	public void start() {
		http.start();
		leaseReaper.scheduleAtFixedRate(this::reapExpiredWorkersAndLeases, scheduler.leaseMillis(),
				Math.max(250, scheduler.leaseMillis() / 2), TimeUnit.MILLISECONDS);
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
				if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/api/jobs/")) {
					String jobId = path.substring("/api/jobs/".length());
					JobStatusResponse status = scheduler.status(jobId);
					System.out.printf("Client %s requested status for job %s%n", exchange.getRemoteAddress(), jobId);
					logJobStatus(status);
					sendJson(exchange, 200, status);
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

	private final class WorkersHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try {
				String path = exchange.getRequestURI().getPath();
				if ("POST".equals(exchange.getRequestMethod()) && "/api/workers/register".equals(path)) {
					WorkerRegistration registration = read(exchange, WorkerRegistration.class);
					markWorkerSeen(registration.workerId(), registration.supportedPlugins());
					scheduler.register(registration.workerId(), registration.supportedPlugins());
					sendJson(exchange, 200, new WorkerRegistrationResponse(scheduler.leaseMillis()));
					return;
				}
				String[] segments = path.split("/");
				if (segments.length == 5 && "lease".equals(segments[4])) {
					lease(exchange, segments[3]);
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
			markWorkerSeen(workerId, request.supportedPlugins());
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

		private void updateTask(HttpExchange exchange, String workerId, String taskId, String action)
				throws IOException {
			requirePost(exchange);
			markWorkerSeen(workerId, Set.of());
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

	private void markWorkerSeen(String workerId, Set<String> supportedPlugins) {
		Long previous = workerLastSeen.put(workerId, System.currentTimeMillis());
		if (previous == null) {
			System.out.printf("Worker %s connected; capabilities=%s%n", workerId, supportedPlugins);
		}
	}

	private void reapExpiredWorkersAndLeases() {
		int expiredTasks = scheduler.expireLeases();
		if (expiredTasks > 0) {
			System.out.printf("Requeued %d task(s) after worker lease expiration%n", expiredTasks);
		}
		long disconnectedBefore = System.currentTimeMillis() - scheduler.leaseMillis() * 2;
		workerLastSeen.forEach((workerId, lastSeen) -> {
			if (lastSeen < disconnectedBefore && workerLastSeen.remove(workerId, lastSeen)) {
				System.out.printf("Worker %s disconnected (heartbeat timeout)%n", workerId);
			}
		});
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
}
