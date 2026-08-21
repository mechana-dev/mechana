/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.mechana.localreverb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mechana.plugins.audio.WavFile;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EchoFileRendererTest {
	@TempDir
	Path temporary;

	@Test
	void usesConservativeTailThresholdAndSafetyRepeat() {
		EchoSettings settings = new EchoSettings(EchoSettings.Model.TAPE, 750, 0.48, 1, 0, 0, 0, 0, 0, false);
		long frames = EchoFileRenderer.tailFrames(1_000, settings);
		assertTrue(frames >= 14_000 && frames <= 16_000);
	}

	@Test
	void rendersRequestedDelayAndIncludesTheEchoTail() throws Exception {
		Path source = impulse("source.wav", 1_000, 100);
		Path output = temporary.resolve("echo.wav");
		EchoSettings settings = new EchoSettings(EchoSettings.Model.TAPE, 10, 0.5, 1, 0, 0, 0, 0, 0, false);

		new EchoFileRenderer().render(source, output, settings, ignored -> {
		});

		try (WavFile.Reader reader = WavFile.open(output)) {
			assertTrue(reader.format().frames() > 100);
			double[][] samples = new double[1][(int) reader.format().frames()];
			assertEquals(reader.format().frames(), reader.read(samples, 0, samples[0].length));
			assertEquals(1, samples[0][0], 1.0e-4);
			assertEquals(1, samples[0][10], 1.0e-4);
			assertEquals(EchoSettings.feedbackCoefficient(0.5), samples[0][20], 1.0e-4);
		}
	}

	@Test
	void persistsEchoJobsForTheSharedHistory() throws Exception {
		Path source = impulse("voice.wav", 8_000, 64);
		Path jobs = temporary.resolve("jobs");
		CountDownLatch finished = new CountDownLatch(1);
		AtomicReference<ReverbJob> result = new AtomicReference<>();

		try (LocalEchoEngine engine = new LocalEchoEngine()) {
			engine.submit(source, jobs, "voice-echo.wav", EchoSettings.defaults(EchoSettings.Model.ANALOG), job -> {
				result.set(job);
				if (!"RUNNING".equals(job.status()))
					finished.countDown();
			});
			assertTrue(finished.await(5, TimeUnit.SECONDS));
			assertEquals("SUCCEEDED", result.get().status());
			assertTrue(java.nio.file.Files.isRegularFile(result.get().artifactDirectory().resolve("voice-echo.wav")));
			assertTrue(java.nio.file.Files.readString(result.get().artifactDirectory().resolve("job.json"))
					.contains("\"effect\" : \"Echo\""));
		}
	}

	private Path impulse(String name, int sampleRate, int frames) throws Exception {
		Path path = temporary.resolve(name);
		try (WavFile.Writer writer = WavFile.create24Bit(path, sampleRate, 1, frames)) {
			writer.writeFrame(new double[]{1});
			for (int frame = 1; frame < frames; frame++)
				writer.writeFrame(new double[]{0});
		}
		return path;
	}
}
