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

	@Test
	void presentsClientLocalOutputWhenSelected() {
		var descriptor = new JobLauncherDescriptor("video", "Video", "/api/jobs/video",
				List.of(new SubmissionField("storageProvider", "Storage", "choice", true, "server-local", null, null,
						List.of("server-local", "client-local"), "")),
				new OutputDescriptor("server-local", "directory", "Server job artifacts", false), "FFmpeg", 1, "now");
		Preferences settings = Preferences.userRoot().node("dev/mechana/test/" + UUID.randomUUID());
		settings.put("storageProvider", "client-local");
		assertEquals("Output: Client-selected output directory (client-local) — FFmpeg",
				new DescriptorForm(descriptor, settings).outputSummary());
	}

	@Test
	void suggestsReverbOutputNameFromDryFileAndSoundParametersUntilOverridden() {
		Preferences settings = Preferences.userRoot().node("dev/mechana/test/" + UUID.randomUUID());
		DescriptorForm form = new DescriptorForm(reverbDescriptor(), settings);
		form.setValue("dryPath", "/tmp/Scott Voice.wav");
		form.setValue("irPath", "/tmp/Small Plate.WAV");
		assertEquals("Scott-Voice-reverb-ir-Small-Plate-wet0p35-dry1-pre20ms-norm-on.wav",
				form.values().get("outputName"));

		form.setValue("wet", "0.2");
		form.setValue("preDelayMilliseconds", "7.5");
		form.setValue("normalizeIr", "false");
		assertEquals("Scott-Voice-reverb-ir-Small-Plate-wet0p2-dry1-pre7p5ms-norm-off.wav",
				form.values().get("outputName"));

		form.setValue("outputName", "my-version.wav");
		form.setValue("wet", "0.8");
		assertEquals("my-version.wav", form.values().get("outputName"));
	}

	@Test
	void reverbNameUsesCompressedDrySourceStem() {
		assertEquals("Scott-Voice-reverb-ir-Small-Plate-wet0p35-dry1-pre20ms-norm-on.wav", DescriptorForm
				.suggestedReverbOutputName("/tmp/Scott Voice.m4a", "/tmp/Small Plate.wav", "0.35", "1", "20", "true"));
	}

	private static JobLauncherDescriptor reverbDescriptor() {
		return new JobLauncherDescriptor("audio-convolution-reverb", "Reverb", "/api/jobs/audio-reverb", List.of(
				new SubmissionField("dryPath", "Dry", "file", true, "", null, null, List.of(), "", List.of("wav")),
				new SubmissionField("irPath", "IR", "file", true, "", null, null, List.of(), "", List.of("wav")),
				new SubmissionField("outputName", "Output", "text", true, "reverberated.wav", null, null, List.of(),
						""),
				new SubmissionField("wet", "Wet", "decimal", true, "0.35", 0d, 2d, List.of(), ""),
				new SubmissionField("dry", "Dry", "decimal", true, "1.0", 0d, 2d, List.of(), ""),
				new SubmissionField("preDelayMilliseconds", "Pre", "decimal", true, "20", 0d, 10000d, List.of(), ""),
				new SubmissionField("normalizeIr", "Normalize", "choice", true, "true", null, null,
						List.of("true", "false"), "")),
				new OutputDescriptor("server-local", "directory", "Artifacts", false), "small", 1, "now");
	}
}
