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
