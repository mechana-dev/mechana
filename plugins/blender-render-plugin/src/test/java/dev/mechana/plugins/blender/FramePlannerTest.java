package dev.mechana.plugins.blender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class FramePlannerTest {
	@Test
	void dividesInclusiveRangeWithoutGaps() {
		assertEquals(List.of(new FramePlanner.Batch(0, 1, 3), new FramePlanner.Batch(1, 4, 6),
				new FramePlanner.Batch(2, 7, 8)), new FramePlanner().plan(1, 8, 3));
	}

	@Test
	void rejectsMoreTasksThanFrames() {
		assertThrows(IllegalArgumentException.class, () -> new FramePlanner().plan(1, 2, 3));
	}
}
