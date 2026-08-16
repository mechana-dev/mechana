/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.mechana.localreverb;

import dev.mechana.plugins.audio.WavFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class WavPreviewPlayer implements AutoCloseable {
	private static final int BLOCK = 1_024;
	private final AtomicBoolean stopped = new AtomicBoolean(true);
	private final Object pauseLock = new Object();
	private volatile boolean paused;
	private volatile boolean looping;
	private volatile ReverbPreviewPlayer.AudioSink sink;
	private volatile ReverbPreviewPlayer.AudioSinkFactory sinkFactory = ReverbPreviewPlayer.javaSoundSink();
	private volatile Consumer<ReverbPreviewPlayer.Position> positionListener = ignored -> {
	};

	void play(Path file, double startFraction, Consumer<ReverbPreviewPlayer.State> state, Consumer<String> failure) {
		stop();
		stopped.set(false);
		paused = false;
		Thread.ofVirtual().name("mechana-echo-preview").start(() -> {
			state.accept(ReverbPreviewPlayer.State.PREPARING);
			try {
				double position = startFraction;
				do {
					stream(file, position, state);
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

	private void stream(Path file, double startFraction, Consumer<ReverbPreviewPlayer.State> state) throws IOException {
		try (WavFile.Reader reader = WavFile.open(file);
				ReverbPreviewPlayer.AudioSink output = sinkFactory.open(reader.format().sampleRate(),
						reader.format().channels())) {
			sink = output;
			long start = Math.min(reader.format().frames(), Math.round(reader.format().frames() * startFraction));
			reader.seekFrame(start);
			output.start();
			state.accept(ReverbPreviewPlayer.State.PLAYING);
			double[][] samples = new double[reader.format().channels()][BLOCK];
			byte[] pcm = new byte[BLOCK * reader.format().channels() * 2];
			long position = start;
			for (int count; !stopped.get() && (count = reader.read(samples, 0, BLOCK)) > 0;) {
				waitWhilePaused();
				int byteIndex = 0;
				for (int frame = 0; frame < count; frame++)
					for (int channel = 0; channel < samples.length; channel++) {
						int value = (int) Math.round(Math.max(-1, Math.min(0.999969, samples[channel][frame])) * 32768);
						pcm[byteIndex++] = (byte) value;
						pcm[byteIndex++] = (byte) (value >>> 8);
					}
				output.write(pcm, byteIndex);
				position += count;
				positionListener.accept(new ReverbPreviewPlayer.Position(position, reader.format().frames(),
						reader.format().sampleRate()));
			}
			if (!stopped.get())
				output.drain();
		}
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
