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

/** Minimal in-place radix-2 complex FFT used by the partitioned convolver. */
final class FastFourierTransform {
	private FastFourierTransform() {
	}

	static void transform(double[] real, double[] imaginary, boolean inverse) {
		int size = real.length;
		if (size != imaginary.length || Integer.bitCount(size) != 1)
			throw new IllegalArgumentException("FFT arrays must have the same power-of-two length");
		for (int index = 1, reversed = 0; index < size; index++) {
			int bit = size >>> 1;
			for (; (reversed & bit) != 0; bit >>>= 1)
				reversed ^= bit;
			reversed ^= bit;
			if (index < reversed) {
				double value = real[index];
				real[index] = real[reversed];
				real[reversed] = value;
				value = imaginary[index];
				imaginary[index] = imaginary[reversed];
				imaginary[reversed] = value;
			}
		}
		for (int length = 2; length <= size; length <<= 1) {
			double angle = (inverse ? 2 : -2) * Math.PI / length;
			double stepReal = Math.cos(angle);
			double stepImaginary = Math.sin(angle);
			for (int start = 0; start < size; start += length) {
				double twiddleReal = 1;
				double twiddleImaginary = 0;
				for (int offset = 0; offset < length / 2; offset++) {
					int even = start + offset;
					int odd = even + length / 2;
					double oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary;
					double oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal;
					double evenReal = real[even];
					double evenImaginary = imaginary[even];
					real[odd] = evenReal - oddReal;
					imaginary[odd] = evenImaginary - oddImaginary;
					real[even] = evenReal + oddReal;
					imaginary[even] = evenImaginary + oddImaginary;
					double nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary;
					twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal;
					twiddleReal = nextReal;
				}
			}
			if (length == size)
				break;
		}
		if (inverse)
			for (int index = 0; index < size; index++) {
				real[index] /= size;
				imaginary[index] /= size;
			}
	}
}
