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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WetEqualizerTest {
	private static final int SAMPLE_RATE = 48_000;

	@Test
	void highCutAttenuatesHighFrequenciesButPreservesLowFrequencies() {
		double low = response(200, 0, 1_000);
		double high = response(10_000, 0, 1_000);

		assertTrue(low > 0.9, "low-frequency passband should remain near unity");
		assertTrue(high < 0.02, "high-cut should strongly attenuate 10 kHz");
	}

	@Test
	void lowCutAttenuatesLowFrequenciesButPreservesHighFrequencies() {
		double low = response(50, 500, 0);
		double high = response(5_000, 500, 0);

		assertTrue(low < 0.02, "low-cut should strongly attenuate 50 Hz");
		assertTrue(high > 0.9, "high-frequency passband should remain near unity");
	}

	@Test
	void rejectsInvalidOrUnorderedCutoffs() {
		assertThrows(IllegalArgumentException.class, () -> new WetEqualizer(SAMPLE_RATE, 1_000, 500));
		assertThrows(IllegalArgumentException.class, () -> new WetEqualizer(SAMPLE_RATE, 0, 24_000));
	}

	private static double response(double frequency, double lowCut, double highCut) {
		WetEqualizer equalizer = new WetEqualizer(SAMPLE_RATE, lowCut, highCut);
		double inputEnergy = 0;
		double outputEnergy = 0;
		for (int frame = 0; frame < SAMPLE_RATE; frame++) {
			double sample = Math.sin(2 * Math.PI * frequency * frame / SAMPLE_RATE);
			double output = equalizer.process(sample);
			if (frame >= SAMPLE_RATE / 2) {
				inputEnergy += sample * sample;
				outputEnergy += output * output;
			}
		}
		return Math.sqrt(outputEnergy / inputEnergy);
	}
}
