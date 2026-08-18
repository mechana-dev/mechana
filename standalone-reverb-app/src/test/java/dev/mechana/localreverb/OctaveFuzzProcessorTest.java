/* Copyright (c) 2026 Mark Vita. Licensed under the Apache License, Version 2.0. */
package dev.mechana.localreverb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OctaveFuzzProcessorTest {
	@Test
	void bypassIsTransparent() {
		double[][] audio = {{0.2, -0.4, 0.7, -0.1}};
		double[] expected = audio[0].clone();
		new OctaveFuzzProcessor(48_000, 1).process(audio, audio[0].length, new OctaveFuzzSettings(1, 0.5, 1, 1, true));
		assertArrayEquals(expected, audio[0], 0);
	}

	@Test
	void octaveControlRaisesSecondHarmonicAndStereoRoutingRemainsIndependent() {
		int frames = 48_000;
		double[][] without = sine(frames);
		double[][] with = sine(frames);
		new OctaveFuzzProcessor(48_000, 2).process(without, frames, new OctaveFuzzSettings(0.5, 1, 0.7, 0, false));
		new OctaveFuzzProcessor(48_000, 2).process(with, frames, new OctaveFuzzSettings(0.5, 1, 0.7, 1, false));
		assertTrue(magnitude(with[0], 880) > magnitude(without[0], 880) * 1.5);
		for (int frame = 0; frame < frames; frame++) {
			assertTrue(Double.isFinite(with[0][frame]));
			assertTrue(Math.abs(with[1][frame]) < 1e-12);
		}
	}

	private static double[][] sine(int frames) {
		double[][] result = new double[2][frames];
		for (int frame = 0; frame < frames; frame++) {
			result[0][frame] = Math.sin(2 * Math.PI * 440 * frame / 48_000) * 0.25;
			result[1][frame] = 0;
		}
		return result;
	}

	private static double magnitude(double[] values, double frequency) {
		double real = 0, imaginary = 0;
		for (int frame = 4_800; frame < values.length; frame++) {
			double phase = 2 * Math.PI * frequency * frame / 48_000;
			real += values[frame] * Math.cos(phase);
			imaginary -= values[frame] * Math.sin(phase);
		}
		return Math.hypot(real, imaginary);
	}
}
