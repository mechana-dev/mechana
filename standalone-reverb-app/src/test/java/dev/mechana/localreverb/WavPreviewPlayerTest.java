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
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WavPreviewPlayerTest {
	@TempDir
	Path temporary;

	@Test
	void streamsProcessedBlocksAndTheFullEchoTailDirectlyToTheAudioSink() throws Exception {
		Path input = temporary.resolve("source.wav");
		try (WavFile.Writer writer = WavFile.create24Bit(input, 1_000, 1, 100)) {
			writer.writeFrame(new double[]{0.5});
			for (int frame = 1; frame < 100; frame++)
				writer.writeFrame(new double[]{0});
		}
		ByteArrayOutputStream played = new ByteArrayOutputStream();
		CountDownLatch finished = new CountDownLatch(1);
		AtomicReference<String> failure = new AtomicReference<>();
		EchoSettings settings = new EchoSettings(EchoSettings.Model.TAPE, 10, 0.5, 0.5, 0, 0, 0, 0, 0, false);

		try (WavPreviewPlayer player = new WavPreviewPlayer()) {
			player.setAudioSinkFactory((sampleRate, channels) -> new CapturingSink(played));
			player.play(input, settings, 0, state -> {
				if (state == ReverbPreviewPlayer.State.FINISHED)
					finished.countDown();
			}, message -> {
				failure.set(message);
				finished.countDown();
			});
			assertTrue(finished.await(5, TimeUnit.SECONDS));
		}

		assertEquals(null, failure.get());
		byte[] pcm = played.toByteArray();
		assertTrue(pcm.length > 200, "Preview must stream beyond the dry source to preserve the tail");
		assertEquals(8_192, sample(pcm, 0), 1);
		assertEquals(8_192, sample(pcm, 10), 1);
		assertEquals(Math.round(8_192 * EchoSettings.feedbackCoefficient(0.5)), sample(pcm, 20), 1);
	}

	private static int sample(byte[] pcm, int frame) {
		int index = frame * 2;
		return (short) ((pcm[index] & 0xff) | pcm[index + 1] << 8);
	}

	private static final class CapturingSink implements ReverbPreviewPlayer.AudioSink {
		private final ByteArrayOutputStream output;

		private CapturingSink(ByteArrayOutputStream output) {
			this.output = output;
		}

		@Override
		public void start() {
		}

		@Override
		public void write(byte[] samples, int length) {
			output.write(samples, 0, length);
		}

		@Override
		public void drain() {
		}

		@Override
		public void pause() {
		}

		@Override
		public void resume() {
		}

		@Override
		public void stop() {
		}

		@Override
		public void close() {
		}
	}
}
