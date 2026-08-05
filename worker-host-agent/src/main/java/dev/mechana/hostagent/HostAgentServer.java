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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

final class HostAgentServer implements AutoCloseable {
	private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
	private final HttpServer server;
	private final WorkerManager manager;
	private final String token;

	HostAgentServer(AgentConfig config, WorkerManager manager) throws IOException {
		this.manager = manager;
		this.token = config.token();
		server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
		server.createContext("/api/v1/health", this::health);
		server.createContext("/api/v1/workers", this::workers);
		server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
	}

	void start() {
		server.start();
	}
	int port() {
		return server.getAddress().getPort();
	}

	private void health(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			send(exchange, 405, Map.of("error", "Method not allowed"));
			return;
		}
		send(exchange, 200, Map.of("status", "UP"));
	}

	private void workers(HttpExchange exchange) throws IOException {
		if (!authenticated(exchange)) {
			send(exchange, 401, Map.of("error", "Missing or invalid bearer token"));
			return;
		}
		try {
			String method = exchange.getRequestMethod();
			String path = exchange.getRequestURI().getPath();
			if ("GET".equals(method) && "/api/v1/workers".equals(path))
				send(exchange, 200, manager.status());
			else if ("POST".equals(method) && path.endsWith("/start")) {
				StartRequest request = json.readValue(exchange.getRequestBody(), StartRequest.class);
				send(exchange, 200, manager.start(new WorkerManager.LaunchRequest(request.count(),
						request.executionMode(), request.capabilities())));
			} else if ("POST".equals(method) && path.endsWith("/stop"))
				send(exchange, 200, manager.stopAll());
			else
				send(exchange, 404, Map.of("error", "Not found"));
		} catch (IllegalArgumentException failure) {
			send(exchange, 400, Map.of("error", message(failure)));
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			send(exchange, 500, Map.of("error", "Stop interrupted"));
		} catch (Exception failure) {
			send(exchange, 500, Map.of("error", message(failure)));
		}
	}

	private boolean authenticated(HttpExchange exchange) {
		if (token.isBlank())
			return true;
		String supplied = exchange.getRequestHeaders().getFirst("Authorization");
		byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
		return supplied != null && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8));
	}

	private void send(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] content = json.writeValueAsBytes(body);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, content.length);
		try (var output = exchange.getResponseBody()) {
			output.write(content);
		}
	}

	private static String message(Throwable failure) {
		return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
	}
	record StartRequest(int count, WorkerLaunchMode executionMode, String capabilities) {
	}
	public void close() {
		server.stop(0);
	}
}
