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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
		assertTrue(descriptor.fields().stream().anyMatch(field -> "taskCount".equals(field.name())));
	}

	@Test
	void everyCapabilityUsesTheSameFleetAwareTasksConvention() {
		var descriptors = JobLauncherCatalog.available(Map.of("sleep", 1, "video-ffmpeg", 1, "fractal-render", 1,
				"ocr-tesseract", 1, "blender-render", 1, "audio-convolution-reverb", 1));
		assertEquals(6, descriptors.size());
		for (var descriptor : descriptors) {
			var tasks = descriptor.fields().stream()
					.filter(field -> field.name().equals("taskCount") || field.name().equals("segmentCount"))
					.findFirst().orElseThrow();
			assertEquals("Tasks (0 = fleet)", tasks.label());
			assertEquals("0", tasks.defaultValue());
			assertEquals(0d, tasks.minimum());
		}
	}

	@Test
	void audioDescriptorIsSchemaDrivenAndWavSpecific() {
		var descriptor = JobLauncherCatalog.available(Map.of("audio-convolution-reverb", 1)).getFirst();
		var fields = descriptor.fields().stream()
				.collect(java.util.stream.Collectors.toMap(field -> field.name(), field -> field));
		assertEquals("/api/jobs/audio-reverb", descriptor.submitPath());
		assertEquals(List.of("wav"), fields.get("dryPath").acceptedExtensions());
		assertEquals(List.of("wav"), fields.get("irPath").acceptedExtensions());
		assertEquals("directory", fields.get("artifactRoot").type());
		assertFalse(fields.get("artifactRoot").required());
		assertEquals(List.of("true", "false"), fields.get("normalizeIr").choices());
		assertEquals(List.of("server-local"), fields.get("storageProvider").choices());
	}

	@Test
	void fileFieldsAdvertisePluginSpecificExtensions() {
		var descriptors = JobLauncherCatalog.available(Map.of("ocr-tesseract", 1, "blender-render", 1));
		assertEquals(List.of("pdf"), descriptors.getFirst().fields().stream()
				.filter(field -> "sourcePath".equals(field.name())).findFirst().orElseThrow().acceptedExtensions());
		assertEquals(List.of("blend"), descriptors.getLast().fields().stream()
				.filter(field -> "sourcePath".equals(field.name())).findFirst().orElseThrow().acceptedExtensions());
	}

	@Test
	void ffmpegOffersServerAndClientLocalStorageLocations() {
		var descriptor = JobLauncherCatalog.available(Map.of("video-ffmpeg", 1)).getFirst();
		var fields = descriptor.fields().stream()
				.collect(java.util.stream.Collectors.toMap(field -> field.name(), field -> field));
		assertEquals("server-local", fields.get("storageProvider").defaultValue());
		assertEquals(List.of("server-local", "client-local"), fields.get("storageProvider").choices());
		assertEquals("directory", fields.get("clientScratchDirectory").type());
		assertEquals("directory", fields.get("clientOutputDirectory").type());
		assertEquals("Start offset in seconds", fields.get("startOffsetSeconds").label());
		assertEquals("0", fields.get("startOffsetSeconds").defaultValue());
		assertEquals(0d, fields.get("startOffsetSeconds").minimum());
	}
}
