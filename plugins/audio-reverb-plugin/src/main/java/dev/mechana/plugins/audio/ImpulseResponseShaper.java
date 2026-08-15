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

/** Neutral-bypass shaping of samples already present in a captured IR. */
public final class ImpulseResponseShaper {
	private static final int EARLY_BOUNDARY_MILLISECONDS = 80;
	private static final int EARLY_CROSSFADE_MILLISECONDS = 10;
	private static final int DECAY_FADE_MILLISECONDS = 50;

	public record Options(double earlyLevel, double lateLevel, double attackMilliseconds, double decayLengthPercent) {
		public Options {
			if (!finiteRange(earlyLevel, 0, 2) || !finiteRange(lateLevel, 0, 2)
					|| !finiteRange(attackMilliseconds, 0, 5_000) || !finiteRange(decayLengthPercent, 1, 100))
				throw new IllegalArgumentException("Invalid captured-response shaping controls");
		}

		public boolean neutral() {
			return earlyLevel == 1 && lateLevel == 1 && attackMilliseconds == 0 && decayLengthPercent == 100;
		}
	}

	private ImpulseResponseShaper() {
	}

	public static ImpulseResponse shape(ImpulseResponse source, Options options) {
		if (options.neutral())
			return source;
		int originalLength = source.length();
		int shapedLength = Math.max(1, (int) Math.round(originalLength * options.decayLengthPercent() / 100));
		double[][] input = source.channels();
		double[][] output = new double[input.length][shapedLength];
		int sampleRate = source.sampleRate();
		int boundary = Math.min(originalLength, sampleRate * EARLY_BOUNDARY_MILLISECONDS / 1_000);
		int transition = Math.max(1, sampleRate * EARLY_CROSSFADE_MILLISECONDS / 1_000);
		int attack = (int) Math.round(options.attackMilliseconds() * sampleRate / 1_000);
		int decayFade = options.decayLengthPercent() < 100
				? Math.min(shapedLength, Math.max(2, sampleRate * DECAY_FADE_MILLISECONDS / 1_000))
				: 0;
		for (int channel = 0; channel < input.length; channel++)
			for (int frame = 0; frame < shapedLength; frame++) {
				double earlyBlend = Math.max(0, Math.min(1, (frame - (boundary - transition / 2.0)) / transition));
				double sectionGain = options.earlyLevel() * (1 - earlyBlend) + options.lateLevel() * earlyBlend;
				double attackGain = attack > 0 && frame < attack ? (double) frame / attack : 1;
				double decayGain = decayFade > 0 && frame >= shapedLength - decayFade
						? (double) (shapedLength - frame - 1) / (decayFade - 1)
						: 1;
				output[channel][frame] = input[channel][frame] * sectionGain * attackGain * decayGain;
			}
		return new ImpulseResponse(sampleRate, output);
	}

	private static boolean finiteRange(double value, double minimum, double maximum) {
		return Double.isFinite(value) && value >= minimum && value <= maximum;
	}
}
