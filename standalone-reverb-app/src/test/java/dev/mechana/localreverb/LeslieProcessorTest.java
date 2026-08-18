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

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LeslieProcessorTest {
	@Test
	void producesFiniteStereoMotion() {
		double[][] audio = new double[2][48_000];
		for (int frame = 0; frame < audio[0].length; frame++) {
			double value = Math.sin(2 * Math.PI * 440 * frame / 48_000);
			audio[0][frame] = value;
			audio[1][frame] = value;
		}
		new LeslieProcessor(48_000, 2).process(audio, audio[0].length,
				new LeslieSettings(LeslieSettings.Speed.FAST, 0.18, 0.52, 0.35, 0.72, 800, 1, 0));
		double stereoDifference = 0;
		for (int frame = 0; frame < audio[0].length; frame++) {
			assertTrue(Double.isFinite(audio[0][frame]));
			assertTrue(Double.isFinite(audio[1][frame]));
			stereoDifference += Math.abs(audio[0][frame] - audio[1][frame]);
		}
		assertTrue(stereoDifference > 1, "rotating microphones should produce a stereo difference");
	}

	@Test
	void dryOnlyLeavesTheSourceUnchanged() {
		double[][] audio = {{0.2, -0.4, 0.7, -0.1}};
		double[] expected = audio[0].clone();
		LeslieSettings defaults = LeslieSettings.defaults();
		new LeslieProcessor(48_000, 1).process(audio, audio[0].length,
				new LeslieSettings(defaults.speed(), defaults.drive(), defaults.hornLevel(), defaults.micDistance(),
						defaults.stereoWidth(), defaults.crossoverHertz(), 0, 1));
		for (int frame = 0; frame < expected.length; frame++)
			assertTrue(Math.abs(expected[frame] - audio[0][frame]) < 1e-12);
	}
}
