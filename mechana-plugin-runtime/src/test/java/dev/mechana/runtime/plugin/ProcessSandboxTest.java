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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessSandboxTest {
	@TempDir
	Path temporary;
	@Test
	void capturesLogsAndSuccessfulExit() throws Exception {
		SandboxResult result = run(List.of("/bin/sh", "-c", "echo out; echo err >&2"), Duration.ofSeconds(2));
		assertEquals(0, result.exitCode());
		assertEquals("out", Files.readString(result.stdout()).trim());
		assertEquals("err", Files.readString(result.stderr()).trim());
	}
	@Test
	void timesOutAndTerminatesProcessTree() throws Exception {
		SandboxResult result = run(List.of("/bin/sh", "-c", "sleep 30 & wait"), Duration.ofMillis(150));
		assertTrue(result.timedOut());
		assertTrue(result.elapsed().compareTo(Duration.ofSeconds(5)) < 0);
	}
	private SandboxResult run(List<String> command, Duration timeout) throws Exception {
		AttemptWorkspace workspace = AttemptWorkspace.create(temporary, "job", "attempt" + System.nanoTime());
		SandboxPolicy policy = new SandboxPolicy(TrustMode.MANAGED, true, 1, 1, 1, timeout, 1);
		return new ProcessSandbox().execute(
				new SandboxRequest(command, Map.of("PATH", "/usr/bin:/bin"), workspace, policy), new AtomicBoolean());
	}
}
