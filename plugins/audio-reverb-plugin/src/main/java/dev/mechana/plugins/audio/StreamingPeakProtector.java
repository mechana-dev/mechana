/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package dev.mechana.plugins.audio;

/**
 * Stereo-linked streaming look-ahead peak protection shared by preview and
 * render.
 */
public final class StreamingPeakProtector {
	private static final int LOOKAHEAD_MILLISECONDS = 10;
	private static final int ATTACK_MILLISECONDS = 1;
	private static final int RELEASE_MILLISECONDS = 250;
	private final double[][] frames;
	private final double[][] passthroughFrames;
	private final double[] requiredGains;
	private final int latencyFrames;
	private final double attackCoefficient;
	private final double releaseCoefficient;
	private double gain = 1;
	private double minimumGain = 1;
	private int position;
	private int buffered;

	public StreamingPeakProtector(int sampleRate, int channels) {
		latencyFrames = Math.max(1, sampleRate * LOOKAHEAD_MILLISECONDS / 1000);
		frames = new double[latencyFrames][channels];
		passthroughFrames = new double[latencyFrames][channels];
		requiredGains = new double[latencyFrames];
		java.util.Arrays.fill(requiredGains, 1);
		attackCoefficient = 1 - Math.exp(-1 / Math.max(1, sampleRate * ATTACK_MILLISECONDS / 1000.0));
		releaseCoefficient = 1 - Math.exp(-1 / Math.max(1, sampleRate * RELEASE_MILLISECONDS / 1000.0));
	}

	public int latencyFrames() {
		return latencyFrames;
	}

	public double minimumGain() {
		return minimumGain;
	}

	public boolean push(double[] input, double target, boolean enabled, double[] output) {
		return push(input, input, target, enabled, output, new double[output.length]);
	}

	public boolean push(double[] input, double[] passthrough, double target, boolean enabled, double[] output,
			double[] outputPassthrough) {
		double peak = 0;
		for (double sample : input)
			peak = Math.max(peak, Math.abs(sample));
		double inputRequiredGain = enabled && peak > target ? target / peak : 1;
		double required = inputRequiredGain;
		for (double value : requiredGains)
			required = Math.min(required, value);
		if (required < gain)
			gain += (required - gain) * attackCoefficient;
		else
			gain += (1 - gain) * releaseCoefficient;
		boolean ready = buffered >= latencyFrames;
		if (ready) {
			System.arraycopy(frames[position], 0, output, 0, output.length);
			System.arraycopy(passthroughFrames[position], 0, outputPassthrough, 0, outputPassthrough.length);
			double outputPeak = 0;
			for (double sample : output)
				outputPeak = Math.max(outputPeak, Math.abs(sample));
			double safeGain = enabled && outputPeak * gain > target ? target / outputPeak : gain;
			minimumGain = Math.min(minimumGain, safeGain);
			for (int channel = 0; channel < output.length; channel++)
				output[channel] *= safeGain;
		}
		System.arraycopy(input, 0, frames[position], 0, input.length);
		System.arraycopy(passthrough, 0, passthroughFrames[position], 0, passthrough.length);
		requiredGains[position] = inputRequiredGain;
		position = (position + 1) % latencyFrames;
		buffered++;
		return ready;
	}
}
