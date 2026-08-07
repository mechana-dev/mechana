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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsSandboxTest {
	@TempDir
	Path temporary;

	@Test
	void buildsAppContainerLauncherWithResourceLimits() throws Exception {
		AttemptWorkspace workspace = AttemptWorkspace.create(temporary, "job", "attempt");
		SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, false, 2, 512L * 1024 * 1024, 1024,
				Duration.ofSeconds(5), 3);
		List<String> command = new WindowsSandbox().launcherCommand(Path.of("launcher.ps1"), workspace, policy,
				List.of("C:\\Java\\bin\\java", "-version"));
		assertTrue(command.contains("-MemoryBytes"));
		assertTrue(command.contains(Long.toString(policy.memoryBytes())));
		assertTrue(command.contains("-CpuCount"));
		assertTrue(command.contains("2"));
		assertTrue(command.contains("-MaxProcesses"));
		assertTrue(command.contains("3"));
		String encoded = command.get(command.indexOf("-ChildCommandBase64") + 1);
		String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
		assertTrue(decoded.endsWith("runtime" + java.io.File.separator + "java-25" + java.io.File.separator + "bin"
				+ java.io.File.separator + "java.exe\0-version"));
	}

	@Test
	void passesExplicitNativeRuntimeRootsToLauncher() throws Exception {
		AttemptWorkspace workspace = AttemptWorkspace.create(temporary, "job", "attempt");
		SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, false, 1, 1024, 1024, Duration.ofSeconds(5), 1);
		Path nativeRuntime = Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "Mechana",
				"runtime", "ffmpeg");
		List<String> command = new WindowsSandbox().launcherCommand(Path.of("launcher.ps1"), workspace, policy,
				List.of("cmd.exe"), List.of(nativeRuntime));
		String encoded = command.get(command.indexOf("-RuntimePathsBase64") + 1);
		assertTrue(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
				.endsWith("runtime" + java.io.File.separator + "ffmpeg"));
	}

	@Test
	void rejectsUnimplementedNetworkGrant() throws Exception {
		AttemptWorkspace workspace = AttemptWorkspace.create(temporary, "job", "attempt");
		SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, true, 1, 1024, 1024, Duration.ofSeconds(5), 1);
		assertThrows(IllegalArgumentException.class, () -> new WindowsSandbox().launcherCommand(Path.of("launcher.ps1"),
				workspace, policy, List.of("cmd.exe")));
	}

	@Test
	void reportsNothingWhenNotVerifiedOnThisHost() {
		WindowsSandbox sandbox = new WindowsSandbox();
		SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, false, 1, 1024, 1024, Duration.ofSeconds(5), 1);
		if (!System.getProperty("os.name").toLowerCase().contains("windows"))
			assertTrue(sandbox.capabilities(policy).enforced().entrySet().stream().filter(Map.Entry::getValue)
					.allMatch(entry -> entry.getKey() == SandboxControl.TIMEOUT));
	}
}
