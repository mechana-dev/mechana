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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WindowsSandboxTest {
	@Test
	void constructsLauncherCommandWithWorkspaceAndJobLimits() {
		AttemptWorkspace workspace = new AttemptWorkspace(Path.of("C:/sandbox/job/attempt"), Path.of("C:/input"),
				Path.of("C:/work"), Path.of("C:/output"), Path.of("C:/logs"));
		SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, false, 2, 512_000_000, 1_000_000,
				Duration.ofSeconds(30), 8);
		List<String> command = new WindowsSandbox()
				.command(new SandboxRequest(List.of("java.exe", "-version"), Map.of(), workspace, policy));
		assertTrue(command.contains("--workspace"));
		assertTrue(command.contains("512000000"));
		assertTrue(command.contains("--processes"));
		assertTrue(command.contains("8"));
		assertEquals(List.of("java.exe", "-version"), command.subList(command.indexOf("--") + 1, command.size()));
	}
}
