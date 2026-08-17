/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.mechana.localreverb;

import dev.mechana.plugins.audio.DryAudioImporter;
import dev.mechana.plugins.audio.WavFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Streams source audio through the modeled Echo processor without rendering a
 * preview file.
 */
final class WavPreviewPlayer implements AutoCloseable {
	private static final int BLOCK = 1_024;
	private final AtomicBoolean stopped = new AtomicBoolean(true);
	private final Object pauseLock = new Object();
	private volatile boolean paused;
	private volatile boolean looping;
	private volatile boolean bypassed;
	private volatile EchoSettings liveSettings = EchoSettings.defaults(EchoSettings.Model.TAPE);
	private volatile ReverbPreviewPlayer.AudioSink sink;
	private volatile ReverbPreviewPlayer.AudioSinkFactory sinkFactory = ReverbPreviewPlayer.javaSoundSink();
	private volatile Consumer<ReverbPreviewPlayer.Position> positionListener = ignored -> {
	};

	void play(Path source, EchoSettings settings, double startFraction, Consumer<ReverbPreviewPlayer.State> state,
			Consumer<String> failure) {
		stop();
		liveSettings = Objects.requireNonNull(settings, "settings");
		stopped.set(false);
		paused = false;
		Thread.ofVirtual().name("mechana-echo-preview").start(() -> {
			state.accept(ReverbPreviewPlayer.State.PREPARING);
			try {
				double position = startFraction;
				do {
					stream(source, position, state);
					position = 0;
				} while (looping && !stopped.get());
				if (!stopped.get())
					state.accept(ReverbPreviewPlayer.State.FINISHED);
			} catch (IOException | RuntimeException problem) {
				if (!stopped.get())
					failure.accept(problem.getMessage());
			} finally {
				stopped.set(true);
				sink = null;
			}
		});
	}

	private void stream(Path sourcePath, double startFraction, Consumer<ReverbPreviewPlayer.State> state)
			throws IOException {
		Path temporaryDirectory = Files.createTempDirectory("mechana-echo-preview-");
		Path converted = temporaryDirectory.resolve("dry.wav");
		try {
			Path prepared = DryAudioImporter.prepareNative(sourcePath, converted);
			try (WavFile.Reader reader = WavFile.open(prepared)) {
				WavFile.Format format = reader.format();
				if (format.channels() < 1 || format.channels() > 2)
					throw new IOException("Echo Preview supports mono or stereo audio");
				long tail = EchoFileRenderer.tailFrames(format.sampleRate(), liveSettings);
				long totalFrames = Math.addExact(format.frames(), tail);
				long audibleStart = Math.min(format.frames(), Math.round(format.frames() * startFraction));
				long position = Math.max(0, audibleStart - tail);
				reader.seekFrame(position);
				EchoProcessor processor = new EchoProcessor(format.sampleRate(), format.channels());
				try (ReverbPreviewPlayer.AudioSink output = sinkFactory.open(format.sampleRate(), format.channels())) {
					sink = output;
					output.start();
					state.accept(ReverbPreviewPlayer.State.PLAYING);
					process(reader, processor, output, format, position, audibleStart, totalFrames);
					if (!stopped.get())
						output.drain();
				}
			}
		} finally {
			Files.deleteIfExists(converted);
			Files.deleteIfExists(temporaryDirectory);
		}
	}

	private void process(WavFile.Reader reader, EchoProcessor processor, ReverbPreviewPlayer.AudioSink output,
			WavFile.Format format, long position, long audibleStart, long totalFrames) throws IOException {
		double[][] samples = new double[format.channels()][BLOCK];
		byte[] pcm = new byte[BLOCK * format.channels() * 2];
		while (!stopped.get() && position < totalFrames) {
			waitWhilePaused();
			int count = (int) Math.min(BLOCK, totalFrames - position);
			for (double[] channel : samples)
				java.util.Arrays.fill(channel, 0, count, 0);
			if (position < format.frames())
				reader.read(samples, 0, (int) Math.min(count, format.frames() - position));
			EchoSettings current = bypassed ? bypassed(liveSettings) : liveSettings;
			processor.process(samples, count, current);
			long blockEnd = position + count;
			if (blockEnd > audibleStart) {
				int firstAudible = (int) Math.max(0, audibleStart - position);
				output.write(pcm, encode(samples, firstAudible, count, pcm));
				positionListener.accept(new ReverbPreviewPlayer.Position(blockEnd, totalFrames, format.sampleRate()));
			}
			position = blockEnd;
		}
	}

	private static int encode(double[][] samples, int firstFrame, int frames, byte[] pcm) {
		int byteIndex = 0;
		for (int frame = firstFrame; frame < frames; frame++)
			for (double[] channel : samples) {
				int value = (int) Math.round(Math.max(-1, Math.min(0.999969, channel[frame])) * 32768);
				pcm[byteIndex++] = (byte) value;
				pcm[byteIndex++] = (byte) (value >>> 8);
			}
		return byteIndex;
	}

	void update(EchoSettings settings) {
		liveSettings = Objects.requireNonNull(settings, "settings");
	}

	void setBypassed(boolean value) {
		bypassed = value;
	}

	private static EchoSettings bypassed(EchoSettings value) {
		return new EchoSettings(value.model(), value.delayMilliseconds(), value.feedback(), 0, 1, value.lowCutHertz(),
				value.highCutHertz(), value.saturation(), value.modulationRateHertz(),
				value.modulationDepthMilliseconds(), value.pingPong());
	}

	void togglePause(Consumer<ReverbPreviewPlayer.State> state) {
		if (stopped.get())
			return;
		boolean nowPaused;
		synchronized (pauseLock) {
			paused = !paused;
			nowPaused = paused;
			if (!nowPaused)
				pauseLock.notifyAll();
		}
		ReverbPreviewPlayer.AudioSink active = sink;
		if (nowPaused) {
			if (active != null)
				active.pause();
			state.accept(ReverbPreviewPlayer.State.PAUSED);
		} else {
			if (active != null)
				active.resume();
			state.accept(ReverbPreviewPlayer.State.PLAYING);
		}
	}

	private void waitWhilePaused() {
		synchronized (pauseLock) {
			while (paused && !stopped.get())
				try {
					pauseLock.wait();
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
		}
	}

	void stop() {
		synchronized (pauseLock) {
			stopped.set(true);
			paused = false;
			pauseLock.notifyAll();
		}
		ReverbPreviewPlayer.AudioSink active = sink;
		if (active != null)
			active.stop();
	}

	boolean isActive() {
		return !stopped.get();
	}

	void setLooping(boolean value) {
		looping = value;
	}

	void setAudioSinkFactory(ReverbPreviewPlayer.AudioSinkFactory value) {
		sinkFactory = Objects.requireNonNull(value, "value");
	}

	void onPosition(Consumer<ReverbPreviewPlayer.Position> listener) {
		positionListener = Objects.requireNonNull(listener, "listener");
	}

	@Override
	public void close() {
		stop();
	}
}
