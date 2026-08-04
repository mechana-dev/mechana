package dev.mechana.workercontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class AgentClient {
	record Worker(String id, long pid, Instant startedAt, boolean alive) {
	}
	record Status(int requestedCount, int runningCount, String state, List<Worker> workers, String diagnostic) {
	}

	private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
	private final HttpClient http;

	AgentClient() {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
	}
	AgentClient(HttpClient http) {
		this.http = http;
	}

	Status status(URI base, String token) throws IOException, InterruptedException {
		return send(base, token, "GET", "", "/api/v1/workers");
	}
	Status start(URI base, String token, int count) throws IOException, InterruptedException {
		return send(base, token, "POST", "{\"count\":" + count + "}", "/api/v1/workers/start");
	}
	Status stop(URI base, String token) throws IOException, InterruptedException {
		return send(base, token, "POST", "{}", "/api/v1/workers/stop");
	}

	private Status send(URI base, String token, String method, String body, String path)
			throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
				.header("Accept", "application/json");
		if (!token.isBlank())
			builder.header("Authorization", "Bearer " + token);
		if ("POST".equals(method))
			builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
		else
			builder.GET();
		HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200)
			throw new IOException("Agent returned HTTP " + response.statusCode() + ": " + response.body());
		return json.readValue(response.body(), Status.class);
	}
}
