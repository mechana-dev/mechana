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
