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
	void producesRepeatAtRequestedDelayAndPreservesDrySignal() {
		EchoSettings settings = new EchoSettings(EchoSettings.Model.TAPE, 10, 0.5, 1, 1, 0, 0, 0, 0, 0, false);
		double[][] audio = new double[1][1_000];
		audio[0][0] = 0.5;
		new EchoProcessor(10_000, 1).process(audio, audio[0].length, settings);
		assertEquals(0.5, audio[0][0], 1.0e-9);
		assertEquals(0.5, audio[0][100], 1.0e-6);
		assertEquals(0.25, audio[0][200], 1.0e-6);
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
}
