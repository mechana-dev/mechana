/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.mechana.localreverb;

final class EchoProcessor {
	private final double[][] delay;
	private final double[] lowPass;
	private final double[] highPassInput;
	private final double[] highPassOutput;
	private final int sampleRate;
	private int writePosition;
	private double phase;
	private double smoothedMix;
	private boolean mixInitialized;

	EchoProcessor(int sampleRate, int channels) {
		this.sampleRate = sampleRate;
		delay = new double[channels][sampleRate * 4 + 4];
		lowPass = new double[channels];
		highPassInput = new double[channels];
		highPassOutput = new double[channels];
	}

	void process(double[][] audio, int frames, EchoSettings settings) {
		double baseDelay = settings.delayMilliseconds() * sampleRate / 1_000;
		double depth = settings.modulationDepthMilliseconds() * sampleRate / 1_000;
		double phaseIncrement = 2 * Math.PI * settings.modulationRateHertz() / sampleRate;
		double lowPassCoefficient = Math.exp(-2 * Math.PI * Math.max(1, settings.highCutHertz()) / sampleRate);
		double highPassCoefficient = 1 / (1 + 2 * Math.PI * Math.max(1, settings.lowCutHertz()) / sampleRate);
		double feedback = EchoSettings.feedbackCoefficient(settings.feedback());
		double mixCoefficient = 1 - Math.exp(-1 / (0.01 * sampleRate));
		if (!mixInitialized) {
			smoothedMix = settings.mix();
			mixInitialized = true;
		}
		double[] repeated = new double[audio.length];
		for (int frame = 0; frame < frames; frame++) {
			double modulation = Math.sin(phase);
			if (settings.model() == EchoSettings.Model.TAPE)
				modulation = modulation * 0.78 + Math.sin(phase * 7.13 + 0.7) * 0.15
						+ Math.sin(phase * 13.71 + 2.1) * 0.07;
			double delaySamples = Math.max(1, Math.min(delay[0].length - 4, baseDelay + depth * modulation));
			phase = (phase + phaseIncrement) % (2 * Math.PI);
			for (int channel = 0; channel < audio.length; channel++)
				repeated[channel] = interpolate(delay[channel], writePosition - delaySamples);
			for (int channel = 0; channel < audio.length; channel++) {
				int sourceChannel = settings.pingPong() && audio.length == 2 ? 1 - channel : channel;
				double repeat = repeated[sourceChannel];
				if (settings.highCutHertz() > 0) {
					lowPass[channel] = (1 - lowPassCoefficient) * repeat + lowPassCoefficient * lowPass[channel];
					repeat = lowPass[channel];
				}
				if (settings.lowCutHertz() > 0) {
					double filtered = highPassCoefficient * (highPassOutput[channel] + repeat - highPassInput[channel]);
					highPassInput[channel] = repeat;
					highPassOutput[channel] = filtered;
					repeat = filtered;
				}
				if (settings.saturation() > 0) {
					double drive = 1 + settings.saturation() * 7;
					repeat = settings.model() == EchoSettings.Model.ANALOG
							? (Math.tanh(repeat * drive + 0.035) - Math.tanh(0.035)) / drive
							: Math.tanh(repeat * drive) / drive;
				}
				double input = audio[channel][frame];
				delay[channel][writePosition] = input + feedback * repeat;
				audio[channel][frame] = input * (1 - smoothedMix) + repeat * smoothedMix;
			}
			smoothedMix += (settings.mix() - smoothedMix) * mixCoefficient;
			writePosition = (writePosition + 1) % delay[0].length;
		}
	}

	private static double interpolate(double[] buffer, double position) {
		double wrapped = position % buffer.length;
		if (wrapped < 0)
			wrapped += buffer.length;
		int index = (int) wrapped;
		double fraction = wrapped - index;
		double y0 = buffer[(index + buffer.length - 1) % buffer.length];
		double y1 = buffer[index];
		double y2 = buffer[(index + 1) % buffer.length];
		double y3 = buffer[(index + 2) % buffer.length];
		double c0 = y1;
		double c1 = 0.5 * (y2 - y0);
		double c2 = y0 - 2.5 * y1 + 2 * y2 - 0.5 * y3;
		double c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2);
		return ((c3 * fraction + c2) * fraction + c1) * fraction + c0;
	}
}
