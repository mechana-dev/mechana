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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mechana.api.WorkUnit;
import dev.mechana.coordinator.InMemoryJobMonitor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobDashboardServerTest {
	@Test
	void servesPluginIndependentDashboardAndStatusJson() throws Exception {
		InMemoryJobMonitor monitor = new InMemoryJobMonitor("job-1", "sleep", Map.of("duration", "5s"));
		monitor.onPlan(1, List.of(new WorkUnit("task-1", "Sleep task", 1, Map.of())));
		try (JobDashboardServer server = new JobDashboardServer(0, monitor);
				HttpClient client = HttpClient.newHttpClient()) {
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());
			HttpResponse<String> page = client.send(HttpRequest.newBuilder(base.resolve("/")).build(),
					HttpResponse.BodyHandlers.ofString());
			HttpResponse<String> status = client.send(HttpRequest.newBuilder(base.resolve("/api/status")).build(),
					HttpResponse.BodyHandlers.ofString());

			assertEquals(200, page.statusCode());
			assertTrue(page.body().contains("Mechana Job"));
			assertTrue(page.body().contains("Work units"));
			assertTrue(page.body().contains("Workers"));
			assertEquals(200, status.statusCode());
			assertTrue(status.body().contains("\"plugin\":\"sleep\""));
			assertTrue(status.body().contains("\"totalWorkUnits\":1"));
		}
	}
}
