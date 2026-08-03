package dev.mechana.plugins.blender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BlenderMovieAssemblerTest {
	@Test
	void constructsDeterministicMovieCommand() {
		var command = BlenderMovieAssembler.movieCommand("ffmpeg", Path.of("frame_%06d.png"), Path.of("out.mp4"), 7,
				24);
		assertEquals("ffmpeg", command.getFirst());
		assertTrue(command.contains("7"));
		assertTrue(command.contains("24"));
		assertTrue(command.contains("libx265"));
		assertEquals("hvc1", command.get(command.indexOf("-tag:v") + 1));
	}
}
