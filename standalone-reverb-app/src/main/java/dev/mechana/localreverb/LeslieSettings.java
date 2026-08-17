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

record LeslieSettings(Speed speed, double drive, double hornLevel, double micDistance, double stereoWidth,
		double crossoverHertz, double wet, double dry) {
	enum Speed {
		STOPPED("Stopped"), SLOW("Slow / Chorale"), FAST("Fast / Tremolo");

		private final String label;

		Speed(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	LeslieSettings {
		if (speed == null || !range(drive, 0, 1) || !range(hornLevel, 0, 1) || !range(micDistance, 0, 1)
				|| !range(stereoWidth, 0, 1) || !range(crossoverHertz, 200, 2_000) || !range(wet, 0, 2)
				|| !range(dry, 0, 2))
			throw new IllegalArgumentException("One or more Leslie controls are outside the allowed range");
	}

	static LeslieSettings defaults() {
		return new LeslieSettings(Speed.SLOW, 0.18, 0.52, 0.35, 0.72, 800, 1, 0);
	}

	private static boolean range(double value, double minimum, double maximum) {
		return Double.isFinite(value) && value >= minimum && value <= maximum;
	}
}
