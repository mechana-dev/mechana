/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package dev.mechana.plugins.audio;

import java.io.IOException;
import java.nio.file.Path;

/** Computes a non-destructive, stereo-linked loudness calibration for an IR. */
public final class ImpulseResponseCalibration {
	private static final double REFERENCE_SAMPLE_RATE = 48_000;
	public static final double TARGET_ENERGY = 1.0;
	public static final double MAX_BOOST = Math.pow(10, 12.0 / 20);
	public static final double MAX_PEAK = Math.pow(10, -1.0 / 20);

	public record Result(double gain, double gainDecibels, double energy, double peak, boolean boostLimited) {
	}

	private ImpulseResponseCalibration() {
	}

	public static Result analyze(Path path) throws IOException {
		return analyze(ImpulseResponse.read(path, false));
	}

	public static Result analyze(ImpulseResponse response) {
		double energy = 0;
		double peak = 0;
		for (double[] channel : response.channels()) {
			double channelEnergySquared = 0;
			for (double sample : channel) {
				channelEnergySquared += sample * sample;
				peak = Math.max(peak, Math.abs(sample));
			}
			energy = Math.max(energy, Math.sqrt(channelEnergySquared * REFERENCE_SAMPLE_RATE / response.sampleRate()));
		}
		if (!(energy > 0) || !Double.isFinite(energy))
			return new Result(1, 0, energy, peak, false);
		double desired = TARGET_ENERGY / energy;
		double peakLimit = peak > 0 ? MAX_PEAK / peak : MAX_BOOST;
		double gain = Math.min(desired, Math.min(MAX_BOOST, peakLimit));
		return new Result(gain, 20 * Math.log10(gain), energy, peak, desired > gain);
	}

	public static ImpulseResponse apply(ImpulseResponse response, double gain) {
		if (!Double.isFinite(gain) || gain <= 0)
			throw new IllegalArgumentException("IR calibration gain must be finite and positive");
		double[][] channels = response.channels();
		for (double[] channel : channels)
			for (int index = 0; index < channel.length; index++)
				channel[index] *= gain;
		return new ImpulseResponse(response.sampleRate(), channels);
	}
}
