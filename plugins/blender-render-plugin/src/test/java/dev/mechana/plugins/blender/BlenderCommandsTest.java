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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlenderCommandsTest {
	@TempDir
	Path temporary;

	@Test
	void stagesWorkerProvidedSceneWithoutNetworkAccess() throws Exception {
		Path source = temporary.resolve("staged.blend");
		Path destination = temporary.resolve("work/scene.blend");
		Files.writeString(source, "packed-scene");
		Files.createDirectories(destination.getParent());

		BlenderRenderPlugin.stageInput(Map.of("inputPath", source.toString()), destination);

		assertEquals("packed-scene", Files.readString(destination));
	}

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

	@Test
	void defaultsToOneCpuThreadPerDistributedWorker() {
		assertEquals(1, BlenderRenderPlugin.renderThreads(Map.of()));
		assertEquals(1, BlenderRenderPlugin.renderThreads(Map.of("threads", "0")));
		assertEquals(3, BlenderRenderPlugin.renderThreads(Map.of("threads", "3")));
	}

	@Test
	void reportsVisibleProgressForSingleFrameAndCyclesSamples() {
		assertEquals(5, BlenderRenderPlugin.progressForLine("Fra:7 Mem:1.0M | Syncing", 7, 7).orElseThrow());
		assertEquals(47,
				BlenderRenderPlugin.progressForLine("Fra:7 Mem:1.0M | Rendering 2 / 4 samples", 7, 7).orElseThrow());
		assertTrue(BlenderRenderPlugin.progressForLine("Blender 4.5.3", 7, 7).isEmpty());
	}

	@Test
	void confinesBlenderTemporaryFilesToAttemptScratch() {
		ProcessBuilder builder = new ProcessBuilder("blender");

		BlenderRenderPlugin.configureTemporaryDirectory(builder, temporary);

		String expected = temporary.toAbsolutePath().normalize().toString();
		assertEquals(expected, builder.environment().get("TMPDIR"));
		assertEquals(expected, builder.environment().get("TMP"));
		assertEquals(expected, builder.environment().get("TEMP"));
	}
}
