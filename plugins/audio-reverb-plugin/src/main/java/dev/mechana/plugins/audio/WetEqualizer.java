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
package dev.mechana.plugins.audio;

/** Stateful second-order Butterworth low/high-cut filters for a wet channel. */
public final class WetEqualizer {
	private static final double BUTTERWORTH_Q = Math.sqrt(0.5);
	private final int sampleRate;
	private Biquad lowCut = Biquad.identity();
	private Biquad highCut = Biquad.identity();
	private double lowCutHertz;
	private double highCutHertz;

	public WetEqualizer(int sampleRate, double lowCutHertz, double highCutHertz) {
		if (sampleRate < 1)
			throw new IllegalArgumentException("Sample rate must be positive");
		this.sampleRate = sampleRate;
		update(lowCutHertz, highCutHertz);
	}

	public void update(double newLowCutHertz, double newHighCutHertz) {
		validate(sampleRate, newLowCutHertz, newHighCutHertz);
		if (Double.compare(newLowCutHertz, lowCutHertz) != 0) {
			lowCut = newLowCutHertz == 0 ? Biquad.identity() : Biquad.highPass(sampleRate, newLowCutHertz);
			lowCutHertz = newLowCutHertz;
		}
		if (Double.compare(newHighCutHertz, highCutHertz) != 0) {
			highCut = newHighCutHertz == 0 ? Biquad.identity() : Biquad.lowPass(sampleRate, newHighCutHertz);
			highCutHertz = newHighCutHertz;
		}
	}

	public double process(double sample) {
		return highCut.process(lowCut.process(sample));
	}

	public static void validate(int sampleRate, double lowCutHertz, double highCutHertz) {
		double nyquist = sampleRate / 2.0;
		if (!Double.isFinite(lowCutHertz) || !Double.isFinite(highCutHertz) || lowCutHertz < 0 || highCutHertz < 0
				|| lowCutHertz >= nyquist || highCutHertz >= nyquist
				|| highCutHertz > 0 && lowCutHertz > 0 && lowCutHertz >= highCutHertz)
			throw new IllegalArgumentException(
					"Wet EQ cuts must be off (0) or ordered below Nyquist (" + nyquist + " Hz)");
	}

	private static final class Biquad {
		private final double b0;
		private final double b1;
		private final double b2;
		private final double a1;
		private final double a2;
		private double x1;
		private double x2;
		private double y1;
		private double y2;

		private Biquad(double b0, double b1, double b2, double a1, double a2) {
			this.b0 = b0;
			this.b1 = b1;
			this.b2 = b2;
			this.a1 = a1;
			this.a2 = a2;
		}

		private static Biquad identity() {
			return new Biquad(1, 0, 0, 0, 0);
		}

		private static Biquad lowPass(int sampleRate, double cutoff) {
			return design(sampleRate, cutoff, false);
		}

		private static Biquad highPass(int sampleRate, double cutoff) {
			return design(sampleRate, cutoff, true);
		}

		private static Biquad design(int sampleRate, double cutoff, boolean highPass) {
			double omega = 2 * Math.PI * cutoff / sampleRate;
			double cosine = Math.cos(omega);
			double alpha = Math.sin(omega) / (2 * BUTTERWORTH_Q);
			double a0 = 1 + alpha;
			double b0 = highPass ? (1 + cosine) / 2 : (1 - cosine) / 2;
			double b1 = highPass ? -(1 + cosine) : 1 - cosine;
			double b2 = b0;
			return new Biquad(b0 / a0, b1 / a0, b2 / a0, -2 * cosine / a0, (1 - alpha) / a0);
		}

		private double process(double sample) {
			double output = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
			x2 = x1;
			x1 = sample;
			y2 = y1;
			y1 = output;
			return output;
		}
	}
}
