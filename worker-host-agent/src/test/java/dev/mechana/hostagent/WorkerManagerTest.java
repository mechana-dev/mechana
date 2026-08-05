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

package dev.mechana.hostagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerManagerTest {
	@TempDir
	Path temporary;

	@Test
	void startsOnlyTheDeficitAndStopsManagedChildren() throws Exception {
		List<FakeProcess> launched = new ArrayList<>();
		WorkerManager manager = new WorkerManager(config(3), (command, directory, log) -> {
			assertTrue(command.contains("http://coordinator:8787"));
			FakeProcess process = new FakeProcess(100 + launched.size());
			launched.add(process);
			return process;
		});
		assertEquals(2, manager.start(2).runningCount());
		assertEquals(2, manager.start(2).runningCount());
		assertEquals(2, launched.size());
		assertTrue(manager.status().workers().stream().allMatch(worker -> worker.id().startsWith("test-host-")));
		assertEquals("STOPPED", manager.stopAll().state());
		assertTrue(launched.stream().allMatch(process -> process.destroyed));
	}

	@Test
	void enforcesLimitAndPrunesDeadChildren() throws Exception {
		List<FakeProcess> launched = new ArrayList<>();
		WorkerManager manager = new WorkerManager(config(1), (c, d, l) -> {
			FakeProcess p = new FakeProcess(1);
			launched.add(p);
			return p;
		});
		assertThrows(IllegalArgumentException.class, () -> manager.start(2));
		manager.start(1);
		launched.getFirst().alive = false;
		assertEquals(0, manager.status().runningCount());
		assertEquals("STOPPED", manager.status().state());
	}

	@Test
	void validatesSandboxModeAndBuildsSandboxedWorkerCommandOnMacOs() throws Exception {
		List<String> launched = new ArrayList<>();
		WorkerManager manager = new WorkerManager(config(2), (command, directory, log) -> {
			launched.addAll(command);
			return new FakeProcess(7);
		});
		WorkerManager.LaunchRequest request = new WorkerManager.LaunchRequest(1, WorkerLaunchMode.SANDBOXED,
				"fractal-render");
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
			WorkerManager.Status status = manager.start(request);
			assertEquals(WorkerLaunchMode.SANDBOXED, status.launchMode());
			assertTrue(launched.stream().anyMatch(value -> value.startsWith("-Dmechana.sandbox.root=")));
			assertTrue(launched.contains("fractal-render"));
		} else {
			assertThrows(IllegalArgumentException.class, () -> manager.start(request));
		}
		assertThrows(IllegalArgumentException.class,
				() -> manager.start(new WorkerManager.LaunchRequest(1, WorkerLaunchMode.SANDBOXED, "sleep")));
	}

	private AgentConfig config(int max) {
		return new AgentConfig("127.0.0.1", 0, "", URI.create("http://coordinator:8787"), Path.of("java"),
				Path.of("worker.jar"), temporary, max, "sleep", Duration.ofMillis(5), "test-host", false,
				temporary.resolve("sandbox"), "fractal-render");
	}

	private static final class FakeProcess implements ManagedProcess {
		final long pid;
		boolean alive = true;
		boolean destroyed;
		FakeProcess(long pid) {
			this.pid = pid;
		}
		public long pid() {
			return pid;
		}
		public boolean isAlive() {
			return alive;
		}
		public void destroy() {
			destroyed = true;
			alive = false;
		}
		public void destroyForcibly() {
			alive = false;
		}
		public boolean waitFor(Duration timeout) {
			return !alive;
		}
	}
}
