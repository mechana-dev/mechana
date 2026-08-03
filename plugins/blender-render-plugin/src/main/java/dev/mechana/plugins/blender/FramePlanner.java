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
