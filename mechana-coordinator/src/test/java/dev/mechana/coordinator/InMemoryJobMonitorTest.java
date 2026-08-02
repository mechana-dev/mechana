package dev.mechana.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mechana.api.WorkUnit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryJobMonitorTest {
	@Test
	void reportsWorkerAndWeightedProgressWithoutPluginKnowledge() {
		InMemoryJobMonitor monitor = new InMemoryJobMonitor("job-1", "sleep", Map.of("request", "demo"));
		monitor.onPlan(2, List.of(new WorkUnit("short", "Short task", 1, Map.of("duration", "1s")),
				new WorkUnit("long", "Long task", 3, Map.of("duration", "3s"))));
		monitor.onStage("EXECUTING");
		monitor.onWorkUnitStarted("short", "192.0.2.10");
		monitor.onWorkUnitStarted("long", "192.0.2.11");
		monitor.onWorkUnitCompleted("short");
		monitor.onWorkUnitProgress("long", 50, Map.of("samples", "1500"));

		InMemoryJobMonitor.Snapshot snapshot = monitor.snapshot();
		assertEquals(63, snapshot.progress());
		assertEquals(2, snapshot.configuredWorkers());
		assertEquals(1, snapshot.completedWorkUnits());
		assertEquals(1, snapshot.activeWorkers());
		assertEquals("sleep", snapshot.plugin());
		assertEquals("192.0.2.11", snapshot.workUnits().get(1).workerAddress());
		assertEquals("1500", snapshot.workUnits().get(1).details().get("samples"));
	}

	@Test
	void freezesJobAndWorkUnitElapsedTimeAtCompletion() {
		MutableClock clock = new MutableClock();
		InMemoryJobMonitor monitor = new InMemoryJobMonitor("job-1", "sleep", Map.of(), clock);
		monitor.onPlan(1, List.of(new WorkUnit("task-1", "Task", 1, Map.of())));
		monitor.onWorkUnitStarted("task-1", "worker-1");
		clock.advance(5_000);
		monitor.onWorkUnitCompleted("task-1");
		monitor.onStage("SUCCEEDED");
		clock.advance(60_000);

		InMemoryJobMonitor.Snapshot snapshot = monitor.snapshot();
		assertEquals("00:00:05", snapshot.elapsed());
		assertEquals("00:00:05", snapshot.workUnits().getFirst().elapsed());
		assertEquals("1970-01-01T00:00:05Z", snapshot.completedAt());
	}

	@Test
	void excludesPausedTimeAndRequeuesUnfinishedWorkOnResume() {
		MutableClock clock = new MutableClock();
		InMemoryJobMonitor monitor = new InMemoryJobMonitor("job-1", "sleep", Map.of(), clock);
		monitor.onPlan(1, List.of(new WorkUnit("task-1", "Task", 1, Map.of())));
		monitor.onWorkUnitStarted("task-1", "worker-1");
		clock.advance(5_000);
		monitor.pause();
		clock.advance(60_000);

		assertEquals("PAUSED", monitor.snapshot().stage());
		assertEquals("00:00:05", monitor.snapshot().elapsed());
		assertEquals("PAUSED", monitor.snapshot().workUnits().getFirst().state());

		monitor.resume();
		assertEquals("QUEUED", monitor.snapshot().stage());
		assertEquals(0, monitor.snapshot().workUnits().getFirst().progress());
	}

	private static final class MutableClock extends Clock {
		private long millis;

		void advance(long amount) {
			millis += amount;
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(millis);
		}
	}
}
