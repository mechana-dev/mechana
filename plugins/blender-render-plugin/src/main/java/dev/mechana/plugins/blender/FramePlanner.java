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

package dev.mechana.plugins.blender;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministically divides an inclusive frame range into contiguous batches.
 */
public final class FramePlanner {
	public record Batch(int index, int firstFrame, int lastFrame) {
		public int frameCount() {
			return lastFrame - firstFrame + 1;
		}
	}

	public List<Batch> plan(int firstFrame, int lastFrame, int taskCount) {
		if (firstFrame < 0 || lastFrame < firstFrame)
			throw new IllegalArgumentException("Invalid frame range");
		int frames = lastFrame - firstFrame + 1;
		if (taskCount < 1 || taskCount > frames)
			throw new IllegalArgumentException("Task count must be between 1 and frame count");
		List<Batch> batches = new ArrayList<>(taskCount);
		int cursor = firstFrame;
		for (int index = 0; index < taskCount; index++) {
			int count = frames / taskCount + (index < frames % taskCount ? 1 : 0);
			batches.add(new Batch(index, cursor, cursor + count - 1));
			cursor += count;
		}
		return List.copyOf(batches);
	}
}
