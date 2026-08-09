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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mechana.protocol.Messages.JobSubmission;
import dev.mechana.protocol.Messages.TaskLease;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MechanaServerDashboardTest {
	@Test
	void exposesGenericDashboardForSubmittedSleepJobOnLoopback(@TempDir java.nio.file.Path temporary) throws Exception {
		var plugin = Files.createTempFile("mechana-test-plugin", ".jar");
		try (MechanaServer server = new MechanaServer(0, "http://localhost", plugin, 5_000, temporary);
				HttpClient client = HttpClient.newHttpClient()) {
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			HttpRequest submit = HttpRequest.newBuilder(base.resolve("/api/jobs"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"taskCount\":2,\"durationMillis\":1000}")).build();
			HttpResponse<String> submitted = client.send(submit, HttpResponse.BodyHandlers.ofString());
			String jobId = new ObjectMapper().readValue(submitted.body(), JobSubmission.class).jobId();
			HttpResponse<String> page = client.send(
					HttpRequest.newBuilder(base.resolve("/dashboard/jobs/" + jobId)).build(),
					HttpResponse.BodyHandlers.ofString());
			HttpResponse<String> status = client.send(
					HttpRequest.newBuilder(base.resolve("/api/jobs/" + jobId + "/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString());

			assertEquals(202, submitted.statusCode());
			assertEquals(200, page.statusCode());
			assertTrue(page.body().contains("Mechana Job"));
			assertEquals(200, status.statusCode());
			assertTrue(status.body().contains("\"plugin\":\"sleep\""));
			assertTrue(status.body().contains("\"totalWorkUnits\":2"));
		} finally {
			Files.deleteIfExists(plugin);
		}
	}

	@Test
	void exposesPersistentServerDashboardWithWorkersAndJobs(@TempDir java.nio.file.Path temporary) throws Exception {
		var plugin = Files.createTempFile("mechana-test-plugin", ".jar");
		try (MechanaServer server = new MechanaServer(0, "http://localhost", plugin, 5_000, temporary);
				HttpClient client = HttpClient.newHttpClient()) {
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			HttpRequest register = HttpRequest.newBuilder(base.resolve("/api/workers/register"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers
							.ofString("{\"workerId\":\"worker-1\",\"workerAddress\":\"192.0.2.10\","
									+ "\"supportedPlugins\":[\"sleep\"]}"))
					.build();
			HttpRequest submit = HttpRequest.newBuilder(base.resolve("/api/jobs"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"taskCount\":2,\"durationMillis\":1000}")).build();

			assertEquals(200, client.send(register, HttpResponse.BodyHandlers.discarding()).statusCode());
			String jobId = new ObjectMapper()
					.readValue(client.send(submit, HttpResponse.BodyHandlers.ofString()).body(), JobSubmission.class)
					.jobId();
			HttpRequest lease = HttpRequest.newBuilder(base.resolve("/api/workers/worker-1/lease"))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers
							.ofString("{\"workerAddress\":\"192.0.2.10\",\"supportedPlugins\":[\"sleep\"]}"))
					.build();
			assertEquals(200, client.send(lease, HttpResponse.BodyHandlers.discarding()).statusCode());
			HttpResponse<String> page = client.send(HttpRequest.newBuilder(base.resolve("/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString());
			HttpResponse<String> status = client.send(HttpRequest.newBuilder(base.resolve("/api/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString());

			assertEquals(200, page.statusCode());
			assertTrue(page.body().contains("Mechana Server"));
			assertTrue(page.body().contains("/dashboard/jobs/"));
			assertTrue(page.body().contains("capabilitySummary"));
			assertTrue(page.body().contains("capabilityDetails"));
			assertTrue(page.body().contains("FFmpeg video"));
			assertTrue(page.body().contains("Linux sandbox"));
			assertTrue(page.body().contains("Windows sandbox"));
			assertTrue(page.body().contains("Purge all"));
			assertTrue(page.body().contains("/api/jobs/completed"));
			assertEquals(200, status.statusCode());
			assertTrue(status.body().contains("\"serverPid\":"));
			assertTrue(status.body().contains("\"serverDate\":"));
			assertTrue(status.body().contains("\"serverTime\":"));
			assertTrue(status.body().contains("\"serverUptime\":"));
			assertTrue(status.body().contains("\"connectedWorkers\":1"));
			assertTrue(status.body().contains("\"address\":\"192.0.2.10\""));
			assertTrue(status.body().contains("\"activity\":\"sleep\""));
			assertTrue(status.body().contains("\"progress\":0"));
			assertTrue(status.body().contains("\"jobId\":\"" + jobId + "\""));
			assertTrue(status.body().contains("\"capabilities\":[\"sleep\"]"));
			assertTrue(status.body().contains(jobId));
			assertTrue(status.body().contains("\"activeJobs\":1"));

			HttpRequest heartbeat = HttpRequest.newBuilder(base.resolve("/api/workers/worker-1/heartbeat"))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers
							.ofString("{\"workerAddress\":\"192.0.2.10\",\"supportedPlugins\":[\"sleep\"]}"))
					.build();
			assertEquals(204, client.send(heartbeat, HttpResponse.BodyHandlers.discarding()).statusCode());

			HttpRequest disconnect = HttpRequest.newBuilder(base.resolve("/api/workers/worker-1/disconnect"))
					.POST(HttpRequest.BodyPublishers.noBody()).build();
			assertEquals(204, client.send(disconnect, HttpResponse.BodyHandlers.discarding()).statusCode());
			String disconnected = client.send(HttpRequest.newBuilder(base.resolve("/api/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString()).body();
			assertTrue(disconnected.contains("\"connectedWorkers\":0"));
			assertTrue(disconnected.contains("\"registeredWorkers\":0"));
			assertFalse(disconnected.contains("\"id\":\"worker-1\""));

			HttpRequest timeoutRegistration = HttpRequest.newBuilder(base.resolve("/api/workers/register"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(
							"{\"workerId\":\"worker-timeout\",\"workerAddress\":\"192.0.2.11\",\"supportedPlugins\":[\"sleep\"]}"))
					.build();
			assertEquals(200, client.send(timeoutRegistration, HttpResponse.BodyHandlers.discarding()).statusCode());
			long beforeTimeout = System.currentTimeMillis();
			server.reapExpiredWorkers(beforeTimeout + TimeUnit.SECONDS.toMillis(15) + 1);
			String timedOut = client.send(HttpRequest.newBuilder(base.resolve("/api/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString()).body();
			assertTrue(timedOut.contains("\"registeredWorkers\":1"));
			assertTrue(timedOut.contains("\"state\":\"DISCONNECTED\""));
			assertTrue(timedOut.contains("\"activity\":\"OFFLINE\""));

			server.reapExpiredWorkers(beforeTimeout + TimeUnit.SECONDS.toMillis(25) + 2);
			String forgotten = client.send(HttpRequest.newBuilder(base.resolve("/api/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString()).body();
			assertTrue(forgotten.contains("\"connectedWorkers\":0"));
			assertTrue(forgotten.contains("\"registeredWorkers\":0"));
			assertFalse(forgotten.contains("\"id\":\"worker-timeout\""));
		} finally {
			Files.deleteIfExists(plugin);
		}
	}

	@Test
	void restartsServerOnlyThroughConfiguredLoopbackAction(@TempDir java.nio.file.Path temporary) throws Exception {
		var plugin = Files.createTempFile("mechana-test-plugin", ".jar");
		try (MechanaServer server = new MechanaServer(0, "http://localhost", plugin, 5_000, temporary);
				HttpClient client = HttpClient.newHttpClient()) {
			CountDownLatch restarted = new CountDownLatch(1);
			server.onRestart(restarted::countDown);
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			HttpResponse<String> page = client.send(HttpRequest.newBuilder(base.resolve("/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString());
			HttpRequest restart = HttpRequest.newBuilder(base.resolve("/api/server/restart"))
					.POST(HttpRequest.BodyPublishers.noBody()).build();

			assertTrue(page.body().contains("Restart server"));
			assertEquals(202, client.send(restart, HttpResponse.BodyHandlers.discarding()).statusCode());
			assertTrue(restarted.await(1, TimeUnit.SECONDS));
		} finally {
			Files.deleteIfExists(plugin);
		}
	}

	@Test
	void stopsServerOnlyThroughConfiguredLoopbackAction(@TempDir java.nio.file.Path temporary) throws Exception {
		var plugin = Files.createTempFile("mechana-test-plugin", ".jar");
		try (MechanaServer server = new MechanaServer(0, "http://localhost", plugin, 5_000, temporary);
				HttpClient client = HttpClient.newHttpClient()) {
			CountDownLatch stopped = new CountDownLatch(1);
			server.onStop(stopped::countDown);
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			HttpResponse<String> page = client.send(HttpRequest.newBuilder(base.resolve("/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString());
			HttpRequest stop = HttpRequest.newBuilder(base.resolve("/api/server/stop"))
					.POST(HttpRequest.BodyPublishers.noBody()).build();

			assertTrue(page.body().contains("Stop server"));
			assertEquals(202, client.send(stop, HttpResponse.BodyHandlers.discarding()).statusCode());
			assertTrue(stopped.await(1, TimeUnit.SECONDS));
		} finally {
			Files.deleteIfExists(plugin);
		}
	}

	@Test
	void completedJobSurvivesRestartProvidesArtifactsAndCanBePurged(@TempDir java.nio.file.Path temporary)
			throws Exception {
		var plugin = temporary.resolve("plugin.jar");
		Files.writeString(plugin, "plugin");
		ObjectMapper json = new ObjectMapper();
		String jobId;
		try (MechanaServer server = new MechanaServer(0, "http://localhost", plugin, 5_000, temporary);
				HttpClient client = HttpClient.newHttpClient()) {
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			HttpRequest submit = HttpRequest.newBuilder(base.resolve("/api/jobs"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"taskCount\":1,\"durationMillis\":1}")).build();
			jobId = json
					.readValue(client.send(submit, HttpResponse.BodyHandlers.ofString()).body(), JobSubmission.class)
					.jobId();
			HttpRequest leaseRequest = HttpRequest.newBuilder(base.resolve("/api/workers/worker-1/lease"))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers
							.ofString("{\"workerAddress\":\"192.0.2.10\",\"supportedPlugins\":[\"sleep\"]}"))
					.build();
			TaskLease lease = json.readValue(client.send(leaseRequest, HttpResponse.BodyHandlers.ofString()).body(),
					TaskLease.class);
			HttpRequest complete = HttpRequest
					.newBuilder(base.resolve("/api/workers/worker-1/tasks/" + lease.taskId() + "/complete"))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers
							.ofString(json.writeValueAsString(Map.of("leaseToken", lease.leaseToken()))))
					.build();
			assertEquals(204, client.send(complete, HttpResponse.BodyHandlers.discarding()).statusCode());
		}

		try (MechanaServer restarted = new MechanaServer(0, "http://localhost", plugin, 5_000, temporary);
				HttpClient client = HttpClient.newHttpClient()) {
			restarted.start();
			URI base = URI.create("http://127.0.0.1:" + restarted.port());
			String dashboard = client.send(HttpRequest.newBuilder(base.resolve("/api/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString()).body();
			assertTrue(dashboard.contains("\"completedJobs\":1"));
			assertTrue(dashboard.contains(jobId));

			String detail = client
					.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + jobId + "/dashboard")).build(),
							HttpResponse.BodyHandlers.ofString())
					.body();
			assertTrue(detail.contains("\"completed\":true"));
			assertTrue(detail.contains("\"completedAt\":"));
			assertTrue(detail.contains("job-summary.json"));
			assertTrue(detail.contains("\"provider\":\"server-local\""));
			assertTrue(detail.contains("\"key\":\"jobs/" + jobId + "/artifacts/job-summary.json\""));
			assertTrue(detail.contains("\"sha256\":"));
			HttpResponse<String> page = client.send(
					HttpRequest.newBuilder(base.resolve("/dashboard/jobs/" + jobId)).build(),
					HttpResponse.BodyHandlers.ofString());
			assertTrue(page.body().contains("Show in Finder"));
			HttpResponse<String> artifact = client.send(
					HttpRequest.newBuilder(base.resolve("/api/jobs/" + jobId + "/artifacts/job-summary.json")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertEquals(200, artifact.statusCode());
			assertTrue(artifact.body().contains(jobId));

			HttpResponse<Void> purged = client.send(
					HttpRequest.newBuilder(base.resolve("/api/jobs/" + jobId)).DELETE().build(),
					HttpResponse.BodyHandlers.discarding());
			assertEquals(204, purged.statusCode());
			String afterPurge = client.send(HttpRequest.newBuilder(base.resolve("/api/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString()).body();
			assertTrue(afterPurge.contains("\"completedJobs\":0"));
			assertTrue(Files.notExists(temporary.resolve("jobs").resolve(jobId)));
		}
	}

	@Test
	void abortMovesActiveJobIntoDurableCompletedJobs(@TempDir java.nio.file.Path temporary) throws Exception {
		var plugin = temporary.resolve("plugin.jar");
		Files.writeString(plugin, "plugin");
		try (MechanaServer server = new MechanaServer(0, "http://localhost", plugin, 5_000, temporary);
				HttpClient client = HttpClient.newHttpClient()) {
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			HttpRequest submit = HttpRequest.newBuilder(base.resolve("/api/jobs"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"taskCount\":2,\"durationMillis\":1000}")).build();
			String jobId = new ObjectMapper()
					.readValue(client.send(submit, HttpResponse.BodyHandlers.ofString()).body(), JobSubmission.class)
					.jobId();

			HttpResponse<Void> aborted = client
					.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + jobId + "/abort"))
							.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
			assertEquals(204, aborted.statusCode());
			String dashboard = client.send(HttpRequest.newBuilder(base.resolve("/api/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString()).body();
			assertTrue(dashboard.contains("\"activeJobs\":0"));
			assertTrue(dashboard.contains("\"completedJobs\":1"));
			assertTrue(dashboard.contains("\"stage\":\"CANCELLED\""));
			assertTrue(dashboard.contains("\"completedAt\":"));
			String detail = client
					.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + jobId + "/dashboard")).build(),
							HttpResponse.BodyHandlers.ofString())
					.body();
			assertTrue(detail.contains("\"abortable\":false"));
			assertTrue(detail.contains("\"completed\":true"));
			HttpResponse<Void> purged = client.send(
					HttpRequest.newBuilder(base.resolve("/api/jobs/completed")).DELETE().build(),
					HttpResponse.BodyHandlers.discarding());
			assertEquals(204, purged.statusCode());
			String afterPurge = client.send(HttpRequest.newBuilder(base.resolve("/api/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString()).body();
			assertTrue(afterPurge.contains("\"activeJobs\":0"));
			assertTrue(afterPurge.contains("\"completedJobs\":0"));
			assertTrue(Files.notExists(temporary.resolve("jobs").resolve(jobId)));
		}
	}

	@Test
	void pauseResumeAndResumeAsNewAreAvailableThroughDashboardApi(@TempDir java.nio.file.Path temporary)
			throws Exception {
		var plugin = temporary.resolve("plugin.jar");
		Files.writeString(plugin, "plugin");
		ObjectMapper json = new ObjectMapper();
		try (MechanaServer server = new MechanaServer(0, "http://localhost", plugin, 5_000, temporary);
				HttpClient client = HttpClient.newHttpClient()) {
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			HttpRequest submit = HttpRequest.newBuilder(base.resolve("/api/jobs"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"taskCount\":2,\"durationMillis\":1000}")).build();
			String jobId = json
					.readValue(client.send(submit, HttpResponse.BodyHandlers.ofString()).body(), JobSubmission.class)
					.jobId();

			assertEquals(204, post(client, base.resolve("/api/jobs/" + jobId + "/pause")));
			String paused = client
					.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + jobId + "/dashboard")).build(),
							HttpResponse.BodyHandlers.ofString())
					.body();
			assertTrue(paused.contains("\"stage\":\"PAUSED\""));
			assertTrue(paused.contains("\"resumable\":true"));

			assertEquals(204, post(client, base.resolve("/api/jobs/" + jobId + "/resume")));
			assertEquals(204, post(client, base.resolve("/api/jobs/" + jobId + "/abort")));
			HttpRequest resumeAsNew = HttpRequest.newBuilder(base.resolve("/api/jobs/" + jobId + "/resume-as-new"))
					.POST(HttpRequest.BodyPublishers.noBody()).build();
			HttpResponse<String> resumed = client.send(resumeAsNew, HttpResponse.BodyHandlers.ofString());
			assertEquals(202, resumed.statusCode());
			String resumedId = json.readValue(resumed.body(), JobSubmission.class).jobId();
			String detail = client
					.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + resumedId + "/dashboard")).build(),
							HttpResponse.BodyHandlers.ofString())
					.body();
			assertTrue(detail.contains("\"resumedFromJobId\":\"" + jobId + "\""));
		}
	}

	@Test
	void assemblesFractalBatchesIntoDurableArtifacts(@TempDir java.nio.file.Path temporary) throws Exception {
		var plugin = temporary.resolve("plugin.jar");
		Files.writeString(plugin, "plugin");
		ObjectMapper json = new ObjectMapper();
		try (MechanaServer server = new MechanaServer(0, "http://localhost", plugin, plugin, plugin, 5_000, temporary);
				HttpClient client = HttpClient.newHttpClient()) {
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			HttpRequest submit = HttpRequest.newBuilder(base.resolve("/api/jobs/fractal"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(
							"{\"imageCount\":2,\"taskCount\":1,\"width\":64,\"height\":64,\"maxIterations\":32,\"seed\":7}"))
					.build();
			String jobId = json
					.readValue(client.send(submit, HttpResponse.BodyHandlers.ofString()).body(), JobSubmission.class)
					.jobId();
			HttpRequest leaseRequest = HttpRequest.newBuilder(base.resolve("/api/workers/worker-1/lease"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers
							.ofString("{\"workerAddress\":\"192.0.2.10\",\"supportedPlugins\":[\"fractal-render\"]}"))
					.build();
			TaskLease lease = json.readValue(client.send(leaseRequest, HttpResponse.BodyHandlers.ofString()).body(),
					TaskLease.class);
			Path batch = temporary.resolve("batch-00000.zip");
			try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(batch))) {
				for (int index = 0; index < 2; index++) {
					output.putNextEntry(new ZipEntry("fractal-%05d.png".formatted(index)));
					ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", output);
					output.closeEntry();
				}
			}
			HttpRequest upload = HttpRequest
					.newBuilder(base
							.resolve("/api/workers/worker-1/tasks/" + lease.taskId() + "/artifacts/batch-00000.zip"))
					.header("X-Mechana-Lease", lease.leaseToken()).PUT(HttpRequest.BodyPublishers.ofFile(batch))
					.build();
			assertEquals(204, client.send(upload, HttpResponse.BodyHandlers.discarding()).statusCode());
			HttpRequest complete = HttpRequest
					.newBuilder(base.resolve("/api/workers/worker-1/tasks/" + lease.taskId() + "/complete"))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers
							.ofString(json.writeValueAsString(Map.of("leaseToken", lease.leaseToken()))))
					.build();
			assertEquals(204, client.send(complete, HttpResponse.BodyHandlers.discarding()).statusCode());
			String detail = client
					.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + jobId + "/dashboard")).build(),
							HttpResponse.BodyHandlers.ofString())
					.body();
			assertTrue(detail.contains("\"stage\":\"SUCCEEDED\""));
			assertTrue(detail.contains("fractal-collection.zip"));
			assertTrue(detail.contains("contact-sheet.png"));
			assertTrue(detail.contains("fractal-00000.png"));
			assertTrue(detail.contains("\"provider\":\"server-local\""));
			assertTrue(detail.contains("\"key\":\"jobs/" + jobId + "/artifacts/fractal-collection.zip\""));
			assertTrue(detail.contains("\"sha256\":"));
		}
	}

	@Test
	void rasterizesPdfAndAssemblesOcrBatchesIntoMarkdown(@TempDir Path temporary) throws Exception {
		Path plugin = temporary.resolve("plugin.jar");
		Files.writeString(plugin, "plugin");
		Path pdf = temporary.resolve("book.pdf");
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.addPage(new PDPage());
			document.save(pdf.toFile());
		}
		ObjectMapper json = new ObjectMapper();
		int port;
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		try (MechanaServer server = new MechanaServer(port, "http://127.0.0.1:" + port, plugin, plugin, plugin, plugin,
				5_000, temporary.resolve("data")); HttpClient client = HttpClient.newHttpClient()) {
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			String request = json.writeValueAsString(Map.of("sourcePath", pdf.toString(), "taskCount", 1, "dpi", 150,
					"language", "eng", "title", "Test Book"));
			HttpResponse<String> submitted = client.send(
					HttpRequest.newBuilder(base.resolve("/api/jobs/ocr")).header("Content-Type", "application/json")
							.POST(HttpRequest.BodyPublishers.ofString(request)).build(),
					HttpResponse.BodyHandlers.ofString());
			assertEquals(202, submitted.statusCode());
			String jobId = json.readValue(submitted.body(), JobSubmission.class).jobId();
			HttpRequest leaseRequest = HttpRequest.newBuilder(base.resolve("/api/workers/worker-1/lease"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers
							.ofString("{\"workerAddress\":\"192.0.2.10\",\"supportedPlugins\":[\"ocr-tesseract\"]}"))
					.build();
			TaskLease lease = json.readValue(client.send(leaseRequest, HttpResponse.BodyHandlers.ofString()).body(),
					TaskLease.class);
			assertEquals("ocr-tesseract", lease.pluginId());
			assertEquals(2, Integer.parseInt(lease.parameters().get("pageCount")));
			HttpResponse<byte[]> page = client.send(
					HttpRequest.newBuilder(URI.create(lease.parameters().get("pageUrl.0"))).build(),
					HttpResponse.BodyHandlers.ofByteArray());
			assertEquals(200, page.statusCode());
			assertTrue(page.body().length > 0);
			assertTrue(page.headers().firstValue("X-Checksum-Sha256").orElseThrow().matches("[0-9a-f]{64}"));

			Path batch = temporary.resolve("ocr-batch-00000.zip");
			try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(batch))) {
				for (int index = 1; index <= 2; index++) {
					output.putNextEntry(new ZipEntry("page-%06d.txt".formatted(index)));
					output.write(("Text from page " + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
					output.closeEntry();
				}
			}
			HttpRequest upload = HttpRequest
					.newBuilder(base.resolve(
							"/api/workers/worker-1/tasks/" + lease.taskId() + "/artifacts/ocr-batch-00000.zip"))
					.header("X-Mechana-Lease", lease.leaseToken()).PUT(HttpRequest.BodyPublishers.ofFile(batch))
					.build();
			assertEquals(204, client.send(upload, HttpResponse.BodyHandlers.discarding()).statusCode());
			HttpRequest complete = HttpRequest
					.newBuilder(base.resolve("/api/workers/worker-1/tasks/" + lease.taskId() + "/complete"))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers
							.ofString(json.writeValueAsString(Map.of("leaseToken", lease.leaseToken()))))
					.build();
			assertEquals(204, client.send(complete, HttpResponse.BodyHandlers.discarding()).statusCode());
			String detail = client
					.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + jobId + "/dashboard")).build(),
							HttpResponse.BodyHandlers.ofString())
					.body();
			assertTrue(detail.contains("\"stage\":\"SUCCEEDED\""));
			assertTrue(detail.contains("document.md"));
			assertTrue(detail.contains("document.tex"));
			assertTrue(detail.contains("page-000001.txt"));
			assertTrue(detail.contains("\"key\":\"jobs/" + jobId + "/artifacts/document.md\""));
			assertTrue(detail.contains("\"sha256\":"));
		}
	}

	@Test
	void storesBlenderSceneAndFrameBatchAsVerifiedArtifacts(@TempDir Path temporary) throws Exception {
		Path plugin = temporary.resolve("plugin.jar");
		Path scene = temporary.resolve("scene.blend");
		Files.writeString(plugin, "plugin");
		Files.writeString(scene, "BLENDER-v300");
		ObjectMapper json = new ObjectMapper();
		Path data = temporary.resolve("data");
		int port;
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		try (MechanaServer server = new MechanaServer(port, "http://127.0.0.1:" + port, plugin, plugin, plugin, plugin,
				plugin,
				5_000, data); HttpClient client = HttpClient.newHttpClient()) {
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			String request = json.writeValueAsString(Map.of("sourcePath", scene.toString(), "firstFrame", 1,
					"lastFrame", 1, "taskCount", 1, "width", 64, "height", 64, "samples", 1, "fps", 24));
			String jobId = json.readValue(client.send(HttpRequest.newBuilder(base.resolve("/api/jobs/blender"))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request)).build(),
					HttpResponse.BodyHandlers.ofString()).body(), JobSubmission.class).jobId();
			TaskLease lease = json.readValue(client.send(HttpRequest.newBuilder(base.resolve("/api/workers/worker-1/lease"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(
							"{\"workerAddress\":\"192.0.2.10\",\"supportedPlugins\":[\"blender-render\"]}"))
					.build(), HttpResponse.BodyHandlers.ofString()).body(), TaskLease.class);
			HttpResponse<byte[]> stagedScene = client.send(
					HttpRequest.newBuilder(URI.create(lease.parameters().get("inputUrl"))).build(),
					HttpResponse.BodyHandlers.ofByteArray());
			assertEquals(200, stagedScene.statusCode());
			assertEquals("BLENDER-v300", new String(stagedScene.body(), java.nio.charset.StandardCharsets.UTF_8));
			assertTrue(stagedScene.headers().firstValue("X-Checksum-Sha256").orElseThrow().matches("[0-9a-f]{64}"));

			byte[] frames = "frame archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			HttpRequest upload = HttpRequest.newBuilder(base.resolve(
					"/api/workers/worker-1/tasks/" + lease.taskId() + "/artifacts/frames-00000.zip"))
					.header("X-Mechana-Lease", lease.leaseToken()).PUT(HttpRequest.BodyPublishers.ofByteArray(frames)).build();
			assertEquals(204, client.send(upload, HttpResponse.BodyHandlers.discarding()).statusCode());
			assertArrayEquals(frames,
					Files.readAllBytes(data.resolve("jobs/" + jobId + "/intermediate/frames-00000.zip")));
		}
	}

	private static int post(HttpClient client, URI uri) throws Exception {
		return client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.discarding()).statusCode();
	}
}
