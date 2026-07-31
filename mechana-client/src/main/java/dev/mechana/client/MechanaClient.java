package dev.mechana.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mechana.protocol.Messages.JobStatusResponse;
import dev.mechana.protocol.Messages.JobSubmission;
import dev.mechana.protocol.Messages.JobSubmitRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Minimal client for job submission and completion observation. */
public final class MechanaClient {

	private final ObjectMapper json = new ObjectMapper();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final URI server;

	public MechanaClient(URI server) {
		String value = Objects.requireNonNull(server, "server").toString();
		this.server = URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
	}

	public String submit(int taskCount, long durationMillis) throws IOException, InterruptedException {
		byte[] body = json.writeValueAsBytes(new JobSubmitRequest(taskCount, durationMillis));
		HttpRequest request = HttpRequest.newBuilder(server.resolve("/api/jobs")).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		requireStatus(response, 202);
		return json.readValue(response.body(), JobSubmission.class).jobId();
	}

	public JobStatusResponse status(String jobId) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(server.resolve("/api/jobs/" + jobId))
				.timeout(Duration.ofSeconds(10)).GET().build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		requireStatus(response, 200);
		return json.readValue(response.body(), JobStatusResponse.class);
	}

	public JobStatusResponse waitForCompletion(String jobId, long pollMillis) throws IOException, InterruptedException {
		while (true) {
			JobStatusResponse current = status(jobId);
			System.out.printf("Job %s: %s %d%%%n", jobId, current.state(), current.progress());
			if ("SUCCEEDED".equals(current.state())) {
				return current;
			}
			Thread.sleep(pollMillis);
		}
	}

	private static void requireStatus(HttpResponse<byte[]> response, int expected) throws IOException {
		if (response.statusCode() != expected) {
			throw new IOException("Server returned HTTP " + response.statusCode() + ": "
					+ new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
		}
	}
}
