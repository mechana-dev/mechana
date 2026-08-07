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
package dev.mechana.runtime.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WindowsSandboxIT {
	private Path temporary;
	private WindowsSandbox sandbox;
	private AttemptWorkspace workspace;
	private SandboxPolicy policy;
	private String cmd;

	@BeforeEach
	void setUp() throws Exception {
		sandbox = new WindowsSandbox();
		assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));
		assumeTrue(sandbox.supportsCurrentHost(), "Windows AppContainer probe unavailable");
		Path sandboxRoot = Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "Mechana",
				"sandbox");
		Files.createDirectories(sandboxRoot);
		temporary = Files.createTempDirectory(sandboxRoot, "windows-sandbox-it-");
		workspace = AttemptWorkspace.create(temporary, "job", "attempt");
		policy = new SandboxPolicy(TrustMode.SANDBOXED, false, 1, 256L * 1024 * 1024, 64L * 1024 * 1024,
				Duration.ofSeconds(30), 3);
		cmd = System.getenv().getOrDefault("ComSpec", "C:\\Windows\\System32\\cmd.exe");
	}

	@AfterEach
	void cleanUp() throws Exception {
		if (temporary == null || !Files.exists(temporary))
			return;
		try (var paths = Files.walk(temporary)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}

	@Test
	void allowsWorkspaceOutputButDeniesInputAndOutsideWrites() throws Exception {
		Path input = Files.writeString(workspace.input().resolve("seed.txt"), "seed");
		Path output = workspace.output().resolve("allowed.txt");
		Path outside = temporary.resolve("outside.txt");
		assertEquals(0, execute(List.of(cmd, "/d", "/c", "echo allowed>\"" + output + "\"")).exitCode());
		assertEquals("allowed", Files.readString(output).strip());
		assertNotEquals(0, execute(List.of(cmd, "/d", "/c", "echo denied>\"" + input + "\"")).exitCode());
		assertEquals("seed", Files.readString(input));
		assertNotEquals(0, execute(List.of(cmd, "/d", "/c", "echo denied>\"" + outside + "\"")).exitCode());
		assertFalse(Files.exists(outside));
	}

	@Test
	void deniesReadsFromActualUserHome() throws Exception {
		Path secret = Files.writeString(Path.of(System.getProperty("user.home"), "mechana-sandbox-denied-read.txt"),
				"not available to plugin");
		try {
			assertNotEquals(0, execute(List.of(cmd, "/d", "/c", "type", secret.toString())).exitCode());
		} finally {
			Files.deleteIfExists(secret);
		}
	}

	@Test
	void deniesLoopbackNetwork() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.start();
		try {
			String curl = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "curl.exe")
					.toString();
			assertNotEquals(0, execute(List.of(curl, "--connect-timeout", "2", "--max-time", "3",
					"http://127.0.0.1:" + server.getAddress().getPort())).exitCode());
		} finally {
			server.stop(0);
		}
	}

	private SandboxResult execute(List<String> command) throws Exception {
		Map<String, String> environment = new HashMap<>();
		for (String name : List.of("SystemRoot", "WINDIR", "ComSpec", "PATH")) {
			String value = System.getenv(name);
			if (value != null)
				environment.put(name, value);
		}
		return sandbox.execute(new SandboxRequest(command, environment, workspace, policy), new AtomicBoolean());
	}
}
