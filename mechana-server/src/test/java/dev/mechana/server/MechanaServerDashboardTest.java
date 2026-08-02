package dev.mechana.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mechana.protocol.Messages.JobSubmission;
import dev.mechana.protocol.Messages.TaskLease;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;
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
			assertEquals(200, status.statusCode());
			assertTrue(status.body().contains("\"serverPid\":"));
			assertTrue(status.body().contains("\"serverDate\":"));
			assertTrue(status.body().contains("\"serverTime\":"));
			assertTrue(status.body().contains("\"serverUptime\":"));
			assertTrue(status.body().contains("\"connectedWorkers\":1"));
			assertTrue(status.body().contains("\"address\":\"192.0.2.10\""));
			assertTrue(status.body().contains("\"activity\":\"WORKING\""));
			assertTrue(status.body().contains("\"jobId\":\"" + jobId + "\""));
			assertTrue(status.body().contains("\"capabilities\":[\"sleep\"]"));
			assertTrue(status.body().contains(jobId));
			assertTrue(status.body().contains("\"activeJobs\":1"));

			HttpRequest disconnect = HttpRequest.newBuilder(base.resolve("/api/workers/worker-1/disconnect"))
					.POST(HttpRequest.BodyPublishers.noBody()).build();
			assertEquals(204, client.send(disconnect, HttpResponse.BodyHandlers.discarding()).statusCode());
			String disconnected = client.send(HttpRequest.newBuilder(base.resolve("/api/dashboard")).build(),
					HttpResponse.BodyHandlers.ofString()).body();
			assertTrue(disconnected.contains("\"connectedWorkers\":0"));
			assertTrue(disconnected.contains("\"registeredWorkers\":1"));
			assertTrue(disconnected.contains("\"state\":\"DISCONNECTED\""));
			assertTrue(disconnected.contains("\"activity\":\"OFFLINE\""));
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
			assertTrue(detail.contains("job-summary.json"));
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
			String detail = client
					.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + jobId + "/dashboard")).build(),
							HttpResponse.BodyHandlers.ofString())
					.body();
			assertTrue(detail.contains("\"abortable\":false"));
			assertTrue(detail.contains("\"completed\":true"));
		}
	}
}
