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
package dev.mechana.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mechana.protocol.Messages.JobLauncherDescriptor;
import dev.mechana.protocol.Messages.OutputDescriptor;
import dev.mechana.protocol.Messages.SubmissionField;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import javax.swing.JScrollPane;
import org.junit.jupiter.api.Test;

class DescriptorFormTest {
	@Test
	void convertsDescriptorDefaultsToJsonTypes() {
		var descriptor = new JobLauncherDescriptor("sleep", "Sleep", "/api/jobs",
				List.of(new SubmissionField("taskCount", "Tasks", "integer", true, "4", 1d, 10d, List.of(), ""),
						new SubmissionField("durationMillis", "Duration", "decimal", true, "2.5", 1d, 10d, List.of(),
								"")),
				new OutputDescriptor("server-local", "directory", "Artifacts", false), "small", 1, "now");
		Preferences settings = Preferences.userRoot().node("dev/mechana/test/" + UUID.randomUUID());
		var values = new DescriptorForm(descriptor, settings).values();
		assertEquals(4L, values.get("taskCount"));
		assertEquals(2.5d, values.get("durationMillis"));
		settings.put("taskCount", "7");
		assertEquals(7L, new DescriptorForm(descriptor, settings).values().get("taskCount"));
	}

	@Test
	void descriptorFieldsRemainAccessibleInAScrollPane() {
		var descriptor = new JobLauncherDescriptor("many", "Many", "/api/jobs",
				java.util.stream.IntStream.range(0, 12)
						.mapToObj(index -> new SubmissionField("field" + index, "Field " + index, "integer", true, "0",
								0d, 100d, List.of(), ""))
						.toList(),
				new OutputDescriptor("server-local", "directory", "Artifacts", false), "small", 1, "now");
		Preferences settings = Preferences.userRoot().node("dev/mechana/test/" + UUID.randomUUID());
		DescriptorForm form = new DescriptorForm(descriptor, settings);
		assertEquals(JScrollPane.class, form.getComponent(0).getClass());
	}

	@Test
	void validatesDescriptorProvidedFileExtensions() {
		var descriptor = new JobLauncherDescriptor("ocr", "OCR", "/api/jobs/ocr",
				List.of(new SubmissionField("sourcePath", "Input PDF", "file", true, "document.docx", null, null,
						List.of(), "", List.of("pdf"))),
				new OutputDescriptor("server-local", "directory", "Artifacts", false), "small", 1, "now");
		Preferences settings = Preferences.userRoot().node("dev/mechana/test/" + UUID.randomUUID());
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> new DescriptorForm(descriptor, settings).values());
		assertEquals("Input PDF must be a .pdf file", failure.getMessage());
	}

	@Test
	void acceptsBlankOptionalText() {
		var descriptor = new JobLauncherDescriptor("video", "Video", "/api/jobs/video",
				List.of(new SubmissionField("clientTransferHost", "Client transfer host", "text", false, "", null, null,
						List.of(), "")),
				new OutputDescriptor("client-local", "directory", "Artifacts", false), "small", 1, "now");
		Preferences settings = Preferences.userRoot().node("dev/mechana/test/" + UUID.randomUUID());
		assertEquals("", new DescriptorForm(descriptor, settings).values().get("clientTransferHost"));
	}
}
