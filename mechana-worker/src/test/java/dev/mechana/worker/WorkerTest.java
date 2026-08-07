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

package dev.mechana.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.mechana.api.JobId;
import dev.mechana.protocol.ExecutionRequest;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerTest {
	@Test
	void heartbeatUsesDedicatedPlatformThread() throws Exception {
		Thread heartbeat = WorkerAgent.heartbeatThread("heartbeat-test", () -> {
		});
		heartbeat.join(Duration.ofSeconds(2));

		assertTrue(!heartbeat.isVirtual());
		assertTrue(heartbeat.isDaemon());
		assertTrue(heartbeat.getPriority() > Thread.NORM_PRIORITY);
	}

	@Test
	void recognizesSupportedTaskType() {
		Worker worker = new Worker("example");

		assertTrue(worker.supports(new ExecutionRequest(JobId.random(), "example", new byte[0])));
	}

	@Test
	void retriesTransientDownloadFailures() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/input", exchange -> {
			int request = requests.incrementAndGet();
			byte[] body = request < 3 ? new byte[0] : "page".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(request < 3 ? 503 : 200, body.length);
			try (var output = exchange.getResponseBody()) {
				output.write(body);
			}
		});
		server.start();
		try {
			URI input = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/input");
			assertArrayEquals("page".getBytes(StandardCharsets.UTF_8), WorkerAgent
					.downloadBytes(HttpClient.newHttpClient(), input, Duration.ofSeconds(5), "Test download"));
			assertEquals(3, requests.get());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rebasesCoordinatorLoopbackDownloadsForRemoteWorkers() {
		URI coordinator = URI.create("http://coordinator.example:8787");
		assertEquals(URI.create("http://coordinator.example:8787/api/plugins/ocr/1.0?token=abc"),
				WorkerAgent.resolveCoordinatorUri(coordinator, "http://localhost:8787/api/plugins/ocr/1.0?token=abc"));
		assertEquals(URI.create("https://cdn.example/plugin.jar"),
				WorkerAgent.resolveCoordinatorUri(coordinator, "https://cdn.example/plugin.jar"));
	}
}
