/* Copyright (c) 2026 Mark Vita. Licensed under the Apache License, Version 2.0. */
package dev.mechana.localreverb;

record OctaveFuzzSettings(double drive, double tone, double level, double octave, boolean bypass) {
	OctaveFuzzSettings {
		if (!range(drive, 0, 1) || !range(tone, 0, 1) || !range(level, 0, 2) || !range(octave, 0, 1))
			throw new IllegalArgumentException("One or more Octave Fuzz controls are outside the allowed range");
	}

	static OctaveFuzzSettings defaults() {
		return new OctaveFuzzSettings(0.62, 0.55, 0.72, 0.65, false);
	}

	private static boolean range(double value, double minimum, double maximum) {
		return Double.isFinite(value) && value >= minimum && value <= maximum;
	}
}
