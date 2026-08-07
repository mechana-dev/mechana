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

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone probe for the Windows AppContainer and Job Object backend. */
public final class WindowsSandboxProbe {
	private WindowsSandboxProbe() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		WindowsSandbox sandbox = new WindowsSandbox();
		if (!sandbox.supportsCurrentHost())
			throw new IllegalStateException(
					"Windows AppContainer or Job Objects are unavailable: " + sandbox.supportFailure());
		Path probeBase = Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "Mechana", "sandbox");
		Files.createDirectories(probeBase);
		Path root = Files.createTempDirectory(probeBase, "sandbox-probe-");
		System.out.println("workspaceRoot=" + root);
		try {
			AttemptWorkspace workspace = AttemptWorkspace.create(root, "probe", "attempt");
			SandboxPolicy policy = policy(Duration.ofSeconds(15));
			Path allowed = workspace.output().resolve("allowed.txt");
			Path forbidden = root.resolve("forbidden.txt");
			requireExit(sandbox, workspace, policy, command("echo allowed>" + allowed), 0, "workspace output write");
			if (!Files.exists(allowed))
				throw new IllegalStateException("workspace output was not created");
			Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java.exe");
			requireExit(sandbox, workspace, policy, List.of(javaBinary.toString(), "-Xmx32m", "-version"), 0,
					"sandboxed Java runtime");
			Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe");
			requireExit(sandbox, workspace, policy,
					List.of(keytool.toString(), "-list", "-cacerts", "-storepass", "changeit"), 0,
					"sandboxed Java security initialization");
			requireNonZero(sandbox, workspace, policy, command("echo forbidden>" + forbidden),
					"outside-workspace write");
			if (Files.exists(forbidden))
				throw new IllegalStateException("outside-workspace write escaped onto the host");
			requireNonZero(sandbox, workspace, policy,
					command("type \"" + Path.of(System.getProperty("user.home"), "NTUSER.DAT") + "\" >nul"),
					"home read");
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				exchange.sendResponseHeaders(204, -1);
				exchange.close();
			});
			server.start();
			try {
				String curl = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "curl.exe")
						.toString();
				requireNonZero(sandbox, workspace, policy, List.of(curl, "--connect-timeout", "2", "--max-time", "3",
						"http://127.0.0.1:" + server.getAddress().getPort()), "loopback network");
			} finally {
				server.stop(0);
			}
			requireNonZero(sandbox, workspace, policy, command("exit 7"), "child crash");
			SandboxResult timeout = execute(sandbox, workspace, policy(Duration.ofMillis(150)),
					command("ping -n 10 127.0.0.1 >nul"), new AtomicBoolean());
			if (!timeout.timedOut())
				throw new IllegalStateException("timeout was not enforced");
			SandboxResult cancellation = execute(sandbox, workspace, policy, command("ping -n 10 127.0.0.1 >nul"),
					new AtomicBoolean(true));
			if (!cancellation.cancelled())
				throw new IllegalStateException("cancellation was not enforced");
			requireExit(sandbox, workspace, policy, command("exit 0"), 0, "post-failure recovery");
			System.out.println("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version"));
			System.out.println("java=" + System.getProperty("java.version"));
			System.out.println("backend=" + sandbox.capabilities(policy).backend());
			sandbox.capabilities(policy).enforced().forEach((control, enforced) -> System.out
					.println("control." + control.name().toLowerCase(Locale.ROOT) + "=" + enforced));
			System.out.println("validation=passed");
		} finally {
			if (!"1".equals(System.getenv("MECHANA_PROBE_KEEP")))
				deleteRecursively(root);
		}
	}

	private static SandboxPolicy policy(Duration timeout) {
		return new SandboxPolicy(TrustMode.SANDBOXED, false, 1, 512L * 1024 * 1024, 64L * 1024 * 1024, timeout, 2);
	}

	private static List<String> command(String script) {
		return List.of(System.getenv().getOrDefault("ComSpec", "C:\\Windows\\System32\\cmd.exe"), "/d", "/c", script);
	}

	private static void requireExit(WindowsSandbox sandbox, AttemptWorkspace workspace, SandboxPolicy policy,
			List<String> command, int expected, String description) throws IOException, InterruptedException {
		SandboxResult result = execute(sandbox, workspace, policy, command, new AtomicBoolean());
		if (result.exitCode() != expected)
			throw new IllegalStateException(description + " returned " + result.exitCode() + ": stdout="
					+ Files.readString(result.stdout()) + "; stderr=" + Files.readString(result.stderr()) + "; acl="
					+ readIfPresent(workspace.work().resolve("icacls.log")));
	}

	private static String readIfPresent(Path path) throws IOException {
		return Files.exists(path) ? Files.readString(path) : "missing";
	}

	private static void requireNonZero(WindowsSandbox sandbox, AttemptWorkspace workspace, SandboxPolicy policy,
			List<String> command, String description) throws IOException, InterruptedException {
		SandboxResult result = execute(sandbox, workspace, policy, command, new AtomicBoolean());
		if (result.exitCode() == 0)
			throw new IllegalStateException(description + " was unexpectedly allowed");
	}

	private static SandboxResult execute(WindowsSandbox sandbox, AttemptWorkspace workspace, SandboxPolicy policy,
			List<String> command, AtomicBoolean cancellation) throws IOException, InterruptedException {
		return sandbox.execute(new SandboxRequest(command, Map.of(), workspace, policy), cancellation);
	}

	private static void deleteRecursively(Path root) throws IOException {
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}
}
