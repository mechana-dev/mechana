/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package dev.mechana.plugins.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImpulseResponseCalibrationTest {
	@Test
	void usesOneStereoLinkedGainWithoutChangingChannelBalance() {
		ImpulseResponse response = new ImpulseResponse(48_000, new double[][]{{0.5, 0.25, 0}, {0.1, 0.05, 0}});
		ImpulseResponseCalibration.Result result = ImpulseResponseCalibration.analyze(response);
		ImpulseResponse calibrated = ImpulseResponseCalibration.apply(response, result.gain());

		assertTrue(result.gain() > 1);
		assertEquals(5, calibrated.channel(0)[0] / calibrated.channel(1)[0], 1e-12);
		assertEquals(0, calibrated.channel(0)[2], 0);
	}

	@Test
	void capsNoisyOrVeryQuietCapturesConservatively() {
		double[] quiet = new double[4096];
		java.util.Arrays.fill(quiet, 0.000001);
		var result = ImpulseResponseCalibration.analyze(new ImpulseResponse(48_000, new double[][]{quiet}));

		assertEquals(ImpulseResponseCalibration.MAX_BOOST, result.gain(), 1e-12);
		assertTrue(result.boostLimited());
	}

	@Test
	void energyMeasurementIsSampleRateAware() {
		var at48k = ImpulseResponseCalibration.analyze(new ImpulseResponse(48_000, new double[][]{new double[48_000]}));
		double[] ones48 = new double[48_000];
		double[] ones44 = new double[44_100];
		java.util.Arrays.fill(ones48, 0.01);
		java.util.Arrays.fill(ones44, 0.01);
		at48k = ImpulseResponseCalibration.analyze(new ImpulseResponse(48_000, new double[][]{ones48}));
		var at44k = ImpulseResponseCalibration.analyze(new ImpulseResponse(44_100, new double[][]{ones44}));
		assertEquals(at48k.energy(), at44k.energy(), 1e-9);
	}
}
