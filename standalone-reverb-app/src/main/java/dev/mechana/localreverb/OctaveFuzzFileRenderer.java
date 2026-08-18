/* Copyright (c) 2026 Mark Vita. Licensed under the Apache License, Version 2.0. */
package dev.mechana.localreverb;

import dev.mechana.plugins.audio.DryAudioImporter;
import dev.mechana.plugins.audio.WavFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntConsumer;

final class OctaveFuzzFileRenderer {
	private static final int BLOCK = 1_024;

	Path render(Path source, Path output, OctaveFuzzSettings settings, IntConsumer progress) throws IOException {
		Path prepared = source;
		Path temporary = null;
		if (!source.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".wav")) {
			temporary = Files.createTempFile("mechana-fuzz-source-", ".wav");
			prepared = DryAudioImporter.prepare(source, 48_000, temporary);
		}
		try (WavFile.Reader reader = WavFile.open(prepared)) {
			WavFile.Format format = reader.format();
			if (format.channels() < 1 || format.channels() > 2)
				throw new IOException("Octave Fuzz supports mono or stereo audio");
			double[][] block = new double[format.channels()][BLOCK];
			double[] frame = new double[format.channels()];
			OctaveFuzzProcessor processor = new OctaveFuzzProcessor(format.sampleRate(), format.channels());
			try (WavFile.Writer writer = WavFile.create24Bit(output, format.sampleRate(), format.channels(),
					format.frames())) {
				long written = 0;
				while (written < format.frames()) {
					int count = (int) Math.min(BLOCK, format.frames() - written);
					reader.read(block, 0, count);
					processor.process(block, count, settings);
					for (int index = 0; index < count; index++) {
						for (int channel = 0; channel < frame.length; channel++)
							frame[channel] = block[channel][index];
						writer.writeFrame(frame);
					}
					written += count;
					progress.accept((int) Math.min(99, written * 100 / format.frames()));
				}
			}
			progress.accept(100);
			return output;
		} finally {
			if (temporary != null)
				Files.deleteIfExists(temporary);
		}
	}
}
