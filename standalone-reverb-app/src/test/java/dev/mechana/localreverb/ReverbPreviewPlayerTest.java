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

import dev.mechana.plugins.audio.AudioConvolutionProcessor;
import dev.mechana.plugins.audio.WavFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReverbPreviewPlayerTest {
	@TempDir
	Path temporary;

	@Test
	void streamsIdentityIrWithoutCreatingAnArtifact() throws Exception {
		Path dry = wav("dry.wav", 48_000, new double[][]{{0.25, -0.5, 0.125}});
		Path ir = wav("ir.wav", 48_000, new double[][]{{1}});

		byte[] pcm = ReverbPreviewPlayer
				.renderForTest(new ReverbPreviewPlayer.Settings(dry, ir, 1, 0, 0, 0, 0, false, false, 1));

		assertEquals(3 * 2 * 2, pcm.length);
		assertEquals(0.25, sample(pcm, 0), 1.0 / 32768);
		assertEquals(0.25, sample(pcm, 1), 1.0 / 32768);
		assertEquals(-0.5, sample(pcm, 2), 1.0 / 32768);
		assertEquals(0.125, sample(pcm, 4), 1.0 / 32768);
	}

	@Test
	void preservesDrySampleRateByResamplingTheImpulseResponse() throws Exception {
		double[] source = new double[100];
		java.util.Arrays.fill(source, 0.25);
		Path dry = wav("dry-44100.wav", 44_100, new double[][]{source});
		Path ir = wav("ir-48000.wav", 48_000, new double[][]{{1}});

		byte[] pcm = ReverbPreviewPlayer
				.renderForTest(new ReverbPreviewPlayer.Settings(dry, ir, 1, 0, 0, 0, 0, false, false, 1));

		assertEquals(100 * 2 * 2, pcm.length);
		assertEquals(0.25, sample(pcm, 50 * 2), 2.0 / 32768);
	}

	@Test
	void streamsStereoIrAndCompletePredelayedTail() throws Exception {
		Path dry = wav("dry.wav", 1_000, new double[][]{{1, 0}});
		Path ir = wav("ir.wav", 1_000, new double[][]{{1, 0.5}, {0.25, 0.125}});

		byte[] pcm = ReverbPreviewPlayer
				.renderForTest(new ReverbPreviewPlayer.Settings(dry, ir, 1, 0, 2, 0, 0, false, false, 1));

		assertEquals(5 * 2 * 2, pcm.length);
		assertEquals(1, sample(pcm, 2 * 2), 2.0 / 32768);
		assertEquals(0.25, sample(pcm, 2 * 2 + 1), 1.0 / 32768);
		assertEquals(0.5, sample(pcm, 3 * 2), 1.0 / 32768);
		assertEquals(0.125, sample(pcm, 3 * 2 + 1), 1.0 / 32768);
		assertTrue(Math.abs(sample(pcm, 4 * 2)) < 1.0 / 32768);
	}

	@Test
	void previewPeakProtectionLimitsToRequestedHeadroom() throws Exception {
		Path dry = wav("dry.wav", 48_000, new double[][]{{0.9}});
		Path ir = wav("ir.wav", 48_000, new double[][]{{1}});

		byte[] pcm = ReverbPreviewPlayer
				.renderForTest(new ReverbPreviewPlayer.Settings(dry, ir, 1, 1, 0, 0, 0, false, true, 6));

		assertEquals(Math.pow(10, -6.0 / 20), sample(pcm, 0), 1.0 / 32768);
	}

	@Test
	void previewPeakProtectionPreservesWaveformInsteadOfHardClipping() throws Exception {
		Path dry = wav("dynamic-dry.wav", 48_000, new double[][]{{0.9, 0.45}});
		Path ir = wav("dynamic-ir.wav", 48_000, new double[][]{{1}});

		byte[] pcm = ReverbPreviewPlayer
				.renderForTest(new ReverbPreviewPlayer.Settings(dry, ir, 1, 1, 0, 0, 0, false, true, 6));

		double target = Math.pow(10, -6.0 / 20);
		assertEquals(target, sample(pcm, 0), 1.0 / 32768);
		assertEquals(target / 2, sample(pcm, 2), 8.0 / 32768);
	}

	@Test
	void previewPeakProtectionReducesGainBeforeAnOverRangePeak() throws Exception {
		double[] source = new double[1_500];
		java.util.Arrays.fill(source, 0.1);
		source[1_000] = 0.9;
		Path dry = wav("lookahead-dry.wav", 48_000, new double[][]{source});
		Path ir = wav("lookahead-ir.wav", 48_000, new double[][]{{1}});

		byte[] pcm = ReverbPreviewPlayer
				.renderForTest(new ReverbPreviewPlayer.Settings(dry, ir, 1, 1, 0, 0, 0, false, true, 6));

		double target = Math.pow(10, -6.0 / 20);
		assertEquals(0.2, sample(pcm, 400 * 2), 2.0 / 32768);
		assertTrue(sample(pcm, 550 * 2) < 0.18);
		assertTrue(sample(pcm, 550 * 2) > 0.1);
		assertEquals(target, sample(pcm, 1_000 * 2), 2.0 / 32768);
	}

	@Test
	void wetDryAndNormalizationChangesTakeEffectDuringPlayback() throws Exception {
		double[] source = new double[3_000];
		java.util.Arrays.fill(source, 0.5);
		Path dry = wav("dry.wav", 48_000, new double[][]{source});
		Path ir = wav("ir.wav", 48_000, new double[][]{{1}});
		var settings = new ReverbPreviewPlayer.Settings(dry, ir, 0, 1, 0, 0, 0, false, false, 1);

		byte[] pcm = ReverbPreviewPlayer.renderForTest(settings,
				player -> player.update(1, 0, 0, 0, 0, true, false, 1));

		assertEquals(0.5, sample(pcm, 500 * 2), 1.0 / 32768);
		assertEquals(0.5 * Math.pow(10, -1.0 / 20), sample(pcm, 2_500 * 2), 2.0 / 32768);
	}

	@Test
	void bypassReturnsLivePreviewToTheUnprocessedSource() throws Exception {
		double[] source = new double[5_000];
		java.util.Arrays.fill(source, 0.25);
		Path dry = wav("bypass-dry.wav", 48_000, new double[][]{source});
		Path ir = wav("bypass-ir.wav", 48_000, new double[][]{{1}});
		var settings = new ReverbPreviewPlayer.Settings(dry, ir, 1, 1, 0, 0, 0, false, false, 1);

		byte[] pcm = ReverbPreviewPlayer.renderForTest(settings, player -> player.setBypassed(true));

		assertEquals(0.5, sample(pcm, 500 * 2), 1.0 / 32768);
		assertEquals(0.25, sample(pcm, 4_000 * 2), 1.0 / 32768);
	}

	@Test
	void increasedPredelayTakesEffectAndExtendsPreviewTail() throws Exception {
		double[] source = new double[3_000];
		source[2_000] = 0.75;
		Path dry = wav("dry.wav", 48_000, new double[][]{source});
		Path ir = wav("ir.wav", 48_000, new double[][]{{1}});
		var settings = new ReverbPreviewPlayer.Settings(dry, ir, 1, 0, 0, 0, 0, false, false, 1);

		byte[] pcm = ReverbPreviewPlayer.renderForTest(settings,
				player -> player.update(1, 0, 10, 0, 0, false, false, 1));

		assertEquals((3_000 + 480) * 2 * 2, pcm.length);
		assertTrue(Math.abs(sample(pcm, 2_000 * 2)) < 1.0 / 32768);
		assertEquals(0.75, sample(pcm, 2_480 * 2), 2.0 / 32768);
	}

	@Test
	void changesImpulseResponseDuringPlaybackWithACrossfade() throws Exception {
		double[] source = new double[5_000];
		java.util.Arrays.fill(source, 0.25);
		Path dry = wav("dry.wav", 48_000, new double[][]{source});
		Path firstIr = wav("first-ir.wav", 48_000, new double[][]{{1}});
		Path secondIr = wav("second-ir.wav", 48_000, new double[][]{{-1}});
		var settings = new ReverbPreviewPlayer.Settings(dry, firstIr, 1, 0, 0, 0, 0, false, false, 1);

		byte[] pcm = ReverbPreviewPlayer.renderForTest(settings, player -> {
			try {
				player.changeImpulseResponseNow(secondIr);
			} catch (java.io.IOException failure) {
				throw new java.io.UncheckedIOException(failure);
			}
		});

		assertEquals(0.25, sample(pcm, 500 * 2), 1.0 / 32768);
		assertEquals(-0.25, sample(pcm, 4_000 * 2), 2.0 / 32768);
	}

	@Test
	void capturedResponseShapingShortensPreviewTail() throws Exception {
		Path dry = wav("shaped-dry.wav", 1_000, new double[][]{{1}});
		double[] response = new double[200];
		java.util.Arrays.fill(response, 0.5);
		Path ir = wav("shaped-ir.wav", 1_000, new double[][]{response});
		var settings = new ReverbPreviewPlayer.Settings(dry, ir, 1, 0, 0, 0, 0, 1, 1, 0, 50, false, false, 1);

		byte[] pcm = ReverbPreviewPlayer.renderForTest(settings);

		assertEquals(100 * 2 * 2, pcm.length);
		assertEquals(0, sample(pcm, 99 * 2), 1.0 / 32768);
	}

	@Test
	void loopPlaybackRepeatsTheCompleteClipAndTail() throws Exception {
		Path dry = wav("loop-dry.wav", 1_000, new double[][]{{1, 0}});
		Path ir = wav("loop-ir.wav", 1_000, new double[][]{{1, 0.5}});
		var settings = new ReverbPreviewPlayer.Settings(dry, ir, 1, 0, 0, 0, 0, false, false, 1);

		byte[] pcm = ReverbPreviewPlayer.renderLoopsForTest(settings, 2);

		assertEquals(3 * 2 * 2 * 2, pcm.length);
		assertEquals(1, sample(pcm, 0), 1.0 / 32768);
		assertEquals(0.5, sample(pcm, 1 * 2), 1.0 / 32768);
		assertEquals(1, sample(pcm, 3 * 2), 1.0 / 32768);
		assertEquals(0.5, sample(pcm, 4 * 2), 1.0 / 32768);
	}

	@Test
	void scrubStartsAtRequestedFrameWithRebuiltConvolutionHistory() throws Exception {
		double[] source = new double[4_000];
		for (int frame = 0; frame < source.length; frame++)
			source[frame] = frame / 8_000.0;
		Path dry = wav("seek-dry.wav", 1_000, new double[][]{source});
		Path ir = wav("seek-ir.wav", 1_000, new double[][]{{0.5, 0.25}});
		var settings = new ReverbPreviewPlayer.Settings(dry, ir, 1, 0, 0, 0, 0, false, false, 1);

		byte[] pcm = ReverbPreviewPlayer.renderFromForTest(settings, 0.5);

		assertEquals((2_001) * 2 * 2, pcm.length);
		double expected = source[2_000] * 0.5 + source[1_999] * 0.25;
		assertEquals(expected, sample(pcm, 0), 2.0 / 32768);
	}

	@Test
	void previewAndFinalRenderUseEquivalentCalibratedStreamingDsp() throws Exception {
		double[][] source = new double[2][5_000];
		for (int frame = 0; frame < source[0].length; frame++) {
			source[0][frame] = Math.sin(frame * 0.071) * 0.42;
			source[1][frame] = Math.cos(frame * 0.053) * 0.31;
		}
		double[][] response = new double[2][2_200];
		for (int frame = 0; frame < response[0].length; frame++) {
			double decay = Math.exp(-frame / 420.0);
			response[0][frame] = decay * Math.sin(frame * 0.19) * 0.18;
			response[1][frame] = decay * Math.cos(frame * 0.17) * 0.14;
		}
		Path dry = wav("equivalence-dry.wav", 48_000, source);
		Path ir = wav("equivalence-ir.wav", 48_000, response);
		double calibrationGain = 0.37;
		var settings = new ReverbPreviewPlayer.Settings(dry, ir, 0.6, 1, 20, 90, 9_000, 0.8, 0.7, 8, 85, true, true, 3,
				calibrationGain);
		byte[] preview = ReverbPreviewPlayer.renderForTest(settings);
		Path rendered = temporary.resolve("equivalence-rendered.wav");
		var options = new AudioConvolutionProcessor.Options(0.6, 1, 20, 90, 9_000, 0.8, 0.7, 8, 85, true, true, 3,
				AudioConvolutionProcessor.DEFAULT_BLOCK_SIZE, calibrationGain);
		new AudioConvolutionProcessor().process(dry, ir, rendered, temporary, options, ignored -> {
		});

		try (WavFile.Reader reader = WavFile.open(rendered)) {
			assertEquals(preview.length / 4, reader.format().frames());
			double[][] block = new double[2][1024];
			int previewFrame = 0;
			double squaredError = 0;
			double maximumError = 0;
			long samples = 0;
			for (int count; (count = reader.read(block, 0, block[0].length)) > 0;)
				for (int frame = 0; frame < count; frame++, previewFrame++)
					for (int channel = 0; channel < 2; channel++) {
						double error = block[channel][frame] - sample(preview, previewFrame * 2 + channel);
						squaredError += error * error;
						maximumError = Math.max(maximumError, Math.abs(error));
						samples++;
					}
			double rmsError = Math.sqrt(squaredError / samples);
			assertTrue(rmsError < 0.0001, "Preview/render RMS error was " + rmsError);
			assertTrue(maximumError < 0.001, "Preview/render maximum error was " + maximumError);
		}
	}

	private Path wav(String name, int sampleRate, double[][] channels) throws Exception {
		Path path = temporary.resolve(name);
		try (WavFile.Writer writer = WavFile.create24Bit(path, sampleRate, channels.length, channels[0].length)) {
			for (int frame = 0; frame < channels[0].length; frame++) {
				double[] values = new double[channels.length];
				for (int channel = 0; channel < channels.length; channel++)
					values[channel] = channels[channel][frame];
				writer.writeFrame(values);
			}
		}
		return path;
	}

	private static double sample(byte[] pcm, int index) {
		int offset = index * 2;
		return (short) (Byte.toUnsignedInt(pcm[offset]) | pcm[offset + 1] << 8) / 32768.0;
	}
}
