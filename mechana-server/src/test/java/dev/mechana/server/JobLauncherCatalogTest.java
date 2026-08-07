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
package dev.mechana.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JobLauncherCatalogTest {
	@Test
	void returnsOnlyCurrentlySchedulableDescriptors() {
		var descriptors = JobLauncherCatalog.available(Map.of("sleep", 2, "ocr-tesseract", 1, "offline", 4));
		assertEquals(2, descriptors.size());
		assertEquals("sleep", descriptors.getFirst().capabilityId());
		assertEquals(2, descriptors.getFirst().availableWorkers());
		assertTrue(descriptors.stream().allMatch(item -> item.output().provider().equals("server-local")));
	}

	@Test
	void blenderDefaultsToTheTwoSecondCameraOrbitSample() {
		var descriptor = JobLauncherCatalog.available(Map.of("blender-render", 4)).getFirst();
		Map<String, String> defaults = descriptor.fields().stream()
				.collect(java.util.stream.Collectors.toMap(field -> field.name(), field -> field.defaultValue()));
		assertEquals("samples/blender/mechana-camera-orbit-2s.blend", defaults.get("sourcePath"));
		assertEquals("1", defaults.get("firstFrame"));
		assertEquals("48", defaults.get("lastFrame"));
		assertEquals("24", defaults.get("fps"));
		assertEquals("640", defaults.get("width"));
		assertEquals("360", defaults.get("height"));
		assertEquals("taskCount", descriptor.fields().get(1).name());
	}

	@Test
	void everyCapabilityUsesTheSameFleetAwareTasksConvention() {
		var descriptors = JobLauncherCatalog.available(
				Map.of("sleep", 1, "video-ffmpeg", 1, "fractal-render", 1, "ocr-tesseract", 1, "blender-render", 1));
		assertEquals(5, descriptors.size());
		for (var descriptor : descriptors) {
			var tasks = descriptor.fields().stream()
					.filter(field -> field.name().equals("taskCount") || field.name().equals("segmentCount"))
					.findFirst().orElseThrow();
			assertEquals("Tasks (0 = fleet)", tasks.label());
			assertEquals("0", tasks.defaultValue());
			assertEquals(0d, tasks.minimum());
		}
	}
}
