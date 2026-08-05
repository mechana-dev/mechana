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
	enum LaunchMode {
		LEGACY, SANDBOXED
	}
	record Worker(String id, long pid, Instant startedAt, boolean alive) {
	}
	record Status(int requestedCount, int runningCount, String state, List<Worker> workers, String diagnostic,
			LaunchMode launchMode, String capabilities, String sandboxRoot) {
	}
	private record StartRequest(int count, LaunchMode executionMode, String capabilities) {
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
		return start(base, token, count, LaunchMode.LEGACY, "");
	}
	Status start(URI base, String token, int count, LaunchMode mode, String capabilities)
			throws IOException, InterruptedException {
		return send(base, token, "POST", json.writeValueAsString(new StartRequest(count, mode, capabilities)),
				"/api/v1/workers/start");
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
