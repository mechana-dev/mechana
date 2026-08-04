package dev.mechana.hostagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

	private AgentConfig config(int max) {
		return new AgentConfig("127.0.0.1", 0, "", URI.create("http://coordinator:8787"), Path.of("java"),
				Path.of("worker.jar"), temporary, max, "sleep", Duration.ofMillis(5), "test-host", false);
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
