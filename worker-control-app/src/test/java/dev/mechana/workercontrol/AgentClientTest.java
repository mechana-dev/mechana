package dev.mechana.workercontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentClientTest {
	@Test
	void sendsTokenAndStartCountAndReadsStatus() throws Exception {
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> body = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/v1/workers/start", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] response = "{\"requestedCount\":2,\"runningCount\":2,\"state\":\"RUNNING\",\"workers\":[],\"diagnostic\":\"\"}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		try {
			AgentClient.Status status = new AgentClient()
					.start(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "secret", 2);
			assertEquals(2, status.runningCount());
			assertEquals("Bearer secret", authorization.get());
			assertTrue(body.get().contains("\"count\":2"));
		} finally {
			server.stop(0);
		}
	}
}
