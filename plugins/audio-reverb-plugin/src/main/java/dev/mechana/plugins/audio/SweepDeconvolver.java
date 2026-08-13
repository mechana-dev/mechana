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

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/**
 * Converts a recorded wet sweep return into a convolution-ready impulse
 * response.
 */
public final class SweepDeconvolver {
	private static final double REGULARIZATION_RATIO = 1e-10;
	private static final double ACTIVE_RATIO = Math.pow(10, -70.0 / 20);
	private static final int PRE_ROLL_MILLISECONDS = 1;
	private static final int TAIL_MARGIN_MILLISECONDS = 250;
	private static final int MINIMUM_IR_MILLISECONDS = 500;
	private static final int MAXIMUM_IR_SECONDS = 10;

	public record Result(int sampleRate, int channels, long frames, double latencyMilliseconds, double peak) {
	}

	public Result deconvolve(Path excitationPath, Path recordedReturnPath, Path outputPath, IntConsumer progress)
			throws IOException {
		try (WavFile.Reader excitationReader = WavFile.open(excitationPath);
				WavFile.Reader responseReader = WavFile.open(recordedReturnPath)) {
			WavFile.Format excitationFormat = excitationReader.format();
			WavFile.Format responseFormat = responseReader.format();
			if (excitationFormat.sampleRate() != responseFormat.sampleRate())
				throw new IOException("Sweep and recorded return sample rates must match");
			if (excitationFormat.channels() > 2 || responseFormat.channels() > 2)
				throw new IOException("Sweep deconvolution supports mono or stereo WAV files");
			if (excitationFormat.frames() > Integer.MAX_VALUE || responseFormat.frames() > Integer.MAX_VALUE)
				throw new IOException("Sweep recording is too long for in-memory deconvolution");
			double[][] excitation = read(excitationReader);
			double[][] response = read(responseReader);
			int fftSize = fftSize(Math.addExact(excitationFormat.frames(), responseFormat.frames()));
			double[][] impulse = new double[responseFormat.channels()][];
			int peakIndex = 0;
			double peak = 0;
			for (int channel = 0; channel < response.length; channel++) {
				impulse[channel] = deconvolve(excitation[Math.min(channel, excitation.length - 1)], response[channel],
						fftSize);
				for (int frame = 0; frame < response[channel].length; frame++)
					if (Math.abs(impulse[channel][frame]) > peak) {
						peak = Math.abs(impulse[channel][frame]);
						peakIndex = frame;
					}
				progress.accept(10 + (channel + 1) * 70 / response.length);
			}
			int sampleRate = excitationFormat.sampleRate();
			int start = Math.max(0, peakIndex - sampleRate * PRE_ROLL_MILLISECONDS / 1000);
			int tail = Math.max(sampleRate * MINIMUM_IR_MILLISECONDS / 1000, activeEnd(response) - activeEnd(excitation)
					- peakIndex + sampleRate * TAIL_MARGIN_MILLISECONDS / 1000);
			int frames = Math.min(Math.min(tail, sampleRate * MAXIMUM_IR_SECONDS), impulse[0].length - start);
			write(outputPath, sampleRate, impulse, start, frames);
			progress.accept(100);
			return new Result(sampleRate, response.length, frames, peakIndex * 1000.0 / sampleRate, peak);
		}
	}

	private static double[] deconvolve(double[] excitation, double[] response, int size) {
		double[] excitationReal = new double[size];
		double[] excitationImaginary = new double[size];
		double[] responseReal = new double[size];
		double[] responseImaginary = new double[size];
		System.arraycopy(excitation, 0, excitationReal, 0, excitation.length);
		System.arraycopy(response, 0, responseReal, 0, response.length);
		FastFourierTransform.transform(excitationReal, excitationImaginary, false);
		FastFourierTransform.transform(responseReal, responseImaginary, false);
		double maximumPower = 0;
		for (int bin = 0; bin < size; bin++)
			maximumPower = Math.max(maximumPower,
					excitationReal[bin] * excitationReal[bin] + excitationImaginary[bin] * excitationImaginary[bin]);
		double regularization = maximumPower * REGULARIZATION_RATIO;
		for (int bin = 0; bin < size; bin++) {
			double denominator = excitationReal[bin] * excitationReal[bin]
					+ excitationImaginary[bin] * excitationImaginary[bin] + regularization;
			double real = (responseReal[bin] * excitationReal[bin] + responseImaginary[bin] * excitationImaginary[bin])
					/ denominator;
			double imaginary = (responseImaginary[bin] * excitationReal[bin]
					- responseReal[bin] * excitationImaginary[bin]) / denominator;
			responseReal[bin] = real;
			responseImaginary[bin] = imaginary;
		}
		FastFourierTransform.transform(responseReal, responseImaginary, true);
		return responseReal;
	}

	private static void write(Path output, int sampleRate, double[][] impulse, int start, int frames)
			throws IOException {
		int fadeFrames = Math.min(sampleRate / 20, frames);
		try (WavFile.Writer writer = WavFile.create24Bit(output, sampleRate, impulse.length, frames)) {
			for (int frame = 0; frame < frames; frame++) {
				double fade = frame >= frames - fadeFrames ? (frames - 1.0 - frame) / Math.max(1, fadeFrames - 1) : 1;
				double[] samples = new double[impulse.length];
				for (int channel = 0; channel < samples.length; channel++)
					samples[channel] = impulse[channel][start + frame] * fade;
				writer.writeFrame(samples);
			}
		}
	}

	private static int activeEnd(double[][] samples) {
		double peak = 0;
		for (double[] channel : samples)
			for (double sample : channel)
				peak = Math.max(peak, Math.abs(sample));
		double threshold = peak * ACTIVE_RATIO;
		for (int frame = samples[0].length - 1; frame >= 0; frame--)
			for (double[] channel : samples)
				if (Math.abs(channel[frame]) >= threshold)
					return frame;
		return 0;
	}

	private static double[][] read(WavFile.Reader reader) throws IOException {
		double[][] samples = new double[reader.format().channels()][(int) reader.format().frames()];
		int offset = 0;
		while (offset < samples[0].length) {
			int count = reader.read(samples, offset, samples[0].length - offset);
			if (count == 0)
				throw new IOException("Unexpected end of WAV file");
			offset += count;
		}
		return samples;
	}

	private static int fftSize(long required) throws IOException {
		int size = 1;
		while (size < required) {
			if (size > 1 << 29)
				throw new IOException("Sweep recording is too long for radix-2 deconvolution");
			size <<= 1;
		}
		return size;
	}
}
