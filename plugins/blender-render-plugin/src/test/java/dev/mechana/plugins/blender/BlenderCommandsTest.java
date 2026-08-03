package dev.mechana.plugins.blender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BlenderCommandsTest {
	@Test
	void constructsSafeCpuFrameRangeCommand() {
		var command = new BlenderCommands("blender").render(Path.of("scene.blend"), Path.of("frame_######"), 11, 20,
				1280, 720, 32, 0);
		assertEquals("blender", command.getFirst());
		assertTrue(command.contains("--disable-autoexec"));
		assertTrue(command.contains("--cycles-device"));
		assertTrue(command.contains("CPU"));
		assertTrue(command.stream().anyMatch(argument -> argument.contains("use_persistent_data=True")));
		assertTrue(command.stream().anyMatch(argument -> argument.contains("render.engine='CYCLES'")));
		assertTrue(command.contains("11"));
		assertTrue(command.contains("20"));
	}
}
