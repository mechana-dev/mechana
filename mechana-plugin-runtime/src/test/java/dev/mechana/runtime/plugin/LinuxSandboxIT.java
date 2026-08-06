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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class LinuxSandboxIT {
	@TempDir
	Path temporary;
	private LinuxSandbox sandbox;
	private AttemptWorkspace workspace;

	@BeforeEach
	void setUp() throws Exception {
		sandbox = new LinuxSandbox();
		Assumptions.assumeTrue(sandbox.supportsCurrentHost());
		workspace = AttemptWorkspace.create(temporary, "job", "attempt");
	}

	@Test
	void permitsWorkspaceAndDeniesOutsideWrite() throws Exception {
		assertEquals(0,
				execute(List.of("/usr/bin/touch", workspace.output().resolve("allowed").toString())).exitCode());
		Path forbidden = temporary.resolve("forbidden");
		assertEquals(0, execute(List.of("/usr/bin/touch", forbidden.toString())).exitCode());
		assertTrue(Files.exists(workspace.output().resolve("allowed")));
		assertFalse(Files.exists(forbidden));
	}

	@Test
	void deniesHomeAndNetwork() throws Exception {
		assertNotEquals(0, execute(List.of("/usr/bin/test", "-e", System.getProperty("user.home"))).exitCode());
		SandboxCapabilities capabilities = sandbox.capabilities(policy());
		assertTrue(capabilities.enforces(SandboxControl.FILESYSTEM_RESTRICTION));
		assertTrue(capabilities.enforces(SandboxControl.NETWORK_DENIAL));
	}

	private SandboxResult execute(List<String> command) throws Exception {
		return sandbox.execute(new SandboxRequest(command, Map.of("PATH", "/usr/bin:/bin"), workspace, policy()),
				new AtomicBoolean());
	}

	private static SandboxPolicy policy() {
		return new SandboxPolicy(TrustMode.SANDBOXED, false, 1, 64 * 1024 * 1024, 64 * 1024 * 1024,
				Duration.ofSeconds(5), 2);
	}
}
