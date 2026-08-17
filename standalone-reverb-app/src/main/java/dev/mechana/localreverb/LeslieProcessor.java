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

final class LeslieProcessor {
	private static final double TWO_PI = Math.PI * 2;
	private final int sampleRate;
	private final double[][] hornDelay;
	private final double[][] drumDelay;
	private final double[] crossoverState;
	private int writePosition;
	private double hornPhase;
	private double drumPhase = Math.PI * 0.37;
	private double hornRpm;
	private double drumRpm;

	LeslieProcessor(int sampleRate, int channels) {
		this.sampleRate = sampleRate;
		int delaySize = Math.max(64, sampleRate / 50);
		hornDelay = new double[channels][delaySize];
		drumDelay = new double[channels][delaySize];
		crossoverState = new double[channels];
	}

	void process(double[][] audio, int frames, LeslieSettings settings) {
		double crossover = 1 - Math.exp(-TWO_PI * settings.crossoverHertz() / sampleRate);
		double hornTarget = targetRpm(settings.speed(), 44, 402);
		double drumTarget = targetRpm(settings.speed(), 42, 372);
		for (int frame = 0; frame < frames; frame++) {
			hornRpm = approach(hornRpm, hornTarget, hornTarget > hornRpm ? 1.8 : 2.4);
			drumRpm = approach(drumRpm, drumTarget, drumTarget > drumRpm ? 7.0 : 5.5);
			hornPhase = wrap(hornPhase + TWO_PI * hornRpm / 60 / sampleRate);
			drumPhase = wrap(drumPhase + TWO_PI * drumRpm / 60 / sampleRate);
			for (int channel = 0; channel < audio.length; channel++) {
				double input = audio[channel][frame];
				crossoverState[channel] += crossover * (input - crossoverState[channel]);
				double low = crossoverState[channel];
				double high = input - low;
				hornDelay[channel][writePosition] = high;
				drumDelay[channel][writePosition] = low;
				double side = audio.length == 1 ? 0 : channel == 0 ? -1 : 1;
				double width = settings.stereoWidth() * side;
				double distance = 1 - settings.micDistance() * 0.58;
				double hornAngle = hornPhase + width * 0.72;
				double drumAngle = drumPhase - width * 0.45;
				double horn = interpolate(hornDelay[channel], writePosition - 2.2 - 5.8 * (1 + Math.sin(hornAngle)))
						* (0.62 + distance * 0.38 * (0.5 + 0.5 * Math.cos(hornAngle)));
				double drum = interpolate(drumDelay[channel], writePosition - 1.6 - 2.7 * (1 + Math.sin(drumAngle)))
						* (0.74 + distance * 0.26 * (0.5 + 0.5 * Math.cos(drumAngle)));
				double cabinet = drum * (1 - settings.hornLevel()) + horn * settings.hornLevel();
				if (settings.drive() > 0) {
					double gain = 1 + settings.drive() * 5;
					cabinet = Math.tanh(cabinet * gain) / Math.tanh(gain);
				}
				audio[channel][frame] = input * settings.dry() + cabinet * settings.wet();
			}
			writePosition = (writePosition + 1) % hornDelay[0].length;
		}
	}

	private double approach(double current, double target, double seconds) {
		return current + (target - current) * (1 - Math.exp(-1 / (seconds * sampleRate)));
	}

	private static double targetRpm(LeslieSettings.Speed speed, double slow, double fast) {
		return switch (speed) {
			case STOPPED -> 0;
			case SLOW -> slow;
			case FAST -> fast;
		};
	}

	private static double wrap(double phase) {
		return phase >= TWO_PI ? phase - TWO_PI : phase;
	}

	private static double interpolate(double[] buffer, double position) {
		double wrapped = position % buffer.length;
		if (wrapped < 0)
			wrapped += buffer.length;
		int first = (int) wrapped;
		double fraction = wrapped - first;
		double a = buffer[first];
		double b = buffer[(first + 1) % buffer.length];
		return a + (b - a) * fraction;
	}
}
