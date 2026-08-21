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
	private final double[] reconstructionOne;
	private final double[] reconstructionTwo;
	private final double[] highPassInput;
	private final double[] highPassOutput;
	private final int sampleRate;
	private int writePosition;
	private double phase;
	private double flutterPhase;
	private double wander;
	private double wanderTarget;
	private int wanderCountdown;
	private long randomState = 0x4d656368L;
	private double smoothedDepth;
	private double smoothedRate;
	private double smoothedMix;
	private boolean mixInitialized;

	EchoProcessor(int sampleRate, int channels) {
		this.sampleRate = sampleRate;
		delay = new double[channels][sampleRate * 4 + 4];
		lowPass = new double[channels];
		reconstructionOne = new double[channels];
		reconstructionTwo = new double[channels];
		highPassInput = new double[channels];
		highPassOutput = new double[channels];
	}

	void process(double[][] audio, int frames, EchoSettings settings) {
		double baseDelay = settings.delayMilliseconds() * sampleRate / 1_000;
		double depth = settings.modulationDepthMilliseconds() * sampleRate / 1_000;
		double lowPassCoefficient = Math.exp(-2 * Math.PI * Math.max(1, settings.highCutHertz()) / sampleRate);
		double reconstructionCutoff = Math.min(settings.highCutHertz() * 0.55, 3_200);
		double reconstructionCoefficient = Math.exp(-2 * Math.PI * Math.max(1, reconstructionCutoff) / sampleRate);
		double highPassCoefficient = 1 / (1 + 2 * Math.PI * Math.max(1, settings.lowCutHertz()) / sampleRate);
		double feedback = EchoSettings.feedbackCoefficient(settings.feedback());
		double mixCoefficient = 1 - Math.exp(-1 / (0.01 * sampleRate));
		if (!mixInitialized) {
			smoothedMix = settings.mix();
			smoothedDepth = depth;
			smoothedRate = settings.modulationRateHertz();
			mixInitialized = true;
		}
		double modulationSmoothing = 1 - Math.exp(-1 / (0.02 * sampleRate));
		double[] repeated = new double[audio.length];
		for (int frame = 0; frame < frames; frame++) {
			smoothedDepth += (depth - smoothedDepth) * modulationSmoothing;
			smoothedRate += (settings.modulationRateHertz() - smoothedRate) * modulationSmoothing;
			double modulation = Math.sin(phase);
			if (settings.model() == EchoSettings.Model.TAPE) {
				modulation = modulation * 0.78 + Math.sin(phase * 7.13 + 0.7) * 0.15
						+ Math.sin(phase * 13.71 + 2.1) * 0.07;
			} else {
				if (wanderCountdown == 0) {
					randomState = (randomState * 1_664_525 + 1_013_904_223) & 0xffff_ffffL;
					wanderTarget = ((randomState >>> 8) * (2.0 / 16_777_215.0)) - 1;
					wanderCountdown = Math.max(1, (int) (sampleRate * 0.037));
				}
				wanderCountdown--;
				wander += (wanderTarget - wander) * (1 - Math.exp(-1 / (sampleRate * 0.18)));
				modulation = modulation * 0.91 + Math.sin(flutterPhase) * 0.055 + wander * 0.035;
			}
			double delaySamples = Math.max(1, Math.min(delay[0].length - 4, baseDelay + smoothedDepth * modulation));
			double phaseIncrement = 2 * Math.PI * smoothedRate / sampleRate;
			phase = (phase + phaseIncrement) % (2 * Math.PI);
			flutterPhase = (flutterPhase + phaseIncrement * 6.83) % (2 * Math.PI);
			for (int channel = 0; channel < audio.length; channel++)
				repeated[channel] = interpolate(delay[channel], writePosition - delaySamples);
			for (int channel = 0; channel < audio.length; channel++) {
				int sourceChannel = settings.pingPong() && audio.length == 2 ? 1 - channel : channel;
				double repeat = settings.model() == EchoSettings.Model.ANALOG && audio.length == 2
						&& !settings.pingPong() ? 0.5 * (repeated[0] + repeated[1]) : repeated[sourceChannel];
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
					double bias = 0.045;
					double smallSignalGain = 1 - Math.tanh(bias) * Math.tanh(bias);
					repeat = settings.model() == EchoSettings.Model.ANALOG
							? (Math.tanh(repeat * drive + bias) - Math.tanh(bias)) / (drive * smallSignalGain)
							: Math.tanh(repeat * drive) / drive;
				}
				double wetOutput = repeat;
				if (settings.model() == EchoSettings.Model.ANALOG && settings.highCutHertz() > 0) {
					reconstructionOne[channel] = (1 - reconstructionCoefficient) * wetOutput
							+ reconstructionCoefficient * reconstructionOne[channel];
					reconstructionTwo[channel] = (1 - reconstructionCoefficient) * reconstructionOne[channel]
							+ reconstructionCoefficient * reconstructionTwo[channel];
					wetOutput = reconstructionTwo[channel];
				}
				double input = audio[channel][frame];
				delay[channel][writePosition] = input + feedback * repeat;
				audio[channel][frame] = input * (1 - smoothedMix) + wetOutput * smoothedMix;
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
