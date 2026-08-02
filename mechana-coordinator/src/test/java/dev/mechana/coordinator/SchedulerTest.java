package dev.mechana.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mechana.coordinator.Scheduler.PluginLocation;
import dev.mechana.protocol.Messages.TaskLease;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchedulerTest {

	private static final PluginLocation PLUGIN = new PluginLocation("http://server/plugin.jar", "abc123");

	@Test
	void leasesParallelTasksToDifferentWorkersAndCompletesOnlyAfterAllTasks() {
		Scheduler scheduler = new Scheduler(1_000);
		String jobId = scheduler.submit(2, 100);

		TaskLease first = scheduler.lease("worker-1", Set.of("sleep"), PLUGIN).orElseThrow();
		TaskLease second = scheduler.lease("worker-2", Set.of("sleep"), PLUGIN).orElseThrow();

		assertFalse(first.taskId().equals(second.taskId()));
		assertEquals("sleep", scheduler.dashboard(jobId).plugin());
		assertEquals(2, scheduler.dashboard(jobId).activeWorkers());
		assertTrue(scheduler.progress("worker-1", first.taskId(), first.leaseToken(), 50));
		assertEquals(25, scheduler.dashboard(jobId).progress());
		assertTrue(scheduler.complete("worker-1", first.taskId(), first.leaseToken()));
		assertEquals("RUNNING", scheduler.status(jobId).state());
		assertTrue(scheduler.complete("worker-2", second.taskId(), second.leaseToken()));
		assertEquals("SUCCEEDED", scheduler.status(jobId).state());
		assertEquals("SUCCEEDED", scheduler.dashboard(jobId).stage());
		assertEquals("SUCCEEDED", scheduler.dashboards().getFirst().stage());
	}

	@Test
	void expiredWorkerLeaseIsRejectedAndTaskIsRescheduled() {
		MutableClock clock = new MutableClock();
		Scheduler scheduler = new Scheduler(1_000, clock);
		scheduler.submit(1, 100);
		TaskLease abandoned = scheduler.lease("lost-worker", Set.of("sleep"), PLUGIN).orElseThrow();

		clock.advance(1_001);

		TaskLease retried = scheduler.lease("replacement", Set.of("sleep"), PLUGIN).orElseThrow();
		assertEquals(abandoned.taskId(), retried.taskId());
		assertEquals(2, retried.attempt());
		assertFalse(scheduler.complete("lost-worker", abandoned.taskId(), abandoned.leaseToken()));
		assertTrue(scheduler.complete("replacement", retried.taskId(), retried.leaseToken()));
	}

	@Test
	void abortCancelsQueuedAndRunningTasksAndFencesTheirLeases() {
		Scheduler scheduler = new Scheduler(1_000);
		String jobId = scheduler.submit(2, 100);
		TaskLease running = scheduler.lease("worker-1", Set.of("sleep"), PLUGIN).orElseThrow();

		assertTrue(scheduler.abort(jobId));
		assertEquals("CANCELLED", scheduler.status(jobId).state());
		assertEquals("CANCELLED", scheduler.dashboard(jobId).stage());
		assertTrue(scheduler.dashboard(jobId).workUnits().stream()
				.allMatch(workUnit -> "CANCELLED".equals(workUnit.state())));
		assertFalse(scheduler.complete("worker-1", running.taskId(), running.leaseToken()));
		assertTrue(scheduler.lease("worker-2", Set.of("sleep"), PLUGIN).isEmpty());
		assertFalse(scheduler.abort(jobId));
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

		@Override
		public long millis() {
			return millis;
		}
	}
}
