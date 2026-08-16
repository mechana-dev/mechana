/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.mechana.localreverb;

import dev.mechana.plugins.audio.DryAudioImporter;
import dev.mechana.plugins.audio.WavFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntConsumer;

final class EchoFileRenderer {
	private static final int BLOCK = 1_024;

	Path render(Path source, Path output, EchoSettings settings, IntConsumer progress) throws IOException {
		Path prepared = source;
		Path temporary = null;
		if (!source.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".wav")) {
			temporary = Files.createTempFile("mechana-echo-source-", ".wav");
			prepared = DryAudioImporter.prepare(source, 48_000, temporary);
		}
		try (WavFile.Reader reader = WavFile.open(prepared)) {
			WavFile.Format format = reader.format();
			if (format.channels() < 1 || format.channels() > 2)
				throw new IOException("Echo supports mono or stereo audio");
			long tail = tailFrames(format.sampleRate(), settings);
			long totalFrames = Math.addExact(format.frames(), tail);
			EchoProcessor processor = new EchoProcessor(format.sampleRate(), format.channels());
			double[][] block = new double[format.channels()][BLOCK];
			double[] frame = new double[format.channels()];
			try (WavFile.Writer writer = WavFile.create24Bit(output, format.sampleRate(), format.channels(),
					totalFrames)) {
				long written = 0;
				while (written < totalFrames) {
					int count = (int) Math.min(BLOCK, totalFrames - written);
					for (double[] channel : block)
						java.util.Arrays.fill(channel, 0, count, 0);
					if (written < format.frames())
						reader.read(block, 0, (int) Math.min(count, format.frames() - written));
					processor.process(block, count, settings);
					for (int index = 0; index < count; index++) {
						for (int channel = 0; channel < frame.length; channel++)
							frame[channel] = block[channel][index];
						writer.writeFrame(frame);
					}
					written += count;
					progress.accept((int) Math.min(99, written * 100 / totalFrames));
				}
			}
			progress.accept(100);
			return output;
		} finally {
			if (temporary != null)
				Files.deleteIfExists(temporary);
		}
	}

	private static long tailFrames(int sampleRate, EchoSettings settings) {
		double feedback = Math.max(0.0001, settings.feedback());
		double repeats = feedback <= 0.0001 ? 1 : Math.ceil(Math.log(0.0001) / Math.log(feedback));
		double seconds = Math.min(30, settings.delayMilliseconds() / 1_000 * Math.max(1, repeats) + 0.1);
		return Math.round(seconds * sampleRate);
	}
}
