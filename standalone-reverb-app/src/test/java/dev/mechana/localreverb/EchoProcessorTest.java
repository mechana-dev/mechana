/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.mechana.localreverb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EchoProcessorTest {
	@Test
	void producesRepeatAtRequestedDelayWithCalibratedFeedback() {
		EchoSettings settings = new EchoSettings(EchoSettings.Model.TAPE, 10, 0.5, 1, 0, 0, 0, 0, 0, false);
		double[][] audio = new double[1][1_000];
		audio[0][0] = 0.5;
		new EchoProcessor(10_000, 1).process(audio, audio[0].length, settings);
		double feedback = EchoSettings.feedbackCoefficient(0.5);
		assertEquals(0, audio[0][0], 1.0e-9);
		assertEquals(0.5, audio[0][100], 1.0e-6);
		assertEquals(0.5 * feedback, audio[0][200], 1.0e-6);
	}

	@Test
	void matchesNativeFeedbackCalibrationAndLegacyMixMigration() {
		assertEquals(0.419, EchoSettings.feedbackCoefficient(0.36), 0.01);
		assertEquals(0.26 / 1.08, EchoSettings.mixFromLegacy(0.26, 0.82), 1.0e-12);
		assertEquals(0, EchoSettings.mixFromLegacy(0, 0), 0);
	}

	@Test
	void mixEndpointsAndAutomationAreSmooth() {
		EchoSettings dry = new EchoSettings(EchoSettings.Model.TAPE, 100, 0, 0, 0, 0, 0, 0, 0, false);
		EchoSettings wet = new EchoSettings(EchoSettings.Model.TAPE, 100, 0, 1, 0, 0, 0, 0, 0, false);
		EchoProcessor processor = new EchoProcessor(10_000, 1);
		double[][] first = new double[][]{{0.5}};
		processor.process(first, 1, dry);
		assertEquals(0.5, first[0][0], 1.0e-12);
		double[][] automated = new double[1][200];
		java.util.Arrays.fill(automated[0], 0.5);
		processor.process(automated, automated[0].length, wet);
		assertTrue(automated[0][0] > 0.49, "Mix automation jumped instead of smoothing");
		assertTrue(automated[0][199] < automated[0][0], "Mix did not move toward its wet target");
	}

	@Test
	void analogMemoryDecayUsesUnitySmallSignalColoration() {
		EchoSettings settings = new EchoSettings(EchoSettings.Model.ANALOG, 10, 0.36, 1, 0, 0, 0.16, 0, 0, false);
		double[][] audio = new double[1][400];
		audio[0][0] = 0.001;
		new EchoProcessor(10_000, 1).process(audio, audio[0].length, settings);
		double ratio = audio[0][200] / audio[0][100];
		assertEquals(EchoSettings.feedbackCoefficient(0.36), ratio, 0.01);
	}

	@Test
	void coloredModelsRemainFinite() {
		double[][] audio = new double[2][4_096];
		audio[0][0] = 0.8;
		audio[1][0] = -0.8;
		new EchoProcessor(48_000, 2).process(audio, audio[0].length, EchoSettings.defaults(EchoSettings.Model.ANALOG));
		for (double[] channel : audio)
			for (double sample : channel)
				assertTrue(Double.isFinite(sample));
	}

	@Test
	void analogMemoryProgressivelyDarkensAndClockWanderIsDeterministic() {
		EchoSettings settings = new EchoSettings(EchoSettings.Model.ANALOG, 20, 0.72, 1, 80, 4_500, 0.16, 0.8, 0,
				false);
		double[][] first = alternatingBurst(8_000, 512);
		double[][] second = alternatingBurst(8_000, 512);
		new EchoProcessor(48_000, 1).process(first, first[0].length, settings);
		new EchoProcessor(48_000, 1).process(second, second[0].length, settings);
		assertTrue(java.util.Arrays.equals(first[0], second[0]), "seeded clock motion must be repeatable");
		assertTrue(highFrequencyProxy(first[0], 2_880, 512) < highFrequencyProxy(first[0], 960, 512) * 0.75,
				"later repeat generations should be darker");
	}

	private static double[][] alternatingBurst(int frames, int burstFrames) {
		double[][] audio = new double[1][frames];
		for (int index = 0; index < burstFrames; index++)
			audio[0][index] = index % 2 == 0 ? 0.2 : -0.2;
		return audio;
	}

	private static double highFrequencyProxy(double[] audio, int start, int count) {
		double signalEnergy = 0;
		double differenceEnergy = 0;
		for (int index = start + 1; index < start + count; index++) {
			signalEnergy += audio[index] * audio[index];
			double difference = audio[index] - audio[index - 1];
			differenceEnergy += difference * difference;
		}
		return differenceEnergy / Math.max(signalEnergy, 1.0e-20);
	}
}
