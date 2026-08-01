package dev.mechana.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VideoJobDashboardServerTest {
	@Test
	void servesDashboardAndStatusJson() throws Exception {
		VideoJobMonitor monitor = new VideoJobMonitor(Path.of("input.mp4"), Path.of("output.mkv"));
		try (VideoJobDashboardServer server = new VideoJobDashboardServer(0, monitor);
				HttpClient client = HttpClient.newHttpClient()) {
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			HttpResponse<String> page = client.send(HttpRequest.newBuilder(base.resolve("/")).build(),
					HttpResponse.BodyHandlers.ofString());
			HttpResponse<String> status = client.send(HttpRequest.newBuilder(base.resolve("/api/status")).build(),
					HttpResponse.BodyHandlers.ofString());

			assertEquals(200, page.statusCode());
			assertTrue(page.body().contains("Mechana Video Job"));
			assertTrue(page.body().contains("Segments"));
			assertTrue(page.body().contains("Workers"));
			assertEquals(200, status.statusCode());
			assertTrue(status.body().contains("\"stage\":\"STARTING\""));
			assertTrue(status.body().contains("\"activeWorkers\":0"));
		}
	}
}
