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
