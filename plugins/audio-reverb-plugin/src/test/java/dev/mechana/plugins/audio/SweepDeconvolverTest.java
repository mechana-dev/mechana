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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SweepDeconvolverTest {
	@TempDir
	Path temporary;

	@Test
	void recoversDelayedStereoImpulseResponseWithoutPeakBoost() throws Exception {
		double[] sweep = new double[512];
		for (int index = 0; index < sweep.length; index++)
			sweep[index] = 0.2 * Math.sin(2 * Math.PI * (0.03 * index + 0.0002 * index * index));
		double[] leftIr = delayed(20, 0.2, 0.1, -0.05);
		double[] rightIr = delayed(20, 0.1, -0.05, 0.025);
		Path source = wav("sweep.wav", new double[][]{sweep, sweep});
		Path response = wav("return.wav", new double[][]{convolve(sweep, leftIr), convolve(sweep, rightIr)});
		Path output = temporary.resolve("profile.wav");

		var result = new SweepDeconvolver().deconvolve(source, response, output, ignored -> {
		});

		assertEquals(2, result.channels());
		assertEquals(20, result.latencyMilliseconds(), 1.1);
		try (WavFile.Reader reader = WavFile.open(output)) {
			double[][] actual = new double[2][(int) reader.format().frames()];
			reader.read(actual, 0, actual[0].length);
			assertEquals(0.2, actual[0][1], 2e-3);
			assertEquals(0.1, actual[0][2], 2e-3);
			assertEquals(0.1, actual[1][1], 2e-3);
			assertTrue(reader.format().frames() >= 500);
		}
	}

	private Path wav(String name, double[][] samples) throws Exception {
		Path path = temporary.resolve(name);
		try (WavFile.Writer writer = WavFile.create24Bit(path, 1000, samples.length, samples[0].length)) {
			for (int frame = 0; frame < samples[0].length; frame++) {
				double[] values = new double[samples.length];
				for (int channel = 0; channel < samples.length; channel++)
					values[channel] = samples[channel][frame];
				writer.writeFrame(values);
			}
		}
		return path;
	}

	private static double[] delayed(int delay, double... values) {
		double[] result = new double[delay + values.length];
		System.arraycopy(values, 0, result, delay, values.length);
		return result;
	}

	private static double[] convolve(double[] source, double[] impulse) {
		double[] result = new double[source.length + impulse.length - 1];
		for (int sourceIndex = 0; sourceIndex < source.length; sourceIndex++)
			for (int impulseIndex = 0; impulseIndex < impulse.length; impulseIndex++)
				result[sourceIndex + impulseIndex] += source[sourceIndex] * impulse[impulseIndex];
		return result;
	}
}
