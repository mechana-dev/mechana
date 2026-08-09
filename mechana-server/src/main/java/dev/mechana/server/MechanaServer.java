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

package dev.mechana.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.mechana.coordinator.InMemoryJobMonitor;
import dev.mechana.coordinator.Scheduler;
import dev.mechana.coordinator.Scheduler.PluginLocation;
import dev.mechana.coordinator.Scheduler.WorkSpec;
import dev.mechana.api.ArtifactStore;
import dev.mechana.protocol.Messages.JobStatusResponse;
import dev.mechana.protocol.Messages.ArtifactReference;
import dev.mechana.protocol.Messages.ClientAssemblyCompletion;
import dev.mechana.protocol.Messages.VideoAssemblyManifest;
import dev.mechana.protocol.Messages.LauncherJob;
import dev.mechana.protocol.Messages.JobSubmission;
import dev.mechana.protocol.Messages.JobSubmitRequest;
import dev.mechana.protocol.Messages.FractalJobSubmitRequest;
import dev.mechana.protocol.Messages.LeaseRequest;
import dev.mechana.protocol.Messages.OcrJobSubmitRequest;
import dev.mechana.protocol.Messages.ProgressUpdate;
import dev.mechana.protocol.Messages.TaskCompletion;
import dev.mechana.protocol.Messages.TaskFailure;
import dev.mechana.protocol.Messages.TaskHeartbeat;
import dev.mechana.protocol.Messages.TaskLease;
import dev.mechana.protocol.Messages.WorkerRegistration;
import dev.mechana.protocol.Messages.WorkerRegistrationResponse;
import dev.mechana.protocol.Messages.VideoJobSubmitRequest;
import dev.mechana.protocol.Messages.BlenderJobSubmitRequest;
import dev.mechana.plugins.blender.BlenderMovieAssembler;
import dev.mechana.plugins.blender.FramePlanner;
import dev.mechana.plugins.fractal.FractalCollectionAssembler;
import dev.mechana.plugins.ocr.OcrMarkdownAssembler;
import dev.mechana.plugins.video.CancellationToken;
import dev.mechana.plugins.video.ExternalProcessRunner;
import dev.mechana.plugins.video.FfmpegCommands;
import dev.mechana.plugins.video.FinalValidator;
import dev.mechana.plugins.video.MediaProbe;
import dev.mechana.plugins.video.VideoAssembler;
import dev.mechana.plugins.video.VideoTypes;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** HTTP adapter around the scheduler and authoritative plugin registry. */
public final class MechanaServer implements AutoCloseable {

	private static final String PLUGIN_PATH = "/api/plugins/sleep/1.0.0";
	private static final String VIDEO_PLUGIN_PATH = "/api/plugins/video-ffmpeg/1.0.0";
	private static final String FRACTAL_PLUGIN_PATH = "/api/plugins/fractal-render/1.0.0";
	private static final String OCR_PLUGIN_PATH = "/api/plugins/ocr-tesseract/1.0.0";
	private static final String BLENDER_PLUGIN_PATH = "/api/plugins/blender-render/1.0.0";
	private static final String FRACTAL_PLUGIN_ID = "fractal-render";
	private static final String FRACTAL_PLUGIN_VERSION = "1.0.0";
	private static final String FRACTAL_PLUGIN_ENTRYPOINT = "dev.mechana.plugins.fractal.FractalTaskPlugin";
	private static final String OCR_PLUGIN_ID = "ocr-tesseract";
	private static final String OCR_PLUGIN_VERSION = "1.0.0";
	private static final String OCR_PLUGIN_ENTRYPOINT = "dev.mechana.plugins.ocr.TesseractOcrPlugin";
	private static final String BLENDER_PLUGIN_ID = "blender-render";
	private static final String BLENDER_PLUGIN_VERSION = "1.0.0";
	private static final String BLENDER_PLUGIN_ENTRYPOINT = "dev.mechana.plugins.blender.BlenderRenderPlugin";
	private static final long WORKER_TIMEOUT_MILLIS = 15_000;
	private static final long WORKER_RETENTION_MILLIS = 10_000;
	private static final long HEARTBEAT_CHECK_MILLIS = 1_000;

	private final ObjectMapper json = new ObjectMapper();
	private final Instant startedAt = Instant.now();
	private final long processId = ProcessHandle.current().pid();
	private final Scheduler scheduler;
	private final CompletedJobStore completedJobs;
	private final ArtifactStoreRegistry artifactStores;
	private final ArtifactStore defaultArtifactStore;
	private final HttpServer http;
	private final Path pluginJar;
	private final PluginLocation pluginLocation;
	private final Path videoPluginJar;
	private final PluginLocation videoPluginLocation;
	private final Path fractalPluginJar;
	private final PluginLocation fractalPluginLocation;
	private final Path ocrPluginJar;
	private final PluginLocation ocrPluginLocation;
	private final Path blenderPluginJar;
	private final PluginLocation blenderPluginLocation;
	private final Path workRoot;
	private final String publicUrl;
	private final ConcurrentMap<String, dev.mechana.api.ArtifactReference> videoInputs = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, dev.mechana.api.ArtifactReference> clientVideoUploads = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, VideoJob> videoJobs = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, FractalJob> fractalJobs = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, dev.mechana.api.ArtifactReference> ocrInputs = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, OcrJob> ocrJobs = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, dev.mechana.api.ArtifactReference> blenderInputs = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, BlenderJob> blenderJobs = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, WorkerPresence> workers = new ConcurrentHashMap<>();
	private volatile Runnable restartAction;
	private volatile Runnable stopAction;
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
		this(port, publicUrl, pluginJar, pluginJar, leaseMillis, dataDirectory);
	}

	public MechanaServer(int port, String publicUrl, Path pluginJar, Path videoPluginJar, long leaseMillis,
			Path dataDirectory) throws IOException {
		this(port, publicUrl, pluginJar, videoPluginJar, videoPluginJar, leaseMillis, dataDirectory);
	}

	public MechanaServer(int port, String publicUrl, Path pluginJar, Path videoPluginJar, Path fractalPluginJar,
			long leaseMillis, Path dataDirectory) throws IOException {
		this(port, publicUrl, pluginJar, videoPluginJar, fractalPluginJar, fractalPluginJar, leaseMillis,
				dataDirectory);
	}

	public MechanaServer(int port, String publicUrl, Path pluginJar, Path videoPluginJar, Path fractalPluginJar,
			Path ocrPluginJar, long leaseMillis, Path dataDirectory) throws IOException {
		this(port, publicUrl, pluginJar, videoPluginJar, fractalPluginJar, ocrPluginJar, ocrPluginJar, leaseMillis,
				dataDirectory);
	}

	public MechanaServer(int port, String publicUrl, Path pluginJar, Path videoPluginJar, Path fractalPluginJar,
			Path ocrPluginJar, Path blenderPluginJar, long leaseMillis, Path dataDirectory) throws IOException {
		this.scheduler = new Scheduler(leaseMillis);
		this.artifactStores = new ArtifactStoreRegistry().register(new ServerLocalArtifactStore(dataDirectory));
		this.defaultArtifactStore = artifactStores
				.require(dev.mechana.api.StorageSelection.defaults().inputProviderId());
		this.completedJobs = new CompletedJobStore(dataDirectory, json, defaultArtifactStore);
		this.workRoot = dataDirectory.toAbsolutePath().normalize().resolve("work");
		Files.createDirectories(workRoot);
		this.publicUrl = stripTrailingSlash(publicUrl);
		this.pluginJar = pluginJar.toAbsolutePath().normalize();
		if (!Files.isRegularFile(this.pluginJar)) {
			throw new IllegalArgumentException("Plugin JAR does not exist: " + this.pluginJar);
		}
		this.pluginLocation = new PluginLocation(this.publicUrl + PLUGIN_PATH, sha256(this.pluginJar));
		this.videoPluginJar = videoPluginJar.toAbsolutePath().normalize();
		this.videoPluginLocation = new PluginLocation(this.publicUrl + VIDEO_PLUGIN_PATH, sha256(this.videoPluginJar));
		this.fractalPluginJar = fractalPluginJar.toAbsolutePath().normalize();
		this.fractalPluginLocation = new PluginLocation(this.publicUrl + FRACTAL_PLUGIN_PATH,
				sha256(this.fractalPluginJar));
		this.ocrPluginJar = ocrPluginJar.toAbsolutePath().normalize();
		this.ocrPluginLocation = new PluginLocation(this.publicUrl + OCR_PLUGIN_PATH, sha256(this.ocrPluginJar));
		this.blenderPluginJar = blenderPluginJar.toAbsolutePath().normalize();
		this.blenderPluginLocation = new PluginLocation(this.publicUrl + BLENDER_PLUGIN_PATH,
				sha256(this.blenderPluginJar));
		this.http = HttpServer.create(new InetSocketAddress(port), 0);
		this.http.createContext("/api/jobs", new JobsHandler());
		this.http.createContext("/api/client/capabilities", this::serveLauncherCapabilities);
		this.http.createContext("/api/client/jobs", this::serveLauncherJobs);
		this.http.createContext("/api/dashboard", this::serveServerDashboardStatus);
		this.http.createContext("/api/server/restart", this::restartServer);
		this.http.createContext("/api/server/stop", this::stopServer);
		this.http.createContext("/dashboard", this::serveDashboard);
		this.http.createContext("/api/workers", new WorkersHandler());
		this.http.createContext(PLUGIN_PATH, exchange -> servePlugin(exchange, this.pluginJar, this.pluginLocation));
		this.http.createContext(VIDEO_PLUGIN_PATH,
				exchange -> servePlugin(exchange, this.videoPluginJar, this.videoPluginLocation));
		this.http.createContext(FRACTAL_PLUGIN_PATH,
				exchange -> servePlugin(exchange, this.fractalPluginJar, this.fractalPluginLocation));
		this.http.createContext(OCR_PLUGIN_PATH,
				exchange -> servePlugin(exchange, this.ocrPluginJar, this.ocrPluginLocation));
		this.http.createContext(BLENDER_PLUGIN_PATH,
				exchange -> servePlugin(exchange, this.blenderPluginJar, this.blenderPluginLocation));
		this.http.createContext("/api/video-inputs", this::serveVideoInput);
		this.http.createContext("/api/client/video-inputs", this::receiveClientVideoInput);
		this.http.createContext("/api/ocr-inputs", this::serveOcrInput);
		this.http.createContext("/api/blender-inputs", this::serveBlenderInput);
		this.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
	}

	private void serveLauncherCapabilities(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 405);
			return;
		}
		Map<String, Integer> counts = new HashMap<>();
		workers.values().stream().filter(WorkerPresence::connected).flatMap(worker -> worker.capabilities().stream())
				.forEach(capability -> counts.merge(capability, 1, Integer::sum));
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		sendJson(exchange, 200, JobLauncherCatalog.available(counts));
	}

	private void serveLauncherJobs(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 405);
			return;
		}
		List<LauncherJob> active = scheduler.dashboards().stream().filter(job -> !isTerminal(job.stage()))
				.map(job -> launcherJob(job, false)).toList();
		List<LauncherJob> completed = completedJobs.snapshots().stream().map(job -> launcherJob(job, true)).toList();
		List<LauncherJob> result = new java.util.ArrayList<>(active);
		result.addAll(completed);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		sendJson(exchange, 200, result);
	}

	private LauncherJob launcherJob(InMemoryJobMonitor.Snapshot job, boolean completed) {
		List<String> assignments = job.workUnits().stream().filter(unit -> unit.workerAddress() != null)
				.map(unit -> unit.workerAddress()).distinct().sorted().toList();
		List<ArtifactReference> artifacts = List.of();
		if (completed) {
			try {
				artifacts = completedJobs.artifacts(job.jobId()).stream()
						.map(artifact -> new ArtifactReference(artifact.provider(), artifact.key(), artifact.size(),
								"server-local".equals(artifact.provider())
										? "/api/jobs/" + job.jobId() + "/artifacts/" + artifact.name()
										: "",
								"client-local".equals(artifact.provider()), artifact.sha256()))
						.toList();
			} catch (IOException failure) {
				throw new java.io.UncheckedIOException(failure);
			}
		}
		return new LauncherJob(job.jobId(), job.plugin(), job.stage(), job.progress(), job.completedAt(),
				job.error() == null ? job.elapsed() : job.error(), assignments, artifacts, completed);
	}

	public void start() {
		http.start();
		leaseReaper.scheduleAtFixedRate(this::reapExpiredWorkersAndLeases, HEARTBEAT_CHECK_MILLIS,
				HEARTBEAT_CHECK_MILLIS, TimeUnit.MILLISECONDS);
	}

	public int port() {
		return http.getAddress().getPort();
	}

	void onRestart(Runnable action) {
		this.restartAction = action;
	}

	void onStop(Runnable action) {
		this.stopAction = action;
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
				if ("POST".equals(exchange.getRequestMethod()) && "/api/jobs/video".equals(path)) {
					VideoJobSubmitRequest request = read(exchange, VideoJobSubmitRequest.class);
					if ("server-local".equals(request.storageProvider()))
						requireLoopback(exchange);
					String jobId = submitVideo(request);
					sendJson(exchange, 202, new JobSubmission(jobId));
					return;
				}
				if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/api/jobs/")
						&& path.endsWith("/client-assembly")) {
					String jobId = path.substring("/api/jobs/".length(), path.length() - "/client-assembly".length());
					sendJson(exchange, 200, clientAssemblyManifest(jobId));
					return;
				}
				String clientSegmentMarker = "/client-assembly/segments/";
				int clientSegmentAt = path.indexOf(clientSegmentMarker, "/api/jobs/".length());
				if ("GET".equals(exchange.getRequestMethod()) && clientSegmentAt >= 0) {
					String jobId = path.substring("/api/jobs/".length(), clientSegmentAt);
					serveClientAssemblySegment(exchange, jobId,
							path.substring(clientSegmentAt + clientSegmentMarker.length()));
					return;
				}
				if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/api/jobs/")
						&& path.endsWith("/client-assembly/complete")) {
					String jobId = path.substring("/api/jobs/".length(),
							path.length() - "/client-assembly/complete".length());
					completeClientAssembly(jobId, read(exchange, ClientAssemblyCompletion.class));
					sendEmpty(exchange, 204);
					return;
				}
				if ("POST".equals(exchange.getRequestMethod()) && "/api/jobs/fractal".equals(path)) {
					requireLoopback(exchange);
					FractalJobSubmitRequest request = read(exchange, FractalJobSubmitRequest.class);
					String jobId = submitFractal(request);
					sendJson(exchange, 202, new JobSubmission(jobId));
					return;
				}
				if ("POST".equals(exchange.getRequestMethod()) && "/api/jobs/ocr".equals(path)) {
					requireLoopback(exchange);
					OcrJobSubmitRequest request = read(exchange, OcrJobSubmitRequest.class);
					sendJson(exchange, 202, new JobSubmission(submitOcr(request)));
					return;
				}
				if ("POST".equals(exchange.getRequestMethod()) && "/api/jobs/blender".equals(path)) {
					requireLoopback(exchange);
					BlenderJobSubmitRequest request = read(exchange, BlenderJobSubmitRequest.class);
					sendJson(exchange, 202, new JobSubmission(submitBlender(request)));
					return;
				}
				if ("POST".equals(exchange.getRequestMethod()) && "/api/jobs".equals(path)) {
					JobSubmitRequest request = read(exchange, JobSubmitRequest.class);
					int taskCount = request.taskCount() > 0 ? request.taskCount() : compatibleWorkerCount("sleep");
					List<Long> durations = request.taskDurationsMillis().isEmpty()
							? java.util.Collections.nCopies(taskCount, request.durationMillis())
							: request.taskDurationsMillis();
					String jobId = scheduler.submit(durations);
					System.out.printf("Client %s submitted job %s: tasks=%d, duration=%dms%n",
							exchange.getRemoteAddress(), jobId, durations.size(), request.durationMillis());
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
				if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/api/jobs/")
						&& path.endsWith("/pause")) {
					requireLoopback(exchange);
					String jobId = path.substring("/api/jobs/".length(), path.length() - "/pause".length());
					if (!scheduler.pause(jobId)) {
						sendText(exchange, 409, "Job cannot be paused: " + jobId);
						return;
					}
					System.out.printf("Paused job %s from dashboard%n", jobId);
					sendEmpty(exchange, 204);
					return;
				}
				if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/api/jobs/")
						&& path.endsWith("/resume-as-new")) {
					requireLoopback(exchange);
					String jobId = path.substring("/api/jobs/".length(), path.length() - "/resume-as-new".length());
					InMemoryJobMonitor.Snapshot source = completedJobs.find(jobId)
							.orElseThrow(() -> new IllegalArgumentException("Unknown completed job: " + jobId));
					String resumedJobId = scheduler.resumeAsNew(source);
					System.out.printf("Resumed terminal job %s as new job %s%n", jobId, resumedJobId);
					sendJson(exchange, 202, new JobSubmission(resumedJobId));
					return;
				}
				if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/api/jobs/")
						&& path.endsWith("/resume")) {
					requireLoopback(exchange);
					String jobId = path.substring("/api/jobs/".length(), path.length() - "/resume".length());
					if (!scheduler.resume(jobId)) {
						sendText(exchange, 409, "Job is not paused: " + jobId);
						return;
					}
					System.out.printf("Resumed paused job %s from dashboard%n", jobId);
					sendEmpty(exchange, 204);
					return;
				}
				if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/api/jobs/")
						&& path.endsWith("/reveal-artifacts")) {
					requireLoopback(exchange);
					String jobId = path.substring("/api/jobs/".length(), path.length() - "/reveal-artifacts".length());
					revealInFileManager(completedJobs.artifactsDirectory(jobId));
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
				if ("DELETE".equals(exchange.getRequestMethod()) && "/api/jobs/completed".equals(path)) {
					requireLoopback(exchange);
					List<String> jobIds = completedJobs.snapshots().stream().map(InMemoryJobMonitor.Snapshot::jobId)
							.toList();
					for (String jobId : jobIds) {
						completedJobs.purge(jobId);
						scheduler.purgeCompleted(jobId);
					}
					System.out.printf("Purged all completed jobs (%d)%n", jobIds.size());
					sendEmpty(exchange, 204);
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
			} catch (IOException failure) {
				sendText(exchange, 500, failure.getMessage());
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

	private void restartServer(HttpExchange exchange) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 405);
			return;
		}
		if (!isLoopback(exchange)) {
			sendText(exchange, 403, "Server restart is loopback-only");
			return;
		}
		Runnable action = restartAction;
		if (action == null) {
			sendText(exchange, 503, "Server restart is not configured");
			return;
		}
		sendEmpty(exchange, 202);
		Thread.ofPlatform().name("mechana-server-restart").start(action);
	}

	private void stopServer(HttpExchange exchange) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 405);
			return;
		}
		if (!isLoopback(exchange)) {
			sendText(exchange, 403, "Server stop is loopback-only");
			return;
		}
		Runnable action = stopAction;
		if (action == null) {
			sendText(exchange, 503, "Server stop is not configured");
			return;
		}
		sendEmpty(exchange, 202);
		Thread.ofPlatform().name("mechana-server-stop").start(action);
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
				if (segments.length == 5 && "heartbeat".equals(segments[4])) {
					workerHeartbeat(exchange, segments[3]);
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
				if (segments.length == 8 && "tasks".equals(segments[4]) && "artifacts".equals(segments[6])) {
					uploadArtifact(exchange, segments[3], segments[5], segments[7]);
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
			WorkerPresence removed = workers.remove(workerId);
			if (removed != null) {
				System.out.printf("Worker %s removed (graceful shutdown)%n", workerId);
			}
			sendEmpty(exchange, 204);
		}

		private void workerHeartbeat(HttpExchange exchange, String workerId) throws IOException {
			requirePost(exchange);
			LeaseRequest heartbeat = read(exchange, LeaseRequest.class);
			markWorkerSeen(workerId, advertisedOrRemote(heartbeat.workerAddress(), exchange),
					heartbeat.supportedPlugins());
			scheduler.register(workerId, heartbeat.supportedPlugins());
			sendEmpty(exchange, 204);
		}

		private void updateTask(HttpExchange exchange, String workerId, String taskId, String action)
				throws IOException {
			requirePost(exchange);
			markWorkerSeen(workerId, null, Set.of());
			String[] completedLease = new String[1];
			boolean accepted = switch (action) {
				case "heartbeat" -> {
					TaskHeartbeat heartbeat = read(exchange, TaskHeartbeat.class);
					yield scheduler.heartbeat(workerId, taskId, heartbeat.leaseToken());
				}
				case "progress" -> {
					ProgressUpdate update = read(exchange, ProgressUpdate.class);
					yield scheduler.progress(workerId, taskId, update.leaseToken(), update.percent());
				}
				case "complete" -> {
					TaskCompletion completion = read(exchange, TaskCompletion.class);
					completedLease[0] = completion.leaseToken();
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
				if (completedLease[0] != null)
					recordDirectClientCompletion(status.jobId(), taskId, completedLease[0]);
				if ("complete".equals(action) && "ASSEMBLING".equals(scheduler.dashboard(status.jobId()).stage())) {
					if (videoJobs.containsKey(status.jobId())) {
						if (!videoJobs.get(status.jobId()).clientAssembly())
							assembleVideo(status.jobId());
					} else if (fractalJobs.containsKey(status.jobId()))
						assembleFractal(status.jobId());
					else if (ocrJobs.containsKey(status.jobId()))
						assembleOcr(status.jobId());
					else if (blenderJobs.containsKey(status.jobId()))
						assembleBlender(status.jobId());
				}
				archiveIfTerminal(status.jobId());
				if (!"heartbeat".equals(action)) {
					System.out.printf("Worker %s reported task %s action=%s%n", workerId, taskId, action);
					logJobStatus(status);
				}
			} else {
				System.out.printf("Rejected stale task update from worker %s for task %s action=%s%n", workerId, taskId,
						action);
			}
			sendEmpty(exchange, accepted ? 204 : 409);
		}

		private void uploadArtifact(HttpExchange exchange, String workerId, String taskId, String name)
				throws IOException {
			if (!"PUT".equals(exchange.getRequestMethod())) {
				sendEmpty(exchange, 405);
				return;
			}
			String token = exchange.getRequestHeaders().getFirst("X-Mechana-Lease");
			if (!scheduler.acceptsArtifact(workerId, taskId, token)) {
				sendEmpty(exchange, 409);
				return;
			}
			String jobId = scheduler.statusForTask(taskId).jobId();
			VideoJob video = videoJobs.get(jobId);
			FractalJob fractal = fractalJobs.get(jobId);
			OcrJob ocr = ocrJobs.get(jobId);
			BlenderJob blender = blenderJobs.get(jobId);
			if (video != null && name.matches("segment-[0-9]{5}\\.mkv")) {
				try (InputStream input = exchange.getRequestBody()) {
					dev.mechana.api.ArtifactReference published = defaultArtifactStore
							.put("jobs/" + jobId + "/intermediate/segments/" + name, input);
					video.segments().put(name, published);
				}
				sendEmpty(exchange, 204);
				return;
			}
			Map<String, dev.mechana.api.ArtifactReference> publications;
			if (fractal != null && name.matches("batch-[0-9]{5}\\.zip"))
				publications = fractal.batches();
			else if (ocr != null && name.matches("ocr-batch-[0-9]{5}\\.zip"))
				publications = ocr.batches();
			else if (blender != null && name.matches("frames-[0-9]{5}\\.zip"))
				publications = blender.batches();
			else
				throw new IllegalArgumentException("Unexpected task artifact");
			try (InputStream input = exchange.getRequestBody()) {
				publications.put(name,
						defaultArtifactStore.put("jobs/" + jobId + "/intermediate/" + name, input));
			}
			sendEmpty(exchange, 204);
		}
	}

	private static void servePlugin(HttpExchange exchange, Path jar, PluginLocation location) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 405);
			return;
		}
		long size = Files.size(jar);
		exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
		exchange.getResponseHeaders().set("X-Checksum-Sha256", location.sha256());
		exchange.sendResponseHeaders(200, size);
		try (var output = exchange.getResponseBody()) {
			Files.copy(jar, output);
		}
	}

	private void receiveClientVideoInput(HttpExchange exchange) throws IOException {
		if (!"PUT".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 405);
			return;
		}
		String prefix = "/api/client/video-inputs/";
		String path = exchange.getRequestURI().getPath();
		String token = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
		if (!token.matches("[A-Za-z0-9._-]+"))
			throw new IllegalArgumentException("Invalid client video upload token");
		try (InputStream input = exchange.getRequestBody()) {
			clientVideoUploads.put(token, defaultArtifactStore.put("client-uploads/" + token + "/source", input));
		}
		sendEmpty(exchange, 204);
	}

	private String submitVideo(VideoJobSubmitRequest request) throws IOException {
		String workToken = UUID.randomUUID().toString();
		Path scratch = workRoot.resolve(workToken);
		if (!request.clientChunks().isEmpty())
			return submitDirectClientVideo(request, scratch);
		dev.mechana.api.ArtifactReference uploadedSource = null;
		Path source;
		if ("client-local".equals(request.storageProvider())) {
			uploadedSource = clientVideoUploads.remove(request.sourceUploadToken());
			if (uploadedSource == null)
				throw new IllegalArgumentException("Unknown or expired client video upload token");
			source = scratch.resolve("client-upload").resolve("source");
			stageArtifact(uploadedSource, source);
		} else {
			source = Path.of(request.sourcePath()).toAbsolutePath().normalize();
			if (!Files.isRegularFile(source))
				throw new IllegalArgumentException("Video source does not exist: " + source);
		}
		Path preparedInput = scratch.resolve("prepared-input.mp4");
		Path input = scratch.resolve("input.mp4");
		Path output = scratch.resolve("result.mkv");
		Files.createDirectories(scratch.resolve("segments"));
		Files.createDirectories(scratch.resolve("inputs"));
		try {
			int taskCount = request.segmentCount() > 0 ? request.segmentCount() : compatibleWorkerCount("video-ffmpeg");
			ExternalProcessRunner runner = new ExternalProcessRunner();
			runner.run(
					List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", source.toString(), "-t",
							Double.toString(request.durationSeconds()), "-c", "copy", preparedInput.toString()),
					Duration.ofMinutes(15), CancellationToken.NEVER, ignored -> {
					});
			if (uploadedSource != null)
				deleteArtifactQuietly(uploadedSource);
			dev.mechana.api.ArtifactReference sourceArtifact;
			try (InputStream bytes = Files.newInputStream(preparedInput)) {
				sourceArtifact = defaultArtifactStore.put("video-staging/" + workToken + "/input/source.mp4", bytes);
			}
			stageArtifact(sourceArtifact, input);
			FfmpegCommands commands = new FfmpegCommands("ffmpeg", "ffprobe");
			MediaProbe probe = new MediaProbe(commands, runner);
			VideoTypes.MediaInfo info = probe.inspect(input, Duration.ofMinutes(5));
			VideoTypes.Options options = new VideoTypes.Options(VideoTypes.Container.MKV,
					VideoTypes.QualityMode.VISUALLY_LOSSLESS, 28, "slow",
					Duration.ofMillis(Math.max(1, Math.round(info.durationSeconds() * 1000 / taskCount))), taskCount,
					Duration.ofHours(2));
			VideoTypes.Plan plan = exactSegmentPlan(info, options, probe.keyframes(input, options.processTimeout()),
					scratch, taskCount);
			long bitrate = targetVideoBitrate(info, request.targetSizeRatio());
			List<String> inputTokens = new java.util.ArrayList<>(plan.segments().size());
			List<dev.mechana.api.ArtifactReference> workerInputs = new java.util.ArrayList<>(plan.segments().size());
			List<WorkSpec> work = new java.util.ArrayList<>(plan.segments().size());
			for (VideoTypes.Segment segment : plan.segments()) {
				Path segmentInput = scratch.resolve("inputs").resolve("input-%05d.mp4".formatted(segment.index()));
				runner.run(commands.copySegment(input, segment, segmentInput), Duration.ofMinutes(5),
						CancellationToken.NEVER, ignored -> {
						});
				String inputToken = UUID.randomUUID().toString();
				dev.mechana.api.ArtifactReference workerInput;
				try (InputStream bytes = Files.newInputStream(segmentInput)) {
					workerInput = defaultArtifactStore.put(
							"video-staging/" + workToken + "/inputs/input-%05d.mp4".formatted(segment.index()), bytes);
				}
				videoInputs.put(inputToken, workerInput);
				workerInputs.add(workerInput);
				inputTokens.add(inputToken);
				work.add(new WorkSpec(Math.max(1, Math.round(segment.durationSeconds() * 1000)),
						Map.of("inputUrl", publicUrl + "/api/video-inputs/" + inputToken, "segmentIndex",
								Integer.toString(segment.index()), "durationSeconds",
								Double.toString(segment.durationSeconds()), "startSeconds",
								Double.toString(segment.startSeconds()), "endSeconds",
								Double.toString(segment.endSeconds()), "videoBitrate", Long.toString(bitrate), "preset",
								options.preset()),
						"Segment " + segment.index(),
						Map.of("range", segment.startSeconds() + "–" + segment.endSeconds() + "s")));
			}
			String jobId = scheduler.submitVideo(work,
					Map.of("source", source.toString(), "inputDuration", "%.1fs".formatted(info.durationSeconds()),
							"targetSizeRatio", Double.toString(request.targetSizeRatio())),
					videoPluginLocation);
			videoJobs.put(jobId,
					new VideoJob(List.copyOf(inputTokens), sourceArtifact, List.copyOf(workerInputs), input, output,
							scratch, plan, new ConcurrentHashMap<>(), null,
							"client-local".equals(request.storageProvider())));
			return jobId;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			videoInputs.keySet().removeAll(inputTokensForWorkToken(workToken));
			deleteTree(scratch);
			throw new IOException("Video planning was interrupted", interrupted);
		} catch (IOException | RuntimeException failure) {
			videoInputs.keySet().removeAll(inputTokensForWorkToken(workToken));
			deleteTree(scratch);
			throw failure;
		}
	}

	private String submitDirectClientVideo(VideoJobSubmitRequest request, Path scratch) throws IOException {
		Files.createDirectories(scratch);
		VideoTypes.MediaInfo info = new VideoTypes.MediaInfo("client-chunks", request.durationSeconds(), "unknown", 0,
				0, 1, 0, 0, 0, request.clientChunks().stream().mapToLong(chunk -> chunk.size()).sum());
		VideoTypes.Options options = new VideoTypes.Options(VideoTypes.Container.MKV,
				VideoTypes.QualityMode.VISUALLY_LOSSLESS, 28, "slow",
				Duration.ofMillis(Math.max(1, Math.round(request.durationSeconds() * 1000 / request.segmentCount()))),
				request.segmentCount(), Duration.ofHours(2));
		List<VideoTypes.Segment> segments = new java.util.ArrayList<>(request.clientChunks().size());
		List<WorkSpec> work = new java.util.ArrayList<>(request.clientChunks().size());
		for (int index = 0; index < request.clientChunks().size(); index++) {
			var chunk = request.clientChunks().get(index);
			segments.add(new VideoTypes.Segment(index, chunk.startSeconds(), chunk.endSeconds(),
					scratch.resolve("segment-%05d.mkv".formatted(index))));
			String outputUrl = request.clientOutputUrl().replace("{index}", Integer.toString(index));
			work.add(new WorkSpec(Math.max(1, Math.round(chunk.durationSeconds() * 1000)),
					Map.ofEntries(Map.entry("inputUrl", chunk.inputUrl()),
							Map.entry("inputSize", Long.toString(chunk.size())),
							Map.entry("inputSha256", chunk.sha256()), Map.entry("artifactUploadUrl", outputUrl),
							Map.entry("segmentIndex", Integer.toString(index)),
							Map.entry("durationSeconds", Double.toString(chunk.durationSeconds())),
							Map.entry("startSeconds", Double.toString(chunk.startSeconds())),
							Map.entry("endSeconds", Double.toString(chunk.endSeconds())),
							Map.entry("videoBitrate", Long.toString(request.videoBitrate())),
							Map.entry("preset", options.preset()),
							Map.entry("requiredWorkerCapability", "storage.client-direct-artifacts.v1"),
							Map.entry("artifactTransferOrigin", outputUrl)),
					"Segment " + index, Map.of("range", chunk.startSeconds() + "–" + chunk.endSeconds() + "s")));
		}
		VideoTypes.Plan plan = new VideoTypes.Plan(info, options, List.copyOf(segments), scratch);
		String jobId = scheduler.submitVideo(work,
				Map.of("source", request.sourcePath(), "inputDuration", "%.1fs".formatted(request.durationSeconds()),
						"targetSizeRatio", Double.toString(request.targetSizeRatio()), "dataPlane", "client-direct"),
				videoPluginLocation);
		Map<String, String> taskArtifacts = new HashMap<>();
		for (int index = 0; index < segments.size(); index++)
			taskArtifacts.put(jobId + "-" + (index + 1), "segment-%05d.mkv".formatted(index));
		videoJobs.put(jobId,
				new VideoJob(List.of(), null, List.of(), scratch.resolve("input.mp4"), scratch.resolve("result.mkv"),
						scratch, plan, new ConcurrentHashMap<>(), null, true, true, Map.copyOf(taskArtifacts),
						new ConcurrentHashMap<>()));
		return jobId;
	}

	private void recordDirectClientCompletion(String jobId, String taskId, String leaseToken) {
		VideoJob video = videoJobs.get(jobId);
		if (video == null || !video.directClient())
			return;
		String name = video.taskArtifacts().get(taskId);
		if (name != null)
			video.acceptedLeaseHashes().put(name, sha256(leaseToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	private String submitFractal(FractalJobSubmitRequest request) throws IOException {
		int taskCount = request.taskCount() > 0
				? request.taskCount()
				: Math.min(request.imageCount(), compatibleWorkerCount(FRACTAL_PLUGIN_ID));
		Path scratch = workRoot.resolve(UUID.randomUUID().toString());
		Files.createDirectories(scratch.resolve("batches"));
		boolean retained = false;
		try {
			List<WorkSpec> work = new java.util.ArrayList<>(taskCount);
			int base = request.imageCount() / taskCount;
			int remainder = request.imageCount() % taskCount;
			int start = 0;
			for (int batch = 0; batch < taskCount; batch++) {
				int count = base + (batch < remainder ? 1 : 0);
				work.add(new WorkSpec(count,
						Map.of("startIndex", Integer.toString(start), "imageCount", Integer.toString(count), "width",
								Integer.toString(request.width()), "height", Integer.toString(request.height()),
								"maxIterations", Integer.toString(request.maxIterations()), "seed",
								Long.toString(request.seed()), "batchIndex", Integer.toString(batch)),
						"Batch " + batch,
						Map.of("images", start + "–" + (start + count - 1), "count", Integer.toString(count))));
				start += count;
			}
			String jobId = scheduler.submitPlugin(FRACTAL_PLUGIN_ID, FRACTAL_PLUGIN_VERSION, FRACTAL_PLUGIN_ENTRYPOINT,
					work,
					Map.of("imageCount", Integer.toString(request.imageCount()), "taskCount",
							Integer.toString(taskCount), "dimensions", request.width() + "×" + request.height(),
							"maxIterations", Integer.toString(request.maxIterations()), "seed",
							Long.toString(request.seed())),
					fractalPluginLocation);
			fractalJobs.put(jobId,
					new FractalJob(scratch, request, new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
			retained = true;
			return jobId;
		} finally {
			if (!retained)
				deleteTree(scratch);
		}
	}

	private String submitOcr(OcrJobSubmitRequest request) throws IOException {
		Path source = Path.of(request.sourcePath()).toAbsolutePath().normalize();
		if (!Files.isRegularFile(source))
			throw new IllegalArgumentException("PDF source does not exist: " + source);
		Path fileName = source.getFileName();
		if (fileName == null || !fileName.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))
			throw new IllegalArgumentException("OCR input must be a PDF file: " + source);
		byte[] signature = new byte[5];
		try (var input = Files.newInputStream(source)) {
			if (input.readNBytes(signature, 0, signature.length) != signature.length || !java.util.Arrays
					.equals(signature, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
				throw new IllegalArgumentException("OCR input is not a valid PDF file: " + source);
		}
		Path scratch = workRoot.resolve(UUID.randomUUID().toString());
		Path pages = scratch.resolve("pages");
		Files.createDirectories(pages);
		Files.createDirectories(scratch.resolve("batches"));
		List<String> inputTokens = new java.util.ArrayList<>();
		boolean retained = false;
		try (PDDocument document = Loader.loadPDF(source.toFile())) {
			int documentPages = document.getNumberOfPages();
			if (documentPages < 1)
				throw new IllegalArgumentException("PDF contains no pages");
			if (request.firstPage() > documentPages)
				throw new IllegalArgumentException("firstPage exceeds PDF page count");
			int pageCount = request.pageCount() == 0
					? documentPages - request.firstPage() + 1
					: Math.min(request.pageCount(), documentPages - request.firstPage() + 1);
			PDFRenderer renderer = new PDFRenderer(document);
			List<Path> rendered = new java.util.ArrayList<>(pageCount);
			for (int index = 0; index < pageCount; index++) {
				int documentPage = request.firstPage() + index;
				Path page = pages.resolve("page-%06d.png".formatted(documentPage));
				if (!ImageIO.write(renderer.renderImageWithDPI(documentPage - 1, request.dpi(), ImageType.GRAY), "png",
						page.toFile()))
					throw new IOException("PNG writer is unavailable");
				rendered.add(page);
			}
			int taskCount = request.taskCount() > 0
					? Math.min(request.taskCount(), pageCount)
					: Math.min(pageCount, compatibleWorkerCount(OCR_PLUGIN_ID));
			List<WorkSpec> work = new java.util.ArrayList<>(taskCount);
			int base = pageCount / taskCount;
			int remainder = pageCount % taskCount;
			int start = 0;
			for (int batch = 0; batch < taskCount; batch++) {
				int count = base + (batch < remainder ? 1 : 0);
				Map<String, String> parameters = new HashMap<>();
				parameters.put("startPage", Integer.toString(request.firstPage() + start));
				parameters.put("pageCount", Integer.toString(count));
				parameters.put("batchIndex", Integer.toString(batch));
				parameters.put("language", request.language());
				for (int offset = 0; offset < count; offset++) {
					String token = UUID.randomUUID().toString();
					Path page = rendered.get(start + offset);
					try (InputStream bytes = Files.newInputStream(page)) {
						ocrInputs.put(token, defaultArtifactStore.put("ocr-staging/" + token + "/input.png", bytes));
					}
					inputTokens.add(token);
					parameters.put("pageUrl." + offset, publicUrl + "/api/ocr-inputs/" + token);
				}
				int batchFirstPage = request.firstPage() + start;
				work.add(new WorkSpec(count, parameters, "Pages " + batchFirstPage + "–" + (batchFirstPage + count - 1),
						Map.of("pages", batchFirstPage + "–" + (batchFirstPage + count - 1), "language",
								request.language())));
				start += count;
			}
			String jobId = scheduler.submitPlugin(OCR_PLUGIN_ID, OCR_PLUGIN_VERSION, OCR_PLUGIN_ENTRYPOINT, work,
					Map.of("source", source.toString(), "pages",
							request.firstPage() + "–" + (request.firstPage() + pageCount - 1), "taskCount",
							Integer.toString(taskCount), "dpi", Integer.toString(request.dpi()), "language",
							request.language(), "title", request.title()),
					ocrPluginLocation);
			ocrJobs.put(jobId, new OcrJob(List.copyOf(inputTokens), scratch, request.firstPage(), pageCount, request,
					new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
			retained = true;
			return jobId;
		} finally {
			if (!retained) {
				inputTokens.stream().map(ocrInputs::remove).filter(Objects::nonNull)
						.forEach(this::deleteArtifactQuietly);
				deleteTree(scratch);
			}
		}
	}

	private String submitBlender(BlenderJobSubmitRequest request) throws IOException {
		Path source = Path.of(request.sourcePath()).toAbsolutePath().normalize();
		if (!Files.isRegularFile(source) || !Objects.requireNonNull(source.getFileName()).toString()
				.toLowerCase(java.util.Locale.ROOT).endsWith(".blend"))
			throw new IllegalArgumentException("Packed Blender source does not exist or is not .blend: " + source);
		Path scratch = workRoot.resolve(UUID.randomUUID().toString());
		Files.createDirectories(scratch.resolve("batches"));
		String token = UUID.randomUUID().toString();
		try (InputStream bytes = Files.newInputStream(source)) {
			blenderInputs.put(token, defaultArtifactStore.put("blender-staging/" + token + "/scene.blend", bytes));
		}
		boolean retained = false;
		try {
			int taskCount = request.taskCount() > 0
					? request.taskCount()
					: Math.min(request.lastFrame() - request.firstFrame() + 1,
							compatibleWorkerCount(BLENDER_PLUGIN_ID));
			List<FramePlanner.Batch> batches = new FramePlanner().plan(request.firstFrame(), request.lastFrame(),
					taskCount);
			List<WorkSpec> work = batches.stream()
					.map(batch -> new WorkSpec(batch.frameCount(),
							Map.of("inputUrl", publicUrl + "/api/blender-inputs/" + token, "batchIndex",
									Integer.toString(batch.index()), "firstFrame", Integer.toString(batch.firstFrame()),
									"lastFrame", Integer.toString(batch.lastFrame()), "width",
									Integer.toString(request.width()), "height", Integer.toString(request.height()),
									"samples", Integer.toString(request.samples()), "threads", "0"),
							"Frames " + batch.firstFrame() + "–" + batch.lastFrame(),
							Map.of("frames", batch.firstFrame() + "–" + batch.lastFrame(), "samples",
									Integer.toString(request.samples()))))
					.toList();
			String jobId = scheduler.submitPlugin(BLENDER_PLUGIN_ID, BLENDER_PLUGIN_VERSION, BLENDER_PLUGIN_ENTRYPOINT,
					work,
					Map.of("source", source.toString(), "frames", request.firstFrame() + "–" + request.lastFrame(),
							"dimensions", request.width() + "×" + request.height(), "samples",
							Integer.toString(request.samples()), "fps", Integer.toString(request.fps())),
					blenderPluginLocation);
			blenderJobs.put(jobId,
					new BlenderJob(token, scratch, request, new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
			retained = true;
			return jobId;
		} finally {
			if (!retained) {
				deleteArtifactQuietly(blenderInputs.remove(token));
				deleteTree(scratch);
			}
		}
	}

	private int compatibleWorkerCount(String capability) {
		return Math.max(1, Math.toIntExact(workers.values().stream().filter(WorkerPresence::connected)
				.filter(worker -> worker.capabilities().contains(capability)).count()));
	}

	private static VideoTypes.Plan exactSegmentPlan(VideoTypes.MediaInfo input, VideoTypes.Options options,
			List<Double> keyframes, Path scratch, int segmentCount) throws IOException {
		List<Double> candidates = keyframes.stream().filter(keyframe -> keyframe >= 0.5)
				.filter(keyframe -> input.durationSeconds() - keyframe >= 0.5).sorted().toList();
		int cuts = segmentCount - 1;
		if (candidates.size() < cuts)
			throw new IOException("The clip has only " + candidates.size() + " usable internal keyframes; " + cuts
					+ " are required for " + segmentCount + " segments");
		List<Double> boundaries = new java.util.ArrayList<>(segmentCount + 1);
		boundaries.add(0.0);
		int previousIndex = -1;
		for (int cut = 1; cut <= cuts; cut++) {
			double desired = input.durationSeconds() * cut / segmentCount;
			int lastAllowed = candidates.size() - (cuts - cut) - 1;
			int chosen = previousIndex + 1;
			for (int index = chosen + 1; index <= lastAllowed; index++)
				if (Math.abs(candidates.get(index) - desired) < Math.abs(candidates.get(chosen) - desired))
					chosen = index;
			boundaries.add(candidates.get(chosen));
			previousIndex = chosen;
		}
		boundaries.add(input.durationSeconds());
		List<VideoTypes.Segment> segments = new java.util.ArrayList<>(segmentCount);
		for (int index = 0; index < segmentCount; index++)
			segments.add(new VideoTypes.Segment(index, boundaries.get(index), boundaries.get(index + 1),
					scratch.resolve("segments").resolve("segment-%05d.mkv".formatted(index))));
		return new VideoTypes.Plan(input, options, segments, scratch);
	}

	private void serveVideoInput(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 405);
			return;
		}
		String prefix = "/api/video-inputs/";
		String path = exchange.getRequestURI().getPath();
		dev.mechana.api.ArtifactReference input = path.startsWith(prefix)
				? videoInputs.get(path.substring(prefix.length()))
				: null;
		if (input == null || !defaultArtifactStore.exists(input)) {
			sendEmpty(exchange, 404);
			return;
		}
		exchange.getResponseHeaders().set("Content-Type", "video/mp4");
		exchange.getResponseHeaders().set("X-Checksum-Sha256", input.sha256());
		exchange.sendResponseHeaders(200, input.sizeBytes());
		try (InputStream bytes = defaultArtifactStore.open(input); var output = exchange.getResponseBody()) {
			bytes.transferTo(output);
		}
	}

	private void serveOcrInput(HttpExchange exchange) throws IOException {
		serveMappedArtifact(exchange, "/api/ocr-inputs/", ocrInputs, "image/png");
	}

	private void serveBlenderInput(HttpExchange exchange) throws IOException {
		serveMappedArtifact(exchange, "/api/blender-inputs/", blenderInputs, "application/x-blender");
	}

	private void serveMappedArtifact(HttpExchange exchange, String prefix,
			Map<String, dev.mechana.api.ArtifactReference> inputs,
			String contentType) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			sendEmpty(exchange, 405);
			return;
		}
		String path = exchange.getRequestURI().getPath();
		dev.mechana.api.ArtifactReference input = path.startsWith(prefix)
				? inputs.get(path.substring(prefix.length()))
				: null;
		if (input == null || !artifactStores.require(input.providerId()).exists(input)) {
			sendEmpty(exchange, 404);
			return;
		}
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("X-Checksum-Sha256", input.sha256());
		exchange.sendResponseHeaders(200, input.sizeBytes());
		try (InputStream bytes = artifactStores.require(input.providerId()).open(input);
				var output = exchange.getResponseBody()) {
			bytes.transferTo(output);
		}
	}

	private void assembleVideo(String jobId) {
		VideoJob video = videoJobs.get(jobId);
		if (video == null)
			return;
		try {
			for (VideoTypes.Segment segment : video.plan().segments()) {
				String name = "segment-%05d.mkv".formatted(segment.index());
				dev.mechana.api.ArtifactReference artifact = video.segments().get(name);
				if (artifact == null)
					throw new IOException("Missing published segment artifact: " + name);
				stageArtifact(artifact, segment.output());
			}
			FfmpegCommands commands = new FfmpegCommands("ffmpeg", "ffprobe");
			ExternalProcessRunner runner = new ExternalProcessRunner();
			new VideoAssembler(commands, runner).assemble(video.input(), video.output(), video.plan(),
					CancellationToken.NEVER);
			new FinalValidator(new MediaProbe(commands, runner)).validateSmallerThanInput(video.output(), video.plan());
			try (InputStream bytes = Files.newInputStream(video.output())) {
				video.finalArtifact(
						defaultArtifactStore.put("jobs/" + jobId + "/artifacts/compressed-first-minute.mkv", bytes));
			}
			scheduler.finishVideo(jobId, null);
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			scheduler.finishVideo(jobId, failure.getMessage());
		} catch (IOException | RuntimeException failure) {
			scheduler.finishVideo(jobId, failure.getMessage());
		}
	}

	private VideoAssemblyManifest clientAssemblyManifest(String jobId) {
		VideoJob video = videoJobs.get(jobId);
		if (video == null || !video.clientAssembly())
			throw new IllegalArgumentException("Job does not use client-side assembly: " + jobId);
		if (!"ASSEMBLING".equals(scheduler.dashboard(jobId).stage()))
			throw new IllegalArgumentException("Video segments are not ready for client assembly");
		List<ArtifactReference> segments = video.plan().segments().stream().map(segment -> {
			String name = "segment-%05d.mkv".formatted(segment.index());
			if (video.directClient()) {
				String accepted = Objects.requireNonNull(video.acceptedLeaseHashes().get(name),
						"Missing accepted client segment " + name);
				return new ArtifactReference("client-local", accepted, 0, "", true, "");
			}
			dev.mechana.api.ArtifactReference artifact = Objects.requireNonNull(video.segments().get(name),
					"Missing segment " + name);
			return new ArtifactReference(artifact.providerId(), artifact.key(), artifact.sizeBytes(),
					"/api/jobs/" + jobId + "/client-assembly/segments/" + name, false, artifact.sha256());
		}).toList();
		return new VideoAssemblyManifest(jobId, segments);
	}

	private void serveClientAssemblySegment(HttpExchange exchange, String jobId, String name) throws IOException {
		VideoJob video = videoJobs.get(jobId);
		if (video == null || !video.clientAssembly() || !name.matches("segment-[0-9]{5}\\.mkv"))
			throw new IllegalArgumentException("Unknown client assembly segment");
		dev.mechana.api.ArtifactReference artifact = video.segments().get(name);
		if (artifact == null)
			throw new IllegalArgumentException("Unknown client assembly segment");
		exchange.getResponseHeaders().set("Content-Type", "video/x-matroska");
		exchange.getResponseHeaders().set("X-Checksum-Sha256", artifact.sha256());
		exchange.sendResponseHeaders(200, artifact.sizeBytes());
		try (InputStream input = artifactStores.require(artifact.providerId()).open(artifact);
				var output = exchange.getResponseBody()) {
			input.transferTo(output);
		}
	}

	private void completeClientAssembly(String jobId, ClientAssemblyCompletion completion) {
		VideoJob video = videoJobs.get(jobId);
		if (video == null || !video.clientAssembly() || !"ASSEMBLING".equals(scheduler.dashboard(jobId).stage()))
			throw new IllegalArgumentException("Job is not awaiting client assembly: " + jobId);
		video.finalArtifact(new dev.mechana.api.ArtifactReference(completion.provider(), completion.key(),
				completion.size(), completion.sha256()));
		scheduler.finishVideo(jobId, null);
		archiveIfTerminal(jobId);
	}

	private void assembleFractal(String jobId) {
		FractalJob fractal = fractalJobs.get(jobId);
		if (fractal == null)
			return;
		try {
			List<Path> batches = stagePublishedBatches(fractal.batches(), fractal.scratch().resolve("batches"));
			FractalJobSubmitRequest request = fractal.request();
			Path result = fractal.scratch().resolve("result");
			new FractalCollectionAssembler().assemble(batches, result,
					request.imageCount(), request.width(), request.height(), request.maxIterations(), request.seed());
			publishResultTree(jobId, result, fractal.outputs());
			scheduler.finishAssembly(jobId, null);
		} catch (IOException failure) {
			scheduler.finishAssembly(jobId, "Fractal assembly failed: " + failure.getMessage());
		}
	}

	private void assembleOcr(String jobId) {
		OcrJob ocr = ocrJobs.get(jobId);
		if (ocr == null)
			return;
		try {
			List<Path> batches = stagePublishedBatches(ocr.batches(), ocr.scratch().resolve("batches"));
			Path result = ocr.scratch().resolve("result");
			new OcrMarkdownAssembler().assemble(batches, result, ocr.firstPage(),
					ocr.pageCount(), ocr.request().title());
			publishResultTree(jobId, result, ocr.outputs());
			scheduler.finishAssembly(jobId, null);
		} catch (IOException failure) {
			scheduler.finishAssembly(jobId, "OCR assembly failed: " + failure.getMessage());
		}
	}

	private void assembleBlender(String jobId) {
		BlenderJob blender = blenderJobs.get(jobId);
		if (blender == null)
			return;
		try {
			List<Path> batches = stagePublishedBatches(blender.batches(), blender.scratch().resolve("batches"));
			BlenderJobSubmitRequest request = blender.request();
			Path result = blender.scratch().resolve("result");
			new BlenderMovieAssembler().assemble(batches, result, request.firstFrame(),
					request.lastFrame(), request.width(), request.height(), request.fps(), "ffmpeg");
			publishResultTree(jobId, result, blender.outputs());
			scheduler.finishAssembly(jobId, null);
		} catch (IOException | InterruptedException failure) {
			if (failure instanceof InterruptedException)
				Thread.currentThread().interrupt();
			scheduler.finishAssembly(jobId, "Blender assembly failed: " + failure.getMessage());
		}
	}

	private List<Path> stagePublishedBatches(Map<String, dev.mechana.api.ArtifactReference> artifacts,
			Path directory) throws IOException {
		Files.createDirectories(directory);
		List<Path> staged = new java.util.ArrayList<>(artifacts.size());
		for (var entry : artifacts.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
			Path destination = directory.resolve(entry.getKey()).normalize();
			if (!destination.startsWith(directory))
				throw new IOException("Unsafe intermediate artifact name: " + entry.getKey());
			stageArtifact(entry.getValue(), destination);
			staged.add(destination);
		}
		return List.copyOf(staged);
	}

	private void publishResultTree(String jobId, Path result,
			Map<String, dev.mechana.api.ArtifactReference> outputs) throws IOException {
		try (var paths = Files.walk(result)) {
			for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
				String name = result.relativize(path).toString().replace('\\', '/');
				try (InputStream bytes = Files.newInputStream(path)) {
					outputs.put(name, defaultArtifactStore.put("jobs/" + jobId + "/artifacts/" + name, bytes));
				}
			}
		}
	}

	private static long targetVideoBitrate(VideoTypes.MediaInfo input, double ratio) {
		double targetTotalBitsPerSecond = input.inputBytes() * 8.0 * ratio / input.durationSeconds();
		long audioAndOverheadReserve = input.audioStreams() == 1 ? 512_000 : 64_000;
		return Math.max(250_000, Math.round(targetTotalBitsPerSecond - audioAndOverheadReserve));
	}

	private void sendJobDashboard(HttpExchange exchange, String jobId) throws IOException {
		InMemoryJobMonitor.Snapshot snapshot = jobDashboard(jobId);
		ObjectNode body = json.valueToTree(snapshot);
		List<CompletedJobStore.Artifact> artifacts = completedJobs.find(jobId).isPresent()
				? completedJobs.artifacts(jobId)
				: List.of();
		body.set("artifacts",
				json.valueToTree(artifacts.stream()
						.map(artifact -> Map.of("name", artifact.name(), "size", artifact.size(), "url",
								"/api/jobs/" + jobId + "/artifacts/" + artifact.name(), "provider", artifact.provider(),
								"key", artifact.key(), "sha256", artifact.sha256()))
						.toList()));
		body.put("completed", completedJobs.find(jobId).isPresent());
		body.put("abortable", !isTerminal(snapshot.stage()));
		body.put("pausable", "QUEUED".equals(snapshot.stage()) || "EXECUTING".equals(snapshot.stage()));
		body.put("resumable", "PAUSED".equals(snapshot.stage()));
		body.put("resumableAsNew", "CANCELLED".equals(snapshot.stage()) || "FAILED".equals(snapshot.stage()));
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
			VideoJob video = videoJobs.get(jobId);
			if (video != null && "SUCCEEDED".equals(snapshot.stage()) && video.finalArtifact() != null) {
				if ("server-local".equals(video.finalArtifact().providerId()))
					completedJobs.registerArtifact(jobId, "compressed-first-minute.mkv", video.finalArtifact());
				else
					completedJobs.registerExternalArtifact(jobId, "compressed-first-minute.mkv", video.finalArtifact());
			}
			FractalJob fractal = fractalJobs.get(jobId);
			if (fractal != null && "SUCCEEDED".equals(snapshot.stage()))
				registerCompletedArtifacts(jobId, fractal.outputs());
			OcrJob ocr = ocrJobs.get(jobId);
			if (ocr != null && "SUCCEEDED".equals(snapshot.stage()))
				registerCompletedArtifacts(jobId, ocr.outputs());
			BlenderJob blender = blenderJobs.get(jobId);
			if (blender != null && "SUCCEEDED".equals(snapshot.stage()))
				registerCompletedArtifacts(jobId, blender.outputs());
		} catch (IOException failure) {
			System.err.printf("Could not archive completed job %s: %s%n", jobId, failure.getMessage());
		} finally {
			VideoJob video = videoJobs.remove(jobId);
			if (video != null) {
				video.inputTokens().forEach(videoInputs::remove);
				deleteArtifactQuietly(video.sourceArtifact());
				video.workerInputs().forEach(this::deleteArtifactQuietly);
				video.segments().values().forEach(this::deleteArtifactQuietly);
				deleteTree(video.scratch());
			}
			FractalJob fractal = fractalJobs.remove(jobId);
			if (fractal != null) {
				fractal.batches().values().forEach(this::deleteArtifactQuietly);
				deleteTree(fractal.scratch());
			}
			OcrJob ocr = ocrJobs.remove(jobId);
			if (ocr != null) {
				ocr.inputTokens().stream().map(ocrInputs::remove).filter(Objects::nonNull)
						.forEach(this::deleteArtifactQuietly);
				ocr.batches().values().forEach(this::deleteArtifactQuietly);
				deleteTree(ocr.scratch());
			}
			BlenderJob blender = blenderJobs.remove(jobId);
			if (blender != null) {
				deleteArtifactQuietly(blenderInputs.remove(blender.inputToken()));
				blender.batches().values().forEach(this::deleteArtifactQuietly);
				deleteTree(blender.scratch());
			}
		}
	}

	private void registerCompletedArtifacts(String jobId,
			Map<String, dev.mechana.api.ArtifactReference> artifacts) throws IOException {
		for (var entry : artifacts.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
			dev.mechana.api.ArtifactReference artifact = entry.getValue();
			if (dev.mechana.api.StorageSelection.SERVER_LOCAL.equals(artifact.providerId()))
				completedJobs.registerArtifact(jobId, entry.getKey(), artifact);
			else
				completedJobs.registerExternalArtifact(jobId, entry.getKey(), artifact);
		}
	}

	private static void deleteTree(Path root) {
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// Server work storage is best-effort cleanup after durable archival.
		}
	}

	private void stageArtifact(dev.mechana.api.ArtifactReference artifact, Path destination) throws IOException {
		Path parent = Objects.requireNonNull(destination.getParent(), "Artifact staging path must have a parent");
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".artifact-stage-", ".tmp");
		try (InputStream input = artifactStores.require(artifact.providerId()).open(artifact)) {
			Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		if (Files.size(temporary) != artifact.sizeBytes() || !sha256(temporary).equalsIgnoreCase(artifact.sha256())) {
			Files.deleteIfExists(temporary);
			throw new IOException(
					"Artifact integrity check failed for " + artifact.providerId() + ":" + artifact.key());
		}
		Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	private void deleteArtifactQuietly(dev.mechana.api.ArtifactReference artifact) {
		if (artifact == null)
			return;
		try {
			artifactStores.require(artifact.providerId()).delete(artifact);
		} catch (IOException failure) {
			System.err.printf("Could not delete transient artifact %s:%s: %s%n", artifact.providerId(), artifact.key(),
					failure.getMessage());
		}
	}

	private Set<String> inputTokensForWorkToken(String workToken) {
		String prefix = "video-staging/" + workToken + "/";
		return videoInputs.entrySet().stream().filter(entry -> entry.getValue().key().startsWith(prefix))
				.map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
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

	private static void revealInFileManager(Path directory) throws IOException {
		String operatingSystem = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		List<String> command = operatingSystem.contains("mac")
				? List.of("open", directory.toString())
				: operatingSystem.contains("win")
						? List.of("explorer.exe", directory.toString())
						: List.of("xdg-open", directory.toString());
		new ProcessBuilder(command).start();
	}

	private void markWorkerSeen(String workerId, String workerAddress, Set<String> supportedPlugins) {
		long seenAt = System.currentTimeMillis();
		WorkerPresence previous = workers.get(workerId);
		workers.compute(workerId, (id, worker) -> new WorkerPresence(
				workerAddress == null && worker != null ? worker.address() : workerAddress,
				supportedPlugins.isEmpty() && worker != null ? worker.capabilities() : Set.copyOf(supportedPlugins),
				seenAt, true, 0));
		if (previous == null || !previous.connected()) {
			System.out.printf("Worker %s connected; capabilities=%s%n", workerId, supportedPlugins);
		}
	}

	private void reapExpiredWorkersAndLeases() {
		int expiredTasks = scheduler.expireLeases();
		if (expiredTasks > 0) {
			System.out.printf("Requeued %d task(s) after worker lease expiration%n", expiredTasks);
		}
		reapExpiredWorkers(System.currentTimeMillis());
	}

	void reapExpiredWorkers(long nowMillis) {
		long disconnectedBefore = nowMillis - WORKER_TIMEOUT_MILLIS;
		long forgottenBefore = nowMillis - WORKER_RETENTION_MILLIS;
		workers.forEach((workerId, worker) -> {
			if (!worker.connected() && worker.disconnectedAt() < forgottenBefore && workers.remove(workerId, worker)) {
				System.out.printf("Worker %s removed after extended disconnect%n", workerId);
				return;
			}
			if (worker.connected() && worker.lastSeenAt() < disconnectedBefore
					&& workers.replace(workerId, worker, new WorkerPresence(worker.address(), worker.capabilities(),
							worker.lastSeenAt(), false, nowMillis))) {
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
		Map<String, WorkerActivity> activeJobsByWorker = new HashMap<>();
		for (dev.mechana.coordinator.InMemoryJobMonitor.Snapshot job : activeJobItems) {
			for (dev.mechana.coordinator.InMemoryJobMonitor.WorkUnitSnapshot workUnit : job.workUnits()) {
				if ("RUNNING".equals(workUnit.state()))
					activeJobsByWorker.put(workUnit.workerAddress(),
							new WorkerActivity(job.jobId(), job.plugin(), workUnit.progress()));
			}
		}
		List<ServerDashboard.WorkerSnapshot> workerSnapshots = workers.entrySet().stream()
				.sorted(Comparator.comparing(java.util.Map.Entry::getKey)).map(entry -> {
					WorkerActivity active = entry.getValue().connected()
							? activeJobsByWorker.get(entry.getKey())
							: null;
					String activity = !entry.getValue().connected()
							? "OFFLINE"
							: active == null ? "IDLE" : active.plugin();
					return new ServerDashboard.WorkerSnapshot(entry.getKey(), entry.getValue().address(),
							entry.getValue().connected() ? "CONNECTED" : "DISCONNECTED", activity,
							active == null ? null : active.jobId(), active == null ? 0 : active.progress(),
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

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	private record WorkerPresence(String address, Set<String> capabilities, long lastSeenAt, boolean connected,
			long disconnectedAt) {
		private WorkerPresence {
			address = address == null || address.isBlank() ? "unknown" : address;
			capabilities = Set.copyOf(capabilities);
		}
	}

	private static final class VideoJob {
		private final List<String> inputTokens;
		private final dev.mechana.api.ArtifactReference sourceArtifact;
		private final List<dev.mechana.api.ArtifactReference> workerInputs;
		private final Path input;
		private final Path output;
		private final Path scratch;
		private final VideoTypes.Plan plan;
		private final ConcurrentMap<String, dev.mechana.api.ArtifactReference> segments;
		private volatile dev.mechana.api.ArtifactReference finalArtifact;
		private final boolean clientAssembly;
		private final boolean directClient;
		private final Map<String, String> taskArtifacts;
		private final ConcurrentMap<String, String> acceptedLeaseHashes;

		private VideoJob(List<String> inputTokens, dev.mechana.api.ArtifactReference sourceArtifact,
				List<dev.mechana.api.ArtifactReference> workerInputs, Path input, Path output, Path scratch,
				VideoTypes.Plan plan, ConcurrentMap<String, dev.mechana.api.ArtifactReference> segments,
				dev.mechana.api.ArtifactReference finalArtifact, boolean clientAssembly) {
			this(inputTokens, sourceArtifact, workerInputs, input, output, scratch, plan, segments, finalArtifact,
					clientAssembly, false, Map.of(), new ConcurrentHashMap<>());
		}

		private VideoJob(List<String> inputTokens, dev.mechana.api.ArtifactReference sourceArtifact,
				List<dev.mechana.api.ArtifactReference> workerInputs, Path input, Path output, Path scratch,
				VideoTypes.Plan plan, ConcurrentMap<String, dev.mechana.api.ArtifactReference> segments,
				dev.mechana.api.ArtifactReference finalArtifact, boolean clientAssembly, boolean directClient,
				Map<String, String> taskArtifacts, ConcurrentMap<String, String> acceptedLeaseHashes) {
			this.inputTokens = inputTokens;
			this.sourceArtifact = sourceArtifact;
			this.workerInputs = workerInputs;
			this.input = input;
			this.output = output;
			this.scratch = scratch;
			this.plan = plan;
			this.segments = segments;
			this.finalArtifact = finalArtifact;
			this.clientAssembly = clientAssembly;
			this.directClient = directClient;
			this.taskArtifacts = taskArtifacts;
			this.acceptedLeaseHashes = acceptedLeaseHashes;
		}
		List<String> inputTokens() {
			return inputTokens;
		}
		dev.mechana.api.ArtifactReference sourceArtifact() {
			return sourceArtifact;
		}
		List<dev.mechana.api.ArtifactReference> workerInputs() {
			return workerInputs;
		}
		Path input() {
			return input;
		}
		Path output() {
			return output;
		}
		Path scratch() {
			return scratch;
		}
		VideoTypes.Plan plan() {
			return plan;
		}
		ConcurrentMap<String, dev.mechana.api.ArtifactReference> segments() {
			return segments;
		}
		dev.mechana.api.ArtifactReference finalArtifact() {
			return finalArtifact;
		}
		void finalArtifact(dev.mechana.api.ArtifactReference artifact) {
			this.finalArtifact = artifact;
		}
		boolean clientAssembly() {
			return clientAssembly;
		}
		boolean directClient() {
			return directClient;
		}
		Map<String, String> taskArtifacts() {
			return taskArtifacts;
		}
		ConcurrentMap<String, String> acceptedLeaseHashes() {
			return acceptedLeaseHashes;
		}
	}

	private record FractalJob(Path scratch, FractalJobSubmitRequest request,
			ConcurrentMap<String, dev.mechana.api.ArtifactReference> batches,
			ConcurrentMap<String, dev.mechana.api.ArtifactReference> outputs) {
	}

	private record OcrJob(List<String> inputTokens, Path scratch, int firstPage, int pageCount,
			OcrJobSubmitRequest request, ConcurrentMap<String, dev.mechana.api.ArtifactReference> batches,
			ConcurrentMap<String, dev.mechana.api.ArtifactReference> outputs) {
	}

	private record BlenderJob(String inputToken, Path scratch, BlenderJobSubmitRequest request,
			ConcurrentMap<String, dev.mechana.api.ArtifactReference> batches,
			ConcurrentMap<String, dev.mechana.api.ArtifactReference> outputs) {
	}

	private record WorkerActivity(String jobId, String plugin, int progress) {
	}
}
