package dev.mechana.plugins.sleep;

import dev.mechana.api.PluginDescriptor;
import dev.mechana.api.PluginExecutionException;
import dev.mechana.api.TaskContext;
import dev.mechana.api.TaskPlugin;

/** Demonstration plugin that consumes wall-clock time and reports progress. */
public final class SleepPlugin implements TaskPlugin {

	private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("sleep", "1.0.0");

	@Override
	public PluginDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public void execute(TaskContext context) throws PluginExecutionException {
		long duration = context.durationMillis();
		long startedAt = System.nanoTime();
		context.reportProgress(0);
		while (true) {
			if (context.isCancellationRequested()) {
				throw new PluginExecutionException("Task was cancelled", null);
			}
			long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
			int progress = (int) Math.min(100, elapsedMillis * 100 / duration);
			context.reportProgress(progress);
			if (progress >= 100) {
				return;
			}
			try {
				Thread.sleep(Math.min(250, Math.max(1, duration - elapsedMillis)));
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new PluginExecutionException("Sleep task was interrupted", interrupted);
			}
		}
	}
}
