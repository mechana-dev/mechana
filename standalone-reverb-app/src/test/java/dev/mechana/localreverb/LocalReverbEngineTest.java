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
package dev.mechana.localreverb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mechana.plugins.audio.WavFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalReverbEngineTest {
	@TempDir
	Path temporary;

	@Test
	void runsProductionPluginAndPersistsReloadableLocalJob() throws Exception {
		Path dry = wav("Scott Voice.wav", new double[]{0.25, -0.5, 0.125});
		Path ir = wav("Small Room.wav", new double[]{1});
		Path jobs = temporary.resolve("jobs");
		var request = new ReverbRequest(dry, ir, jobs, "Scott-result.wav", 1, 0, 0, false, true, 1);
		CountDownLatch finished = new CountDownLatch(1);
		AtomicReference<ReverbJob> result = new AtomicReference<>();

		try (var engine = new LocalReverbEngine()) {
			engine.submit(request, job -> {
				result.set(job);
				if (!"RUNNING".equals(job.status()))
					finished.countDown();
			});
			assertTrue(finished.await(5, TimeUnit.SECONDS));
			assertEquals("SUCCEEDED", result.get().status());
			Path directory = result.get().artifactDirectory();
			assertTrue(Files.isRegularFile(directory.resolve("Scott-result.wav")));
			assertTrue(Files.readString(directory.resolve("reverb-job-report.txt")).contains("Plugin version: 1.0.0"));
			assertTrue(Files.readString(directory.resolve("job.json")).contains("\"status\" : \"SUCCEEDED\""));
			assertEquals(result.get().id(), engine.loadJobs(jobs).getFirst().id());
			try (WavFile.Reader output = WavFile.open(directory.resolve("Scott-result.wav"))) {
				assertEquals(3, output.format().frames());
			}
		}
	}

	@Test
	void createsSameDescriptiveNameShapeAsNetworkLauncher() {
		assertEquals("Scott-Voice-reverb-ir-Small-Room-wet0p35-dry1-pre20ms-norm-on.wav", StandaloneReverbFrame
				.suggestedOutputName("/tmp/Scott Voice.wav", "/tmp/Small Room.wav", "0.35", "1.0", "20", true));
	}

	@Test
	void descriptiveNameUsesCompressedDrySourceStem() {
		assertEquals("Scott-Voice-reverb-ir-Small-Room-wet0p35-dry1-pre20ms-norm-on.wav", StandaloneReverbFrame
				.suggestedOutputName("/tmp/Scott Voice.m4a", "/tmp/Small Room.wav", "0.35", "1", "20", true));
	}

	@Test
	void suggestedNameChangesWithEitherAudioInput() {
		String first = StandaloneReverbFrame.suggestedOutputName("/tmp/Voice One.wav", "/tmp/Room One.wav", "0.35",
				"1.0", "20", true);
		String changedDry = StandaloneReverbFrame.suggestedOutputName("/tmp/Voice Two.wav", "/tmp/Room One.wav", "0.35",
				"1.0", "20", true);
		String changedIr = StandaloneReverbFrame.suggestedOutputName("/tmp/Voice One.wav", "/tmp/Room Two.wav", "0.35",
				"1.0", "20", true);
		assertTrue(!first.equals(changedDry));
		assertTrue(!first.equals(changedIr));
		assertTrue(changedDry.startsWith("Voice-Two-reverb-ir-Room-One-"));
		assertTrue(changedIr.startsWith("Voice-One-reverb-ir-Room-Two-"));
	}

	@Test
	void shipsReadableStarterImpulseResponses() throws Exception {
		Path profiles = BundledProfiles.directory();
		assertTrue(profiles != null && Files.isRegularFile(profiles.resolve("README.txt")));
		try (Stream<Path> files = Files.list(profiles)) {
			List<Path> wavs = files.filter(path -> path.getFileName().toString().endsWith(".wav")).sorted().toList();
			assertEquals(6, wavs.size());
			for (Path wav : wavs)
				try (WavFile.Reader input = WavFile.open(wav)) {
					assertEquals(48_000, input.format().sampleRate());
					assertTrue(input.format().frames() > 0);
				}
		}
		Path sweep = BundledProfiles.sweep();
		assertTrue(sweep != null && Files.isRegularFile(sweep));
		try (WavFile.Reader input = WavFile.open(sweep)) {
			assertEquals(48_000, input.format().sampleRate());
			assertEquals(2, input.format().channels());
		}
	}

	private Path wav(String name, double[] samples) throws Exception {
		Path path = temporary.resolve(name);
		try (WavFile.Writer writer = WavFile.create24Bit(path, 48_000, 1, samples.length)) {
			for (double sample : samples)
				writer.writeFrame(new double[]{sample});
		}
		return path;
	}
}
