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

package dev.mechana.hostagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostAgentServerTest {
	@TempDir
	Path temporary;
	@Test
	void protectsManagementEndpoints() throws Exception {
		AgentConfig config = new AgentConfig("127.0.0.1", 0, "secret", URI.create("http://server:8787"),
				Path.of("java"), Path.of("worker.jar"), temporary, 2, "sleep", Duration.ofMillis(5), "test-host", false,
				temporary.resolve("sandbox"), "fractal-render");
		WorkerManager manager = new WorkerManager(config, (c, d, l) -> new FakeProcess());
		try (HostAgentServer server = new HostAgentServer(config, manager);
				HttpClient client = HttpClient.newHttpClient()) {
			server.start();
			URI uri = URI.create("http://127.0.0.1:" + server.port() + "/api/v1/workers");
			assertEquals(401, client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString())
					.statusCode());
			HttpResponse<String> response = client.send(
					HttpRequest.newBuilder(uri).header("Authorization", "Bearer secret").build(),
					HttpResponse.BodyHandlers.ofString());
			assertEquals(200, response.statusCode());
			assertTrue(response.body().contains("\"runningCount\":0"));

			HttpResponse<String> started = client.send(
					HttpRequest.newBuilder(uri.resolve("/api/v1/workers/start"))
							.header("Authorization", "Bearer secret").header("Content-Type", "application/json")
							.POST(HttpRequest.BodyPublishers.ofString("{\"count\":1}")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertEquals(200, started.statusCode());
			assertTrue(started.body().contains("\"runningCount\":1"));
			assertTrue(started.body().contains("\"id\":\"test-host-"));
			assertTrue(started.body().contains("\"startedAt\":"));
		}
	}
	private static final class FakeProcess implements ManagedProcess {
		public long pid() {
			return 1;
		}
		public boolean isAlive() {
			return true;
		}
		public void destroy() {
		}
		public void destroyForcibly() {
		}
		public boolean waitFor(Duration timeout) {
			return true;
		}
	}
}
