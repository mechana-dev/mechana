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

/** Uniform frequency-domain partitioned convolver with overlap-add. */
public final class PartitionedConvolver {
	private final int blockSize;
	private final int fftSize;
	private final double[][] irReal;
	private final double[][] irImaginary;
	private final double[][] inputReal;
	private final double[][] inputImaginary;
	private final double[] overlap;
	private int ringIndex;

	public PartitionedConvolver(double[] impulseResponse, int blockSize) {
		if (impulseResponse.length == 0 || blockSize < 1 || Integer.bitCount(blockSize) != 1)
			throw new IllegalArgumentException("IR must be nonempty and block size a power of two");
		this.blockSize = blockSize;
		fftSize = blockSize * 2;
		int partitions = (impulseResponse.length + blockSize - 1) / blockSize;
		irReal = new double[partitions][fftSize];
		irImaginary = new double[partitions][fftSize];
		inputReal = new double[partitions][fftSize];
		inputImaginary = new double[partitions][fftSize];
		overlap = new double[blockSize];
		for (int partition = 0; partition < partitions; partition++) {
			System.arraycopy(impulseResponse, partition * blockSize, irReal[partition], 0,
					Math.min(blockSize, impulseResponse.length - partition * blockSize));
			FastFourierTransform.transform(irReal[partition], irImaginary[partition], false);
		}
	}

	public double[] process(double[] input, int validFrames) {
		if (input.length < blockSize || validFrames < 0 || validFrames > blockSize)
			throw new IllegalArgumentException("Invalid input block");
		double[] currentReal = inputReal[ringIndex];
		double[] currentImaginary = inputImaginary[ringIndex];
		java.util.Arrays.fill(currentReal, 0);
		java.util.Arrays.fill(currentImaginary, 0);
		System.arraycopy(input, 0, currentReal, 0, validFrames);
		FastFourierTransform.transform(currentReal, currentImaginary, false);
		double[] resultReal = new double[fftSize];
		double[] resultImaginary = new double[fftSize];
		for (int partition = 0; partition < irReal.length; partition++) {
			int inputPartition = Math.floorMod(ringIndex - partition, irReal.length);
			for (int bin = 0; bin < fftSize; bin++) {
				double real = inputReal[inputPartition][bin] * irReal[partition][bin]
						- inputImaginary[inputPartition][bin] * irImaginary[partition][bin];
				double imaginary = inputReal[inputPartition][bin] * irImaginary[partition][bin]
						+ inputImaginary[inputPartition][bin] * irReal[partition][bin];
				resultReal[bin] += real;
				resultImaginary[bin] += imaginary;
			}
		}
		FastFourierTransform.transform(resultReal, resultImaginary, true);
		double[] output = new double[blockSize];
		for (int index = 0; index < blockSize; index++) {
			output[index] = resultReal[index] + overlap[index];
			overlap[index] = resultReal[index + blockSize];
		}
		ringIndex = (ringIndex + 1) % irReal.length;
		return output;
	}

	public int partitionCount() {
		return irReal.length;
	}
}
