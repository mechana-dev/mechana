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
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one explicitly granted native runtime command in the Windows sandbox.
 */
public final class WindowsNativeRuntimeProbe {
	private WindowsNativeRuntimeProbe() {
	}

	public static void main(String[] args) throws java.io.IOException, InterruptedException {
		if (args.length < 2)
			throw new IllegalArgumentException("Usage: WindowsNativeRuntimeProbe RUNTIME_DIRECTORY COMMAND [ARG...]");
		Path runtime = Path.of(args[0]).toAbsolutePath().normalize();
		List<String> command = List.of(args).subList(1, args.length);
		Path base = Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "Mechana", "sandbox");
		Path root = Files.createTempDirectory(base, "native-probe-");
		try {
			AttemptWorkspace workspace = AttemptWorkspace.create(root, "native", "probe");
			SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, false, 2, 2L * 1024 * 1024 * 1024,
					1024L * 1024 * 1024, Duration.ofMinutes(2), 8);
			SandboxRequest request = new SandboxRequest(command, windowsEnvironment(), workspace, policy, null, null,
					List.of(runtime));
			SandboxResult result = new WindowsSandbox().execute(request, new AtomicBoolean());
			System.out.print(Files.readString(result.stdout()));
			System.err.print(Files.readString(result.stderr()));
			System.out.println("probe.exit=" + result.exitCode());
			if (result.exitCode() != 0)
				throw new IllegalStateException("Native runtime probe failed with exit " + result.exitCode());
		} finally {
			try (var paths = Files.walk(root)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.deleteIfExists(path);
			}
		}
	}

	private static Map<String, String> windowsEnvironment() {
		java.util.HashMap<String, String> environment = new java.util.HashMap<>();
		for (String name : List.of("SystemRoot", "WINDIR", "ComSpec", "PATH")) {
			String value = System.getenv(name);
			if (value != null && !value.isBlank())
				environment.put(name, value);
		}
		return Map.copyOf(environment);
	}
}
