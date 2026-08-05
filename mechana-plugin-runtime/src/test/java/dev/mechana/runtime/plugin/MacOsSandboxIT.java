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

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MacOsSandboxIT {
	@TempDir
	Path temporary;
	private MacOsSandbox sandbox;
	private AttemptWorkspace workspace;
	@BeforeEach
	void setUp() throws Exception {
		sandbox = new MacOsSandbox();
		Assumptions.assumeTrue(sandbox.supportsCurrentHost());
		workspace = AttemptWorkspace.create(temporary, "job", "attempt");
	}
	@Test
	void allowsWorkspaceIoButDeniesInputWritesAndOutsideReadsAndWrites() throws Exception {
		Files.writeString(workspace.input().resolve("source.txt"), "input");
		SandboxResult allowed = execute("cat ../input/source.txt > ../output/result.txt");
		assertEquals(0, allowed.exitCode(), () -> diagnostics(allowed));
		assertEquals("input", Files.readString(workspace.output().resolve("result.txt")));
		assertNotEquals(0, execute("echo bad >> ../input/source.txt").exitCode());
		assertNotEquals(0, execute("cat /etc/passwd").exitCode());
		assertNotEquals(0, execute("echo bad > /tmp/mechana-sandbox-escape").exitCode());
	}
	@Test
	void deniesNetworkWhenPolicyRequiresIt() throws Exception {
		SandboxResult result = execute("/usr/bin/curl --connect-timeout 1 http://127.0.0.1:9");
		assertNotEquals(0, result.exitCode());
		assertTrue(result.capabilities().enforces(SandboxControl.NETWORK_DENIAL));
	}
	@Test
	void reportsOnlyControlsActuallyApplied() {
		SandboxCapabilities capabilities = sandbox.capabilities(policy());
		assertAll(() -> assertTrue(capabilities.enforces(SandboxControl.FILESYSTEM_RESTRICTION)),
				() -> assertTrue(capabilities.enforces(SandboxControl.NETWORK_DENIAL)),
				() -> assertTrue(capabilities.enforces(SandboxControl.TIMEOUT)),
				() -> assertFalse(capabilities.enforces(SandboxControl.MEMORY_LIMIT)),
				() -> assertFalse(capabilities.enforces(SandboxControl.CPU_LIMIT)),
				() -> assertFalse(capabilities.enforces(SandboxControl.PROCESS_LIMIT)));
	}
	private SandboxResult execute(String shell) throws Exception {
		return sandbox.execute(new SandboxRequest(List.of("/bin/sh", "-c", shell), Map.of("PATH", "/usr/bin:/bin"),
				workspace, policy()), new AtomicBoolean());
	}

	private static String diagnostics(SandboxResult result) {
		try {
			return "stderr: " + Files.readString(result.stderr());
		} catch (java.io.IOException failure) {
			return "stderr could not be read: " + failure.getMessage();
		}
	}
	private SandboxPolicy policy() {
		return new SandboxPolicy(TrustMode.SANDBOXED, false, 1, 1024, 1024, Duration.ofSeconds(3), 2);
	}
}
