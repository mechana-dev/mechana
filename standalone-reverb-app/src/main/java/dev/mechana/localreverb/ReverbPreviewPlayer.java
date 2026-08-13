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
import dev.mechana.plugins.audio.PartitionedConvolver;
import dev.mechana.plugins.audio.WavFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
	private static final int MAX_PRE_DELAY_MILLISECONDS = 10_000;
	private static final double NORMALIZED_IR_PEAK = Math.pow(10, -1.0 / 20);
	private final AtomicBoolean stopped = new AtomicBoolean(true);
	private final Object pauseLock = new Object();
	private volatile boolean paused;
	private volatile Thread playbackThread;
	private volatile AudioSink activeSink;
	private volatile LiveParameters liveParameters = new LiveParameters(0, 0, 0, false, true, 1);

	enum State {
		PREPARING, PLAYING, PAUSED, STOPPED, FINISHED
	}

	record Settings(Path dryPath, Path irPath, double wet, double dry, double preDelayMilliseconds, boolean normalizeIr,
			boolean peakProtection, double headroomDecibels) {
		Settings {
			Objects.requireNonNull(dryPath, "dryPath");
			Objects.requireNonNull(irPath, "irPath");
			if (!Double.isFinite(wet) || wet < 0 || !Double.isFinite(dry) || dry < 0
					|| !Double.isFinite(preDelayMilliseconds) || preDelayMilliseconds < 0
					|| !Double.isFinite(headroomDecibels) || headroomDecibels < 0)
				throw new IllegalArgumentException("Invalid preview settings");
		}
	}

	private record LiveParameters(double wet, double dry, double preDelayMilliseconds, boolean normalizeIr,
			boolean peakProtection, double headroomDecibels) {
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
		update(settings.wet(), settings.dry(), settings.preDelayMilliseconds(), settings.normalizeIr(),
				settings.peakProtection(), settings.headroomDecibels());
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

	void update(double wet, double dry, double preDelayMilliseconds, boolean normalizeIr, boolean peakProtection,
			double headroomDecibels) {
		if (!Double.isFinite(wet) || wet < 0 || wet > 2 || !Double.isFinite(dry) || dry < 0 || dry > 2
				|| !Double.isFinite(preDelayMilliseconds) || preDelayMilliseconds < 0
				|| preDelayMilliseconds > MAX_PRE_DELAY_MILLISECONDS || !Double.isFinite(headroomDecibels)
				|| headroomDecibels < 0)
			throw new IllegalArgumentException("Invalid live preview settings");
		liveParameters = new LiveParameters(wet, dry, preDelayMilliseconds, normalizeIr, peakProtection,
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

	private void stream(Settings settings, AudioSinkFactory sinkFactory, Consumer<State> state) throws IOException {
		ImpulseResponse ir = ImpulseResponse.read(settings.irPath(), false);
		Path temporaryDirectory = Files.createTempDirectory("mechana-reverb-preview-");
		Path converted = temporaryDirectory.resolve("dry.wav");
		try {
			Path prepared = DryAudioImporter.prepare(settings.dryPath(), ir.sampleRate(), converted);
			try (WavFile.Reader source = WavFile.open(prepared)) {
				WavFile.Format format = source.format();
				if (format.channels() > 2 || ir.channelCount() > 2)
					throw new IOException("Preview supports mono or stereo audio");
				int outputChannels = Math.max(format.channels(), ir.channelCount());
				long preDelay = Math.round(settings.preDelayMilliseconds() * format.sampleRate() / 1000.0);
				long outputFrames = Math.max(format.frames(), format.frames() + ir.length() - 1L + preDelay);
				try (AudioSink sink = sinkFactory.open(format.sampleRate(), outputChannels)) {
					activeSink = sink;
					sink.start();
					state.accept(State.PLAYING);
					process(source, format, ir, outputChannels, outputFrames, sink);
					if (!stopped.get())
						sink.drain();
				}
			}
		} finally {
			Files.deleteIfExists(converted);
			Files.deleteIfExists(temporaryDirectory);
		}
	}

	private void process(WavFile.Reader source, WavFile.Format format, ImpulseResponse ir, int outputChannels,
			long outputFrames, AudioSink sink) throws IOException {
		PartitionedConvolver[] convolvers = new PartitionedConvolver[outputChannels];
		VariableDelayLine[] delays = new VariableDelayLine[outputChannels];
		for (int channel = 0; channel < outputChannels; channel++) {
			convolvers[channel] = new PartitionedConvolver(ir.channel(Math.min(channel, ir.channelCount() - 1)),
					BLOCK_SIZE);
			delays[channel] = new VariableDelayLine(format.sampleRate(), liveParameters.preDelayMilliseconds());
		}
		double normalizationGain = normalizationGain(ir);
		SmoothedValue wetLevel = new SmoothedValue(effectiveWet(liveParameters, normalizationGain));
		SmoothedValue dryLevel = new SmoothedValue(liveParameters.dry());
		SmoothedValue headroom = new SmoothedValue(headroomTarget(liveParameters));
		int rampFrames = Math.max(1, format.sampleRate() * PARAMETER_RAMP_MILLISECONDS / 1000);
		double[][] input = new double[format.channels()][BLOCK_SIZE];
		byte[] pcm = new byte[BLOCK_SIZE * outputChannels * 2];
		long sourcePosition = 0;
		long outputPosition = 0;
		long requiredOutputFrames = outputFrames;
		while (outputPosition < requiredOutputFrames && !stopped.get()) {
			awaitResume();
			if (stopped.get())
				break;
			for (double[] channel : input)
				Arrays.fill(channel, 0);
			int sourceFrames = sourcePosition < format.frames()
					? source.read(input, 0, (int) Math.min(BLOCK_SIZE, format.frames() - sourcePosition))
					: 0;
			double[][] wet = new double[outputChannels][];
			for (int channel = 0; channel < outputChannels; channel++)
				wet[channel] = convolvers[channel].process(input[Math.min(channel, input.length - 1)], sourceFrames);
			LiveParameters blockParameters = liveParameters;
			long requestedDelay = Math.round(blockParameters.preDelayMilliseconds() * format.sampleRate() / 1000.0);
			requiredOutputFrames = Math.max(requiredOutputFrames, format.frames() + ir.length() - 1L + requestedDelay);
			int frames = (int) Math.min(BLOCK_SIZE, requiredOutputFrames - outputPosition);
			int byteIndex = 0;
			for (int frame = 0; frame < frames; frame++) {
				LiveParameters parameters = liveParameters;
				wetLevel.target(effectiveWet(parameters, normalizationGain), rampFrames);
				dryLevel.target(parameters.dry(), rampFrames);
				headroom.target(headroomTarget(parameters), rampFrames);
				double currentWet = wetLevel.next();
				double currentDry = dryLevel.next();
				double currentHeadroom = headroom.next();
				for (int channel = 0; channel < outputChannels; channel++) {
					long absolute = outputPosition + frame;
					double direct = absolute < format.frames()
							? input[Math.min(channel, input.length - 1)][frame]
									* dryEndEnvelope(absolute, format.frames(), format.sampleRate())
							: 0;
					double delayed = delays[channel].push(wet[channel][frame], parameters.preDelayMilliseconds());
					double sample = direct * currentDry + delayed * currentWet;
					if (parameters.peakProtection())
						sample = Math.max(-currentHeadroom, Math.min(currentHeadroom, sample));
					int value = (int) Math.max(Short.MIN_VALUE,
							Math.min(Short.MAX_VALUE, Math.round(sample * 32768.0)));
					pcm[byteIndex++] = (byte) value;
					pcm[byteIndex++] = (byte) (value >>> 8);
				}
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

	private static double effectiveWet(LiveParameters parameters, double normalizationGain) {
		return parameters.wet() * (parameters.normalizeIr() ? normalizationGain : 1);
	}

	private static double headroomTarget(LiveParameters parameters) {
		return Math.pow(10, -parameters.headroomDecibels() / 20);
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
		ReverbPreviewPlayer player = new ReverbPreviewPlayer();
		player.stopped.set(false);
		player.update(settings.wet(), settings.dry(), settings.preDelayMilliseconds(), settings.normalizeIr(),
				settings.peakProtection(), settings.headroomDecibels());
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
