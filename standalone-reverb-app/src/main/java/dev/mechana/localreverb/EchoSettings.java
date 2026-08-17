/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.mechana.localreverb;

record EchoSettings(Model model, double delayMilliseconds, double feedback, double wet, double dry, double lowCutHertz,
		double highCutHertz, double saturation, double modulationRateHertz, double modulationDepthMilliseconds,
		boolean pingPong) {
	enum Model {
		TAPE("Echoplex-style Tape"), ANALOG("Deluxe Memory Man-style Analog");

		private final String label;

		Model(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	EchoSettings {
		if (model == null || !range(delayMilliseconds, 1, 4_000) || !range(feedback, 0, 0.99) || !range(wet, 0, 2)
				|| !range(dry, 0, 2) || !range(lowCutHertz, 0, 20_000) || !range(highCutHertz, 0, 20_000)
				|| lowCutHertz > 0 && highCutHertz > 0 && lowCutHertz >= highCutHertz || !range(saturation, 0, 1)
				|| !range(modulationRateHertz, 0, 20) || !range(modulationDepthMilliseconds, 0, 20))
			throw new IllegalArgumentException("One or more Echo controls are outside the allowed range");
	}

	static EchoSettings defaults(Model model) {
		return model == Model.TAPE
				? new EchoSettings(model, 375, 0.38, 0.26, 1, 45, 6_000, 0.22, 0.55, 1.6, false)
				: new EchoSettings(model, 330, 0.36, 0.26, 1, 80, 4_500, 0.16, 0.8, 2.8, false);
	}

	private static boolean range(double value, double minimum, double maximum) {
		return Double.isFinite(value) && value >= minimum && value <= maximum;
	}
}
