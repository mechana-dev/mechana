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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxSandboxTest {
	@TempDir
	Path temporary;

	@Test
	void buildsPortableNamespaceCommandWithoutExposingHome() throws Exception {
		AttemptWorkspace workspace = AttemptWorkspace.create(temporary, "job", "attempt");
		SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, false, 2, 1024, 2048, Duration.ofSeconds(5), 2);
		SandboxRequest request = new SandboxRequest(List.of("/usr/bin/true"), Map.of(), workspace, policy);
		List<String> command = new LinuxSandbox(Path.of("/test/bwrap")).command(request);
		assertTrue(command.contains("--unshare-net"));
		assertTrue(command.contains("--unshare-user"));
		assertTrue(command.contains("--die-with-parent"));
		assertTrue(command.contains(workspace.input().toString()));
		assertFalse(command.contains(System.getProperty("user.home")));
	}
}
