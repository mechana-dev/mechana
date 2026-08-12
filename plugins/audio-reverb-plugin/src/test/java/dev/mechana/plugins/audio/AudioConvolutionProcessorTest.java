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
package dev.mechana.plugins.audio;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioConvolutionProcessorTest {
	@TempDir
	Path temporary;

	@Test
	void deltaIrPreservesSourceAndTailDuration() throws IOException {
		Path dry = wav("dry.wav", new double[][]{{0.25, -0.5, 0.125}});
		Path ir = wav("ir.wav", new double[][]{{1, 0, 0}});
		Path output = temporary.resolve("output.wav");
		var result = processor(dry, ir, output, options(1, 0, 0, false));
		assertEquals(5, result.frames());
		assertArrayEquals(new double[]{0.25, -0.5, 0.125, 0, 0}, read(output)[0], 2e-7);
	}

	@Test
	void delayedDecayWetDryAndPredelayAreNumericallyCorrect() throws IOException {
		Path dry = wav("dry.wav", new double[][]{{0.5, 0.25}});
		Path ir = wav("ir.wav", new double[][]{{1, 0.5}});
		Path output = temporary.resolve("output.wav");
		processor(dry, ir, output, options(0.5, 0.25, 1, false));
		assertArrayEquals(new double[]{0.125, 0.3125, 0.25, 0.0625}, read(output)[0], 2e-7);
	}

	@Test
	void monoSourcePreservesStereoIrRouting() throws IOException {
		Path dry = wav("dry.wav", new double[][]{{0.5}});
		Path ir = wav("ir.wav", new double[][]{{1, 0.5}, {0.25, -0.25}});
		Path output = temporary.resolve("output.wav");
		processor(dry, ir, output, options(1, 0, 0, false));
		double[][] actual = read(output);
		assertEquals(2, actual.length);
		assertArrayEquals(new double[]{0.5, 0.25}, actual[0], 2e-7);
		assertArrayEquals(new double[]{0.125, -0.125}, actual[1], 2e-7);
	}

	@Test
	void longIrExercisesMultipleFftPartitions() throws IOException {
		double[] drySamples = new double[5000];
		drySamples[0] = 0.2;
		double[] irSamples = new double[5000];
		for (int index = 0; index < irSamples.length; index++)
			irSamples[index] = Math.exp(-index / 700.0) * 0.5;
		Path output = temporary.resolve("output.wav");
		var result = processor(wav("dry.wav", new double[][]{drySamples}), wav("ir.wav", new double[][]{irSamples}),
				output, options(1, 0, 0, false));
		assertEquals(9999, result.frames());
		double[][] actual = read(output);
		assertEquals(0.1, actual[0][0], 2e-6);
		assertEquals(0.1 * Math.exp(-4096 / 700.0), actual[0][4096], 2e-6);
	}

	@Test
	void rejectsMismatchedSampleRates() throws IOException {
		Path dry = wav("dry.wav", 48_000, new double[][]{{1}});
		Path ir = wav("ir.wav", 44_100, new double[][]{{1}});
		IOException failure = assertThrows(IOException.class,
				() -> processor(dry, ir, temporary.resolve("output.wav"), options(1, 0, 0, false)));
		assertTrue(failure.getMessage().contains("sample rates must match"));
	}

	@Test
	void readsSixteenBitPcmAndThirtyTwoBitFloat() throws IOException {
		Path pcm = rawWav("pcm16.wav", 1, 16, new int[]{0, 16384, -16384});
		try (WavFile.Reader reader = WavFile.open(pcm)) {
			double[][] samples = new double[1][3];
			assertEquals(3, reader.read(samples, 0, 3));
			assertArrayEquals(new double[]{0, 0.5, -0.5}, samples[0], 1e-9);
		}
		Path floating = floatWav("float32.wav", new float[]{0, 0.25f, -0.75f});
		try (WavFile.Reader reader = WavFile.open(floating)) {
			double[][] samples = new double[1][3];
			assertTrue(reader.format().floatingPoint());
			assertEquals(3, reader.read(samples, 0, 3));
			assertArrayEquals(new double[]{0, 0.25, -0.75}, samples[0], 1e-9);
		}
	}

	private AudioConvolutionProcessor.Result processor(Path dry, Path ir, Path output,
			AudioConvolutionProcessor.Options options) throws IOException {
		return new AudioConvolutionProcessor().process(dry, ir, output, temporary, options, ignored -> {
		});
	}

	private static AudioConvolutionProcessor.Options options(double wet, double dry, double preDelay,
			boolean protection) {
		return new AudioConvolutionProcessor.Options(wet, dry, preDelay, false, protection, 1, 2048);
	}

	private Path wav(String name, double[][] samples) throws IOException {
		return wav(name, 1000, samples);
	}

	private Path wav(String name, int sampleRate, double[][] samples) throws IOException {
		Path path = temporary.resolve(name);
		try (WavFile.Writer writer = WavFile.create24Bit(path, sampleRate, samples.length, samples[0].length)) {
			for (int frame = 0; frame < samples[0].length; frame++) {
				double[] values = new double[samples.length];
				for (int channel = 0; channel < samples.length; channel++)
					values[channel] = samples[channel][frame];
				writer.writeFrame(values);
			}
		}
		return path;
	}

	private static double[][] read(Path path) throws IOException {
		try (WavFile.Reader reader = WavFile.open(path)) {
			double[][] samples = new double[reader.format().channels()][(int) reader.format().frames()];
			assertEquals(reader.format().frames(), reader.read(samples, 0, samples[0].length));
			assertEquals(24, reader.format().bitsPerSample());
			return samples;
		}
	}

	private Path rawWav(String name, int audioFormat, int bits, int[] samples) throws IOException {
		ByteBuffer data = header(audioFormat, bits, samples.length);
		for (int sample : samples)
			data.putShort((short) sample);
		Path path = temporary.resolve(name);
		Files.write(path, data.array());
		return path;
	}

	private Path floatWav(String name, float[] samples) throws IOException {
		ByteBuffer data = header(3, 32, samples.length);
		for (float sample : samples)
			data.putFloat(sample);
		Path path = temporary.resolve(name);
		Files.write(path, data.array());
		return path;
	}

	private static ByteBuffer header(int audioFormat, int bits, int frames) {
		int dataBytes = frames * bits / 8;
		ByteBuffer data = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
		data.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + dataBytes)
				.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
				.putShort((short) audioFormat).putShort((short) 1).putInt(1000).putInt(1000 * bits / 8)
				.putShort((short) (bits / 8)).putShort((short) bits)
				.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(dataBytes);
		return data;
	}
}
