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

import dev.mechana.plugins.audio.DryAudioImporter;
import dev.mechana.plugins.audio.ImpulseResponse;
import dev.mechana.plugins.audio.ImpulseResponseShaper;
import dev.mechana.plugins.audio.PartitionedConvolver;
import dev.mechana.plugins.audio.WavFile;
import dev.mechana.plugins.audio.WetEqualizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Streams a selected recording through the production convolution primitives.
 */
final class ReverbPreviewPlayer implements AutoCloseable {
	private static final int BLOCK_SIZE = 1024;
	private static final int DRY_END_FADE_MILLISECONDS = 10;
	private static final int PARAMETER_RAMP_MILLISECONDS = 20;
	private static final int IR_CROSSFADE_MILLISECONDS = 50;
	private static final int MAX_PRE_DELAY_MILLISECONDS = 10_000;
	private static final double NORMALIZED_IR_PEAK = Math.pow(10, -1.0 / 20);
	private final AtomicBoolean stopped = new AtomicBoolean(true);
	private final AtomicLong irLoadSequence = new AtomicLong();
	private final ImpulseResponseCache impulseResponseCache;
	private final Object pauseLock = new Object();
	private volatile boolean paused;
	private volatile boolean bypassed;
	private volatile Thread playbackThread;
	private volatile AudioSink activeSink;
	private volatile ConvolutionBank pendingBank;
	private volatile int previewSampleRate;
	private volatile LiveParameters liveParameters = new LiveParameters(0, 0, 0, 0, 0, 1, 1, 0, 100, false, true, 1);

	ReverbPreviewPlayer() {
		this(new ImpulseResponseCache());
	}

	ReverbPreviewPlayer(ImpulseResponseCache impulseResponseCache) {
		this.impulseResponseCache = Objects.requireNonNull(impulseResponseCache, "impulseResponseCache");
	}

	enum State {
		PREPARING, REGENERATING_IR, PLAYING, PAUSED, STOPPED, FINISHED
	}

	record Settings(Path dryPath, Path irPath, double wet, double dry, double preDelayMilliseconds, double lowCutHertz,
			double highCutHertz, double earlyLevel, double lateLevel, double attackMilliseconds,
			double decayLengthPercent, boolean normalizeIr, boolean peakProtection, double headroomDecibels) {
		Settings {
			Objects.requireNonNull(dryPath, "dryPath");
			Objects.requireNonNull(irPath, "irPath");
			if (!Double.isFinite(wet) || wet < 0 || !Double.isFinite(dry) || dry < 0
					|| !Double.isFinite(preDelayMilliseconds) || preDelayMilliseconds < 0
					|| !validFrequency(lowCutHertz) || !validFrequency(highCutHertz)
					|| lowCutHertz > 0 && highCutHertz > 0 && lowCutHertz >= highCutHertz
					|| !validShaping(earlyLevel, lateLevel, attackMilliseconds, decayLengthPercent)
					|| !Double.isFinite(headroomDecibels) || headroomDecibels < 0)
				throw new IllegalArgumentException("Invalid preview settings");
		}

		Settings(Path dryPath, Path irPath, double wet, double dry, double preDelayMilliseconds, double lowCutHertz,
				double highCutHertz, boolean normalizeIr, boolean peakProtection, double headroomDecibels) {
			this(dryPath, irPath, wet, dry, preDelayMilliseconds, lowCutHertz, highCutHertz, 1, 1, 0, 100, normalizeIr,
					peakProtection, headroomDecibels);
		}
	}

	private record LiveParameters(double wet, double dry, double preDelayMilliseconds, double lowCutHertz,
			double highCutHertz, double earlyLevel, double lateLevel, double attackMilliseconds,
			double decayLengthPercent, boolean normalizeIr, boolean peakProtection, double headroomDecibels) {
	}

	interface AudioSink extends AutoCloseable {
		void start() throws IOException;

		void write(byte[] samples, int length) throws IOException;

		void drain() throws IOException;

		void pause();

		void resume();

		void stop();

		@Override
		void close();
	}

	interface AudioSinkFactory {
		AudioSink open(int sampleRate, int channels) throws IOException;
	}

	void play(Settings settings, Consumer<State> state, Consumer<String> failure) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(failure, "failure");
		Thread previous = playbackThread;
		stop();
		if (previous != null)
			try {
				previous.join(1_000);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				failure.accept("Could not stop the previous preview");
				return;
			}
		stopped.set(false);
		paused = false;
		update(settings.wet(), settings.dry(), settings.preDelayMilliseconds(), settings.lowCutHertz(),
				settings.highCutHertz(), settings.earlyLevel(), settings.lateLevel(), settings.attackMilliseconds(),
				settings.decayLengthPercent(), settings.normalizeIr(), settings.peakProtection(),
				settings.headroomDecibels());
		Thread thread = Thread.ofVirtual().name("mechana-reverb-preview").start(() -> {
			state.accept(State.PREPARING);
			try {
				stream(settings, systemSink(), state);
				if (!stopped.get())
					state.accept(State.FINISHED);
			} catch (IOException | RuntimeException playbackFailure) {
				if (!stopped.get())
					failure.accept(rootMessage(playbackFailure));
			} finally {
				stopped.set(true);
				activeSink = null;
				playbackThread = null;
			}
		});
		playbackThread = thread;
	}

	void update(double wet, double dry, double preDelayMilliseconds, double lowCutHertz, double highCutHertz,
			double earlyLevel, double lateLevel, double attackMilliseconds, double decayLengthPercent,
			boolean normalizeIr, boolean peakProtection, double headroomDecibels) {
		if (!Double.isFinite(wet) || wet < 0 || wet > 2 || !Double.isFinite(dry) || dry < 0 || dry > 2
				|| !Double.isFinite(preDelayMilliseconds) || preDelayMilliseconds < 0
				|| preDelayMilliseconds > MAX_PRE_DELAY_MILLISECONDS || !Double.isFinite(headroomDecibels)
				|| !validFrequency(lowCutHertz) || !validFrequency(highCutHertz)
				|| lowCutHertz > 0 && highCutHertz > 0 && lowCutHertz >= highCutHertz || headroomDecibels < 0)
			throw new IllegalArgumentException("Invalid live preview settings");
		if (!validShaping(earlyLevel, lateLevel, attackMilliseconds, decayLengthPercent))
			throw new IllegalArgumentException("Invalid live preview settings");
		liveParameters = new LiveParameters(wet, dry, preDelayMilliseconds, lowCutHertz, highCutHertz, earlyLevel,
				lateLevel, attackMilliseconds, decayLengthPercent, normalizeIr, peakProtection, headroomDecibels);
	}

	void update(double wet, double dry, double preDelayMilliseconds, double lowCutHertz, double highCutHertz,
			boolean normalizeIr, boolean peakProtection, double headroomDecibels) {
		update(wet, dry, preDelayMilliseconds, lowCutHertz, highCutHertz, 1, 1, 0, 100, normalizeIr, peakProtection,
				headroomDecibels);
	}

	void togglePause(Consumer<State> state) {
		if (stopped.get())
			return;
		boolean pausing = !paused;
		AudioSink sink = activeSink;
		if (pausing) {
			paused = true;
			if (sink != null)
				sink.pause();
			state.accept(State.PAUSED);
		} else {
			if (sink != null)
				sink.resume();
			synchronized (pauseLock) {
				paused = false;
				pauseLock.notifyAll();
			}
			state.accept(State.PLAYING);
		}
	}

	void stop() {
		stopped.set(true);
		irLoadSequence.incrementAndGet();
		pendingBank = null;
		synchronized (pauseLock) {
			paused = false;
			pauseLock.notifyAll();
		}
		AudioSink sink = activeSink;
		if (sink != null)
			sink.stop();
		Thread thread = playbackThread;
		if (thread != null)
			thread.interrupt();
	}

	boolean isActive() {
		return !stopped.get();
	}

	void setBypassed(boolean value) {
		bypassed = value;
	}

	void changeImpulseResponse(Path path, Runnable resampling, Consumer<Path> success, Consumer<String> failure) {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(resampling, "resampling");
		Objects.requireNonNull(success, "success");
		Objects.requireNonNull(failure, "failure");
		int sampleRate = previewSampleRate;
		long request = irLoadSequence.incrementAndGet();
		if (!isActive() || sampleRate < 1) {
			failure.accept("Start the preview before changing its impulse response.");
			return;
		}
		Thread.ofVirtual().name("mechana-reverb-ir-loader").start(() -> {
			try {
				ConvolutionBank bank = prepareBank(path, sampleRate, resampling);
				if (!isActive() || previewSampleRate != sampleRate || irLoadSequence.get() != request)
					return;
				pendingBank = bank;
				success.accept(path);
			} catch (IOException | RuntimeException loadFailure) {
				failure.accept(rootMessage(loadFailure));
			}
		});
	}

	void changeImpulseResponseNow(Path path) throws IOException {
		int sampleRate = previewSampleRate;
		if (!isActive() || sampleRate < 1)
			throw new IOException("Start the preview before changing its impulse response.");
		pendingBank = prepareBank(path, sampleRate, () -> {
		});
	}

	private void stream(Settings settings, AudioSinkFactory sinkFactory, Consumer<State> state) throws IOException {
		Path temporaryDirectory = Files.createTempDirectory("mechana-reverb-preview-");
		Path converted = temporaryDirectory.resolve("dry.wav");
		try {
			Path prepared = DryAudioImporter.prepareNative(settings.dryPath(), converted);
			try (WavFile.Reader source = WavFile.open(prepared)) {
				WavFile.Format format = source.format();
				ImpulseResponse ir = shape(
						ImpulseResponse.read(impulseResponseCache.prepare(settings.irPath(), format.sampleRate(),
								() -> state.accept(State.REGENERATING_IR)), false),
						settings.earlyLevel(), settings.lateLevel(), settings.attackMilliseconds(),
						settings.decayLengthPercent());
				previewSampleRate = format.sampleRate();
				if (format.channels() > 2 || ir.channelCount() > 2)
					throw new IOException("Preview supports mono or stereo audio");
				int outputChannels = 2;
				long preDelay = Math.round(settings.preDelayMilliseconds() * format.sampleRate() / 1000.0);
				long outputFrames = Math.max(format.frames(), format.frames() + ir.length() - 1L + preDelay);
				try (AudioSink sink = sinkFactory.open(format.sampleRate(), outputChannels)) {
					activeSink = sink;
					sink.start();
					state.accept(State.PLAYING);
					process(source, format, new ConvolutionBank(ir, outputChannels), outputChannels, outputFrames,
							sink);
					if (!stopped.get())
						sink.drain();
				}
			}
		} finally {
			previewSampleRate = 0;
			pendingBank = null;
			Files.deleteIfExists(converted);
			Files.deleteIfExists(temporaryDirectory);
		}
	}

	private void process(WavFile.Reader source, WavFile.Format format, ConvolutionBank initialBank, int outputChannels,
			long outputFrames, AudioSink sink) throws IOException {
		VariableDelayLine[] delays = new VariableDelayLine[outputChannels];
		WetEqualizer[] equalizers = new WetEqualizer[outputChannels];
		for (int channel = 0; channel < outputChannels; channel++)
			delays[channel] = new VariableDelayLine(format.sampleRate(), liveParameters.preDelayMilliseconds());
		for (int channel = 0; channel < outputChannels; channel++)
			equalizers[channel] = new WetEqualizer(format.sampleRate(), liveParameters.lowCutHertz(),
					liveParameters.highCutHertz());
		SmoothedValue wetLevel = new SmoothedValue(liveParameters.wet());
		SmoothedValue dryLevel = new SmoothedValue(liveParameters.dry());
		SmoothedValue headroom = new SmoothedValue(headroomTarget(liveParameters));
		SmoothedValue bypass = new SmoothedValue(bypassed ? 1 : 0);
		int rampFrames = Math.max(1, format.sampleRate() * PARAMETER_RAMP_MILLISECONDS / 1000);
		double[][] input = new double[format.channels()][BLOCK_SIZE];
		byte[] pcm = new byte[BLOCK_SIZE * outputChannels * 2];
		long sourcePosition = 0;
		long outputPosition = 0;
		long requiredOutputFrames = outputFrames;
		int maximumIrLength = initialBank.length();
		ConvolutionBank activeBank = initialBank;
		ConvolutionBank transitionBank = null;
		int crossfadeFrame = 0;
		int crossfadeFrames = Math.max(1, format.sampleRate() * IR_CROSSFADE_MILLISECONDS / 1000);
		while (outputPosition < requiredOutputFrames && !stopped.get()) {
			awaitResume();
			if (stopped.get())
				break;
			for (double[] channel : input)
				Arrays.fill(channel, 0);
			int sourceFrames = sourcePosition < format.frames()
					? source.read(input, 0, (int) Math.min(BLOCK_SIZE, format.frames() - sourcePosition))
					: 0;
			if (transitionBank == null && pendingBank != null) {
				transitionBank = pendingBank;
				pendingBank = null;
				maximumIrLength = Math.max(maximumIrLength, transitionBank.length());
				crossfadeFrame = 0;
			}
			double[][] wet = activeBank.process(input, sourceFrames);
			double[][] transitionWet = transitionBank == null ? null : transitionBank.process(input, sourceFrames);
			LiveParameters blockParameters = liveParameters;
			long requestedDelay = Math.round(blockParameters.preDelayMilliseconds() * format.sampleRate() / 1000.0);
			requiredOutputFrames = Math.max(requiredOutputFrames,
					format.frames() + maximumIrLength - 1L + requestedDelay);
			int frames = (int) Math.min(BLOCK_SIZE, requiredOutputFrames - outputPosition);
			int byteIndex = 0;
			for (int frame = 0; frame < frames; frame++) {
				LiveParameters parameters = liveParameters;
				wetLevel.target(parameters.wet(), rampFrames);
				dryLevel.target(parameters.dry(), rampFrames);
				headroom.target(headroomTarget(parameters), rampFrames);
				bypass.target(bypassed ? 1 : 0, rampFrames);
				double currentWet = wetLevel.next();
				double currentDry = dryLevel.next();
				double currentHeadroom = headroom.next();
				double currentBypass = bypass.next();
				double blend = transitionBank == null ? 0 : Math.min(1, (double) crossfadeFrame / crossfadeFrames);
				for (int channel = 0; channel < outputChannels; channel++) {
					long absolute = outputPosition + frame;
					double original = absolute < format.frames()
							? input[Math.min(channel, input.length - 1)][frame]
							: 0;
					double direct = original * dryEndEnvelope(absolute, format.frames(), format.sampleRate());
					double wetSample = wet[channel][frame]
							* (parameters.normalizeIr() ? activeBank.normalizationGain() : 1);
					if (transitionBank != null)
						wetSample = wetSample * (1 - blend) + transitionWet[channel][frame] * blend
								* (parameters.normalizeIr() ? transitionBank.normalizationGain() : 1);
					double delayed = delays[channel].push(wetSample, parameters.preDelayMilliseconds());
					equalizers[channel].update(parameters.lowCutHertz(), parameters.highCutHertz());
					delayed = equalizers[channel].process(delayed);
					double sample = direct * currentDry + delayed * currentWet;
					if (parameters.peakProtection())
						sample = Math.max(-currentHeadroom, Math.min(currentHeadroom, sample));
					sample = sample * (1 - currentBypass) + original * currentBypass;
					int value = (int) Math.max(Short.MIN_VALUE,
							Math.min(Short.MAX_VALUE, Math.round(sample * 32768.0)));
					pcm[byteIndex++] = (byte) value;
					pcm[byteIndex++] = (byte) (value >>> 8);
				}
				if (transitionBank != null && crossfadeFrame < crossfadeFrames)
					crossfadeFrame++;
			}
			if (transitionBank != null && crossfadeFrame >= crossfadeFrames) {
				activeBank = transitionBank;
				transitionBank = null;
			}
			sink.write(pcm, byteIndex);
			sourcePosition += sourceFrames;
			outputPosition += frames;
		}
	}

	private static double normalizationGain(ImpulseResponse ir) {
		double peak = 0;
		for (double[] channel : ir.channels())
			for (double sample : channel)
				peak = Math.max(peak, Math.abs(sample));
		return peak > NORMALIZED_IR_PEAK ? NORMALIZED_IR_PEAK / peak : 1;
	}

	private ConvolutionBank prepareBank(Path path, int sampleRate, Runnable resampling) throws IOException {
		LiveParameters parameters = liveParameters;
		ImpulseResponse ir = shape(
				ImpulseResponse.read(impulseResponseCache.prepare(path, sampleRate, resampling), false),
				parameters.earlyLevel(), parameters.lateLevel(), parameters.attackMilliseconds(),
				parameters.decayLengthPercent());
		if (ir.channelCount() > 2)
			throw new IOException("Preview supports mono or stereo impulse responses");
		return new ConvolutionBank(ir, 2);
	}

	private static final class ConvolutionBank {
		private final PartitionedConvolver[] convolvers;
		private final double normalizationGain;
		private final int length;

		private ConvolutionBank(ImpulseResponse ir, int outputChannels) {
			convolvers = new PartitionedConvolver[outputChannels];
			for (int channel = 0; channel < outputChannels; channel++)
				convolvers[channel] = new PartitionedConvolver(ir.channel(Math.min(channel, ir.channelCount() - 1)),
						BLOCK_SIZE);
			normalizationGain = ReverbPreviewPlayer.normalizationGain(ir);
			length = ir.length();
		}

		private double[][] process(double[][] input, int frames) {
			double[][] output = new double[convolvers.length][];
			for (int channel = 0; channel < convolvers.length; channel++)
				output[channel] = convolvers[channel].process(input[Math.min(channel, input.length - 1)], frames);
			return output;
		}

		private double normalizationGain() {
			return normalizationGain;
		}

		private int length() {
			return length;
		}
	}

	private static double headroomTarget(LiveParameters parameters) {
		return Math.pow(10, -parameters.headroomDecibels() / 20);
	}

	private static boolean validFrequency(double value) {
		return Double.isFinite(value) && value >= 0 && value <= 20_000;
	}

	private static boolean validShaping(double early, double late, double attack, double decay) {
		try {
			new ImpulseResponseShaper.Options(early, late, attack, decay);
			return true;
		} catch (IllegalArgumentException invalid) {
			return false;
		}
	}

	private static ImpulseResponse shape(ImpulseResponse ir, double early, double late, double attack, double decay) {
		return ImpulseResponseShaper.shape(ir, new ImpulseResponseShaper.Options(early, late, attack, decay));
	}

	private void awaitResume() throws IOException {
		synchronized (pauseLock) {
			while (paused && !stopped.get())
				try {
					pauseLock.wait();
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IOException("Preview interrupted", interrupted);
				}
		}
	}

	private static double dryEndEnvelope(long frame, long totalFrames, int sampleRate) {
		long fadeFrames = Math.round(DRY_END_FADE_MILLISECONDS * sampleRate / 1000.0);
		if (fadeFrames < 2 || totalFrames <= fadeFrames || frame < totalFrames - fadeFrames)
			return 1;
		return (double) (totalFrames - frame - 1) / (fadeFrames - 1);
	}

	private static AudioSinkFactory systemSink() {
		return (sampleRate, channels) -> {
			AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
			try {
				SourceDataLine line = (SourceDataLine) AudioSystem
						.getLine(new DataLine.Info(SourceDataLine.class, format));
				line.open(format, BLOCK_SIZE * channels * 2 * 4);
				return new SystemAudioSink(line);
			} catch (LineUnavailableException unavailable) {
				throw new IOException("The selected Mac audio output is unavailable", unavailable);
			}
		};
	}

	static byte[] renderForTest(Settings settings) throws IOException {
		return renderForTest(settings, ignored -> {
		});
	}

	static byte[] renderForTest(Settings settings, Consumer<ReverbPreviewPlayer> afterFirstBlock) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		Path parent = Objects.requireNonNull(settings.dryPath().toAbsolutePath().getParent(), "dry audio parent");
		Path cache = parent.resolve("preview-ir-cache");
		ReverbPreviewPlayer player = new ReverbPreviewPlayer(new ImpulseResponseCache(cache));
		player.stopped.set(false);
		player.update(settings.wet(), settings.dry(), settings.preDelayMilliseconds(), settings.lowCutHertz(),
				settings.highCutHertz(), settings.earlyLevel(), settings.lateLevel(), settings.attackMilliseconds(),
				settings.decayLengthPercent(), settings.normalizeIr(), settings.peakProtection(),
				settings.headroomDecibels());
		player.stream(settings,
				(sampleRate, channels) -> new MemoryAudioSink(output, () -> afterFirstBlock.accept(player)),
				ignored -> {
				});
		return output.toByteArray();
	}

	private static String rootMessage(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null)
			root = root.getCause();
		return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
	}

	@Override
	public void close() {
		stop();
	}

	private static final class VariableDelayLine {
		private final double[] samples;
		private final double sampleRate;
		private double delayFrames;
		private double delayTarget;
		private double delayStep;
		private int remaining;
		private int position;

		private VariableDelayLine(int sampleRate, double initialMilliseconds) {
			this.sampleRate = sampleRate;
			samples = new double[sampleRate * MAX_PRE_DELAY_MILLISECONDS / 1000 + 2];
			delayFrames = millisecondsToFrames(initialMilliseconds);
			delayTarget = delayFrames;
		}

		private double push(double sample, double delayMilliseconds) {
			samples[position] = sample;
			double target = millisecondsToFrames(delayMilliseconds);
			if (Double.compare(target, delayTarget) != 0) {
				delayTarget = target;
				remaining = Math.max(1, (int) (sampleRate * PARAMETER_RAMP_MILLISECONDS / 1000));
				delayStep = (delayTarget - delayFrames) / remaining;
			}
			if (remaining > 0) {
				delayFrames += delayStep;
				if (--remaining == 0)
					delayFrames = delayTarget;
			}
			double read = position - delayFrames;
			while (read < 0)
				read += samples.length;
			int first = (int) Math.floor(read) % samples.length;
			int second = (first + 1) % samples.length;
			double fraction = read - Math.floor(read);
			double result = samples[first] * (1 - fraction) + samples[second] * fraction;
			position = (position + 1) % samples.length;
			return result;
		}

		private double millisecondsToFrames(double milliseconds) {
			return milliseconds * sampleRate / 1000.0;
		}
	}

	private static final class SmoothedValue {
		private double current;
		private double target;
		private double step;
		private int remaining;

		private SmoothedValue(double initial) {
			current = initial;
			target = initial;
		}

		private void target(double value, int frames) {
			if (Double.compare(value, target) == 0)
				return;
			target = value;
			remaining = frames;
			step = (target - current) / frames;
		}

		private double next() {
			if (remaining > 0) {
				current += step;
				if (--remaining == 0)
					current = target;
			}
			return current;
		}
	}

	private record SystemAudioSink(SourceDataLine line) implements AudioSink {
		@Override
		public void start() {
			line.start();
		}

		@Override
		public void write(byte[] samples, int length) {
			line.write(samples, 0, length);
		}

		@Override
		public void drain() {
			line.drain();
		}

		@Override
		public void pause() {
			line.stop();
		}

		@Override
		public void resume() {
			line.start();
		}

		@Override
		public void stop() {
			line.stop();
			line.flush();
		}

		@Override
		public void close() {
			line.close();
		}
	}

	private static final class MemoryAudioSink implements AudioSink {
		private final ByteArrayOutputStream output;
		private final Runnable afterFirstWrite;
		private boolean written;

		private MemoryAudioSink(ByteArrayOutputStream output, Runnable afterFirstWrite) {
			this.output = output;
			this.afterFirstWrite = afterFirstWrite;
		}

		@Override
		public void start() {
		}

		@Override
		public void write(byte[] samples, int length) {
			output.write(samples, 0, length);
			if (!written) {
				written = true;
				afterFirstWrite.run();
			}
		}

		@Override
		public void drain() {
		}

		@Override
		public void pause() {
		}

		@Override
		public void resume() {
		}

		@Override
		public void stop() {
		}

		@Override
		public void close() {
		}
	}
}
