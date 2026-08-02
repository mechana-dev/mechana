package dev.mechana.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mechana.coordinator.Scheduler.PluginLocation;
import dev.mechana.coordinator.Scheduler.WorkSpec;
import dev.mechana.protocol.Messages.TaskLease;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.List;
import java.util.Map;
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
	void independentHeartbeatRenewsLeaseWithoutChangingProgress() {
		MutableClock clock = new MutableClock();
		Scheduler scheduler = new Scheduler(1_000, clock);
		String jobId = scheduler.submit(1, 100);
		TaskLease lease = scheduler.lease("busy-worker", Set.of("sleep"), PLUGIN).orElseThrow();

		clock.advance(900);
		assertTrue(scheduler.heartbeat("busy-worker", lease.taskId(), lease.leaseToken()));
		clock.advance(900);

		assertEquals(0, scheduler.dashboard(jobId).progress());
		assertTrue(scheduler.complete("busy-worker", lease.taskId(), lease.leaseToken()));
		assertFalse(scheduler.heartbeat("busy-worker", lease.taskId(), "stale-token"));
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

	@Test
	void pauseFencesLeasesAndResumeRequeuesOnlyUnfinishedTasks() {
		Scheduler scheduler = new Scheduler(1_000);
		String jobId = scheduler.submit(2, 100);
		TaskLease completed = scheduler.lease("worker-1", Set.of("sleep"), PLUGIN).orElseThrow();
		TaskLease interrupted = scheduler.lease("worker-2", Set.of("sleep"), PLUGIN).orElseThrow();
		assertTrue(scheduler.complete("worker-1", completed.taskId(), completed.leaseToken()));

		assertTrue(scheduler.pause(jobId));
		assertEquals("PAUSED", scheduler.status(jobId).state());
		assertFalse(scheduler.complete("worker-2", interrupted.taskId(), interrupted.leaseToken()));
		assertTrue(scheduler.lease("worker-3", Set.of("sleep"), PLUGIN).isEmpty());
		assertTrue(scheduler.resume(jobId));

		TaskLease resumed = scheduler.lease("worker-3", Set.of("sleep"), PLUGIN).orElseThrow();
		assertEquals(interrupted.taskId(), resumed.taskId());
		assertEquals(2, resumed.attempt());
		assertEquals(1, scheduler.dashboard(jobId).completedWorkUnits());
		assertFalse(scheduler.resume(jobId));
	}

	@Test
	void cancelledJobCanResumeAsNewAndReuseCompletedWorkUnits() {
		Scheduler scheduler = new Scheduler(1_000);
		String originalId = scheduler.submit(2, 100);
		TaskLease completed = scheduler.lease("worker-1", Set.of("sleep"), PLUGIN).orElseThrow();
		scheduler.lease("worker-2", Set.of("sleep"), PLUGIN).orElseThrow();
		assertTrue(scheduler.complete("worker-1", completed.taskId(), completed.leaseToken()));
		assertTrue(scheduler.abort(originalId));

		String resumedId = scheduler.resumeAsNew(scheduler.dashboard(originalId));

		assertEquals(originalId, scheduler.dashboard(resumedId).details().get("resumedFromJobId"));
		assertEquals("1", scheduler.dashboard(resumedId).details().get("reusedWorkUnits"));
		assertEquals(1, scheduler.dashboard(resumedId).completedWorkUnits());
		TaskLease remaining = scheduler.lease("worker-3", Set.of("sleep"), PLUGIN).orElseThrow();
		assertEquals(resumedId, remaining.jobId());
	}

	@Test
	void supportsVariableSleepDurationsAndCapabilityMatchedVideoWork() {
		Scheduler scheduler = new Scheduler(1_000);
		String sleepJob = scheduler.submit(List.of(120_000L, 180_000L, 210_000L, 240_000L));
		PluginLocation videoPlugin = new PluginLocation("http://server/video.jar", "video123");
		String videoJob = scheduler.submitVideo(
				List.of(new WorkSpec(10_000, Map.of("segmentIndex", "0", "startSeconds", "0", "endSeconds", "10"))),
				Map.of("source", "input.mp4"), videoPlugin);

		TaskLease sleep = scheduler.lease("sleep-only", Set.of("sleep"), PLUGIN).orElseThrow();
		TaskLease video = scheduler.lease("video-worker", Set.of("video-ffmpeg"), PLUGIN).orElseThrow();

		assertEquals(120_000, sleep.durationMillis());
		assertEquals(sleepJob, sleep.jobId());
		assertEquals(videoJob, video.jobId());
		assertEquals("video-ffmpeg", video.pluginId());
		assertEquals(videoPlugin.url(), video.pluginUrl());
		assertEquals("0", video.parameters().get("segmentIndex"));
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
