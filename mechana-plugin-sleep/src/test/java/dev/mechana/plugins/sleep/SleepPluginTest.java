package dev.mechana.plugins.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.mechana.api.TaskContext;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SleepPluginTest {

	@Test
	void reportsCompletion() throws Exception {
		AtomicInteger progress = new AtomicInteger();
		TaskContext context = new TaskContext() {
			@Override
			public long durationMillis() {
				return 5;
			}

			@Override
			public void reportProgress(int percent) {
				progress.set(percent);
			}

			@Override
			public boolean isCancellationRequested() {
				return false;
			}
		};

		new SleepPlugin().execute(context);

		assertEquals(100, progress.get());
		assertFalse(context.isCancellationRequested());
	}
}
