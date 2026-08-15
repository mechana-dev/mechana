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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Streams a dry WAV through channel-aware partitioned convolution. */
public final class AudioConvolutionProcessor {
	private static final int DRY_END_FADE_MILLISECONDS = 10;
	/**
	 * Shared preview/render block size; keeping this identical avoids
	 * path-dependent FFT rounding.
	 */
	public static final int DEFAULT_BLOCK_SIZE = 1024;

	public record Options(double wet, double dry, double preDelayMilliseconds, double lowCutHertz, double highCutHertz,
			double earlyLevel, double lateLevel, double attackMilliseconds, double decayLengthPercent,
			boolean normalizeIr, boolean peakProtection, double headroomDecibels, int blockSize,
			double irCalibrationGain) {
		public Options {
			if (!Double.isFinite(wet) || !Double.isFinite(dry) || wet < 0 || dry < 0
					|| !Double.isFinite(preDelayMilliseconds) || preDelayMilliseconds < 0
					|| !Double.isFinite(lowCutHertz) || lowCutHertz < 0 || !Double.isFinite(highCutHertz)
					|| highCutHertz < 0 || !Double.isFinite(headroomDecibels) || headroomDecibels < 0 || blockSize < 1
					|| Integer.bitCount(blockSize) != 1 || !Double.isFinite(irCalibrationGain)
					|| irCalibrationGain <= 0)
				throw new IllegalArgumentException("Invalid convolution options");
			new ImpulseResponseShaper.Options(earlyLevel, lateLevel, attackMilliseconds, decayLengthPercent);
		}

		public Options(double wet, double dry, double preDelayMilliseconds, double lowCutHertz, double highCutHertz,
				double earlyLevel, double lateLevel, double attackMilliseconds, double decayLengthPercent,
				boolean normalizeIr, boolean peakProtection, double headroomDecibels, int blockSize) {
			this(wet, dry, preDelayMilliseconds, lowCutHertz, highCutHertz, earlyLevel, lateLevel, attackMilliseconds,
					decayLengthPercent, normalizeIr, peakProtection, headroomDecibels, blockSize, 1);
		}
	}

	public record Result(long frames, int channels, int sampleRate, double appliedGain) {
	}

	public Result process(Path dryPath, Path irPath, Path outputPath, Path workDirectory, Options options,
			IntConsumer progress) throws IOException {
		Objects.requireNonNull(progress, "progress");
		ImpulseResponse ir = ImpulseResponseShaper.shape(
				ImpulseResponseCalibration.apply(ImpulseResponse.read(irPath, false), options.irCalibrationGain()),
				new ImpulseResponseShaper.Options(options.earlyLevel(), options.lateLevel(),
						options.attackMilliseconds(), options.decayLengthPercent()));
		if (options.normalizeIr())
			ir = ir.attenuatePeak();
		Files.createDirectories(workDirectory);
		Path spool = Files.createTempFile(workDirectory, "audio-reverb-", ".f64");
		try (WavFile.Reader dry = WavFile.open(dryPath)) {
			WavFile.Format format = dry.format();
			WetEqualizer.validate(format.sampleRate(), options.lowCutHertz(), options.highCutHertz());
			if (format.sampleRate() != ir.sampleRate())
				throw new IOException(
						"Dry and IR sample rates must match (" + format.sampleRate() + " vs " + ir.sampleRate() + ")");
			if (format.channels() > 2 || ir.channelCount() > 2)
				throw new IOException("POC supports mono or stereo WAV inputs");
			int outputChannels = Math.max(format.channels(), ir.channelCount());
			long preDelay = Math.round(options.preDelayMilliseconds() * format.sampleRate() / 1000.0);
			long wetFrames = Math.addExact(Math.addExact(format.frames(), ir.length() - 1L), preDelay);
			long outputFrames = Math.max(format.frames(), wetFrames);
			PartitionedConvolver[] convolvers = new PartitionedConvolver[outputChannels];
			for (int channel = 0; channel < outputChannels; channel++)
				convolvers[channel] = new PartitionedConvolver(ir.channel(Math.min(channel, ir.channelCount() - 1)),
						options.blockSize());
			double gain = convolve(dry, spool, format, outputChannels, outputFrames, preDelay, options, convolvers,
					progress);
			write(spool, outputPath, format.sampleRate(), outputChannels, outputFrames);
			progress.accept(100);
			return new Result(outputFrames, outputChannels, format.sampleRate(), gain);
		} finally {
			Files.deleteIfExists(spool);
		}
	}

	private static double convolve(WavFile.Reader dry, Path spool, WavFile.Format format, int outputChannels,
			long outputFrames, long preDelay, Options options, PartitionedConvolver[] convolvers, IntConsumer progress)
			throws IOException {
		int blockSize = options.blockSize();
		double[][] input = new double[format.channels()][blockSize];
		long sourcePosition = 0;
		long outputPosition = 0;
		StreamingPeakProtector protector = new StreamingPeakProtector(format.sampleRate(), outputChannels);
		double target = Math.pow(10, -options.headroomDecibels() / 20);
		double[] mixedFrame = new double[outputChannels];
		double[] protectedFrame = new double[outputChannels];
		double[] ignoredPassthrough = new double[outputChannels];
		DelayLine[] delays = new DelayLine[outputChannels];
		WetEqualizer[] equalizers = new WetEqualizer[outputChannels];
		for (int channel = 0; channel < outputChannels; channel++) {
			delays[channel] = new DelayLine(preDelay);
			equalizers[channel] = new WetEqualizer(format.sampleRate(), options.lowCutHertz(), options.highCutHertz());
		}
		try (DataOutputStream temporary = new DataOutputStream(
				new BufferedOutputStream(Files.newOutputStream(spool)))) {
			while (outputPosition < outputFrames + protector.latencyFrames()) {
				for (double[] channel : input)
					java.util.Arrays.fill(channel, 0);
				int sourceFrames = sourcePosition < format.frames()
						? dry.read(input, 0, (int) Math.min(blockSize, format.frames() - sourcePosition))
						: 0;
				double[][] wet = new double[outputChannels][];
				for (int channel = 0; channel < outputChannels; channel++)
					wet[channel] = convolvers[channel].process(input[Math.min(channel, input.length - 1)],
							sourceFrames);
				int count = (int) Math.min(blockSize, outputFrames + protector.latencyFrames() - outputPosition);
				for (int frame = 0; frame < count; frame++) {
					for (int channel = 0; channel < outputChannels; channel++) {
						long absolute = outputPosition + frame;
						double drySample = absolute < format.frames()
								? input[Math.min(channel, input.length - 1)][frame]
										* dryEndEnvelope(absolute, format.frames(), format.sampleRate())
								: 0;
						double wetSample = equalizers[channel].process(delays[channel].push(wet[channel][frame]));
						mixedFrame[channel] = absolute < outputFrames
								? drySample * options.dry() + wetSample * options.wet()
								: 0;
					}
					if (protector.push(mixedFrame, mixedFrame, target, options.peakProtection(), protectedFrame,
							ignoredPassthrough))
						for (double sample : protectedFrame)
							temporary.writeDouble(sample);
				}
				sourcePosition += sourceFrames;
				outputPosition += count;
				progress.accept(Math.min(95, (int) (Math.min(outputPosition, outputFrames) * 95 / outputFrames)));
			}
		}
		return protector.minimumGain();
	}

	private static double dryEndEnvelope(long frame, long totalFrames, int sampleRate) {
		long fadeFrames = Math.round(DRY_END_FADE_MILLISECONDS * sampleRate / 1000.0);
		if (fadeFrames < 2 || totalFrames <= fadeFrames || frame < totalFrames - fadeFrames)
			return 1;
		return (double) (totalFrames - frame - 1) / (fadeFrames - 1);
	}

	private static void write(Path spool, Path output, int sampleRate, int channels, long frames) throws IOException {
		try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(spool)));
				WavFile.Writer writer = WavFile.create24Bit(output, sampleRate, channels, frames)) {
			double[] frame = new double[channels];
			for (long index = 0; index < frames; index++) {
				for (int channel = 0; channel < channels; channel++)
					frame[channel] = input.readDouble();
				writer.writeFrame(frame);
			}
			if (input.read() != -1)
				throw new EOFException("Temporary audio stream has trailing samples");
		}
	}

	private static final class DelayLine {
		private final double[] samples;
		private int position;

		private DelayLine(long delayFrames) throws IOException {
			if (delayFrames > Integer.MAX_VALUE)
				throw new IOException("Pre-delay is too long for the POC");
			samples = new double[(int) delayFrames];
		}

		private double push(double sample) {
			if (samples.length == 0)
				return sample;
			double delayed = samples[position];
			samples[position] = sample;
			position = (position + 1) % samples.length;
			return delayed;
		}
	}
}
