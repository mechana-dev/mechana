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
	private final AtomicBoolean stopped = new AtomicBoolean(true);
	private final Object pauseLock = new Object();
	private volatile boolean paused;
	private volatile Thread playbackThread;
	private volatile AudioSink activeSink;

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
		ImpulseResponse ir = ImpulseResponse.read(settings.irPath(), settings.normalizeIr());
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
					process(source, format, ir, outputChannels, outputFrames, preDelay, settings, sink);
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
			long outputFrames, long preDelay, Settings settings, AudioSink sink) throws IOException {
		PartitionedConvolver[] convolvers = new PartitionedConvolver[outputChannels];
		DelayLine[] delays = new DelayLine[outputChannels];
		for (int channel = 0; channel < outputChannels; channel++) {
			convolvers[channel] = new PartitionedConvolver(ir.channel(Math.min(channel, ir.channelCount() - 1)),
					BLOCK_SIZE);
			delays[channel] = new DelayLine(preDelay);
		}
		double[][] input = new double[format.channels()][BLOCK_SIZE];
		byte[] pcm = new byte[BLOCK_SIZE * outputChannels * 2];
		long sourcePosition = 0;
		long outputPosition = 0;
		double target = Math.pow(10, -settings.headroomDecibels() / 20);
		while (outputPosition < outputFrames && !stopped.get()) {
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
			int frames = (int) Math.min(BLOCK_SIZE, outputFrames - outputPosition);
			int byteIndex = 0;
			for (int frame = 0; frame < frames; frame++)
				for (int channel = 0; channel < outputChannels; channel++) {
					long absolute = outputPosition + frame;
					double direct = absolute < format.frames()
							? input[Math.min(channel, input.length - 1)][frame]
									* dryEndEnvelope(absolute, format.frames(), format.sampleRate())
							: 0;
					double sample = direct * settings.dry()
							+ delays[channel].push(wet[channel][frame]) * settings.wet();
					if (settings.peakProtection())
						sample = Math.max(-target, Math.min(target, sample));
					int value = (int) Math.max(Short.MIN_VALUE,
							Math.min(Short.MAX_VALUE, Math.round(sample * 32768.0)));
					pcm[byteIndex++] = (byte) value;
					pcm[byteIndex++] = (byte) (value >>> 8);
				}
			sink.write(pcm, byteIndex);
			sourcePosition += sourceFrames;
			outputPosition += frames;
		}
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
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ReverbPreviewPlayer player = new ReverbPreviewPlayer();
		player.stopped.set(false);
		player.stream(settings, (sampleRate, channels) -> new MemoryAudioSink(output), ignored -> {
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

	private record DelayLine(double[] samples, int[] position) {
		private DelayLine(long frames) throws IOException {
			this(array(frames), new int[1]);
		}

		private double push(double sample) {
			if (samples.length == 0)
				return sample;
			double delayed = samples[position[0]];
			samples[position[0]] = sample;
			position[0] = (position[0] + 1) % samples.length;
			return delayed;
		}

		private static double[] array(long frames) throws IOException {
			if (frames > Integer.MAX_VALUE)
				throw new IOException("Preview pre-delay is too long");
			return new double[(int) frames];
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

	private record MemoryAudioSink(ByteArrayOutputStream output) implements AudioSink {
		@Override
		public void start() {
		}

		@Override
		public void write(byte[] samples, int length) {
			output.write(samples, 0, length);
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
