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
