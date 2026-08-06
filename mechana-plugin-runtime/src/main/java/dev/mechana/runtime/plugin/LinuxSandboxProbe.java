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

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone host probe used before a Linux worker advertises sandbox
 * guarantees.
 */
public final class LinuxSandboxProbe {
	private LinuxSandboxProbe() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		LinuxSandbox sandbox = new LinuxSandbox();
		if (!sandbox.supportsCurrentHost())
			throw new IllegalStateException("Bubblewrap or unprivileged Linux namespaces are unavailable");
		Path root = Files.createTempDirectory("mechana-linux-probe-");
		try {
			AttemptWorkspace workspace = AttemptWorkspace.create(root, "probe", "attempt");
			SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, false, 1, 64 * 1024 * 1024, 64 * 1024 * 1024,
					Duration.ofSeconds(3), 2);
			Path allowed = workspace.output().resolve("allowed");
			Path forbidden = root.resolve("forbidden");
			requireExit(sandbox, workspace, policy, List.of("/usr/bin/touch", allowed.toString()), 0,
					"workspace write");
			requireExit(sandbox, workspace, policy, List.of("/usr/bin/touch", forbidden.toString()), 0,
					"private tmp write");
			if (Files.exists(forbidden))
				throw new IllegalStateException("private tmp write escaped onto the host");
			requireNonZero(sandbox, workspace, policy, List.of("/usr/bin/test", "-e", System.getProperty("user.home")),
					"home visibility");
			requireNonZero(sandbox, workspace, policy, List.of("/usr/bin/false"), "child crash");
			SandboxPolicy shortPolicy = new SandboxPolicy(TrustMode.SANDBOXED, false, 1, 64 * 1024 * 1024,
					64 * 1024 * 1024, Duration.ofMillis(100), 2);
			SandboxResult timeout = execute(sandbox, workspace, shortPolicy, List.of("/usr/bin/sleep", "5"),
					new AtomicBoolean());
			if (!timeout.timedOut())
				throw new IllegalStateException("timeout was not enforced");
			SandboxResult cancellation = execute(sandbox, workspace, policy, List.of("/usr/bin/sleep", "5"),
					new AtomicBoolean(true));
			if (!cancellation.cancelled())
				throw new IllegalStateException("cancellation was not enforced");
			requireExit(sandbox, workspace, policy, List.of("/usr/bin/true"), 0, "post-failure recovery");
			System.out.println("backend=" + sandbox.capabilities(policy).backend());
			sandbox.capabilities(policy).enforced().forEach((control, enforced) -> System.out
					.println("control." + control.name().toLowerCase(Locale.ROOT) + "=" + enforced));
			System.out.println("validation=passed");
		} finally {
			deleteRecursively(root);
		}
	}

	private static void requireExit(LinuxSandbox sandbox, AttemptWorkspace workspace, SandboxPolicy policy,
			List<String> command, int expected, String description) throws IOException, InterruptedException {
		SandboxResult result = execute(sandbox, workspace, policy, command, new AtomicBoolean());
		if (result.exitCode() != expected)
			throw new IllegalStateException(description + " returned " + result.exitCode());
	}

	private static void requireNonZero(LinuxSandbox sandbox, AttemptWorkspace workspace, SandboxPolicy policy,
			List<String> command, String description) throws IOException, InterruptedException {
		SandboxResult result = execute(sandbox, workspace, policy, command, new AtomicBoolean());
		if (result.exitCode() == 0)
			throw new IllegalStateException(description + " was unexpectedly allowed");
	}

	private static SandboxResult execute(LinuxSandbox sandbox, AttemptWorkspace workspace, SandboxPolicy policy,
			List<String> command, AtomicBoolean cancellation) throws IOException, InterruptedException {
		return sandbox.execute(new SandboxRequest(command, Map.of("PATH", "/usr/bin:/bin"), workspace, policy),
				cancellation);
	}

	private static void deleteRecursively(Path root) throws IOException {
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}
}
