/* Copyright (c) 2026 Mark Vita. Licensed under the Apache License, Version 2.0. */
package dev.mechana.localreverb;

final class OctaveFuzzProcessor {
	private final int sampleRate;
	private final double[] toneState;
	private final double[] dcInput;
	private final double[] dcOutput;
	private final double[] previousInput;
	private double drive = OctaveFuzzSettings.defaults().drive();
	private double tone = OctaveFuzzSettings.defaults().tone();
	private double level = OctaveFuzzSettings.defaults().level();
	private double octave = OctaveFuzzSettings.defaults().octave();

	OctaveFuzzProcessor(int sampleRate, int channels) {
		this.sampleRate = sampleRate;
		toneState = new double[channels];
		dcInput = new double[channels];
		dcOutput = new double[channels];
		previousInput = new double[channels];
	}

	void process(double[][] audio, int frames, OctaveFuzzSettings settings) {
		if (settings.bypass())
			return;
		double smoothing = 1 - Math.exp(-1 / (0.015 * sampleRate));
		for (int frame = 0; frame < frames; frame++) {
			drive += (settings.drive() - drive) * smoothing;
			tone += (settings.tone() - tone) * smoothing;
			level += (settings.level() - level) * smoothing;
			octave += (settings.octave() - octave) * smoothing;
			double cutoff = 900 * Math.pow(12, tone);
			double lowPass = 1 - Math.exp(-2 * Math.PI * Math.min(cutoff, sampleRate * 0.45) / sampleRate);
			for (int channel = 0; channel < audio.length; channel++) {
				double input = audio[channel][frame];
				double midpoint = (previousInput[channel] + input) * 0.5;
				double first = nonlinear(midpoint, drive, octave, channel);
				double second = nonlinear(input, drive, octave, channel);
				double shaped = (first + second) * 0.5;
				toneState[channel] += lowPass * (shaped - toneState[channel]);
				audio[channel][frame] = Math.max(-1, Math.min(1, toneState[channel] * level * 0.82));
				previousInput[channel] = input;
			}
		}
	}

	private double nonlinear(double input, double fuzz, double octaveBlend, int channel) {
		double gain = 2 + fuzz * 28;
		double clipped = Math.tanh(input * gain);
		double rectified = Math.abs(clipped) * 2;
		double dc = rectified - dcInput[channel] + 0.995 * dcOutput[channel];
		dcInput[channel] = rectified;
		dcOutput[channel] = dc;
		return clipped * (1 - octaveBlend) + dc * octaveBlend;
	}
}
