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
import dev.mechana.plugins.audio.WavFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class LesliePreviewPlayer implements AutoCloseable {
	private static final int BLOCK = 1_024;
	private final AtomicBoolean stopped = new AtomicBoolean(true);
	private final AtomicLong generation = new AtomicLong();
	private final Object pauseLock = new Object();
	private volatile boolean paused;
	private volatile boolean looping;
	private volatile boolean bypassed;
	private volatile LeslieSettings liveSettings = LeslieSettings.defaults();
	private volatile ReverbPreviewPlayer.AudioSink sink;
	private volatile ReverbPreviewPlayer.AudioSinkFactory sinkFactory = ReverbPreviewPlayer.javaSoundSink();
	private volatile Consumer<ReverbPreviewPlayer.Position> positionListener = ignored -> {
	};

	void play(Path source, LeslieSettings settings, double startFraction, Consumer<ReverbPreviewPlayer.State> state,
			Consumer<String> failure) {
		stop();
		long session = generation.incrementAndGet();
		liveSettings = Objects.requireNonNull(settings, "settings");
		stopped.set(false);
		paused = false;
		Thread.ofVirtual().name("mechana-leslie-preview").start(() -> {
			state.accept(ReverbPreviewPlayer.State.PREPARING);
			try {
				double position = startFraction;
				do {
					stream(source, position, state, session);
					position = 0;
				} while (looping && active(session));
				if (active(session))
					state.accept(ReverbPreviewPlayer.State.FINISHED);
			} catch (IOException | RuntimeException problem) {
				if (active(session))
					failure.accept(problem.getMessage());
			} finally {
				if (generation.get() == session) {
					stopped.set(true);
					sink = null;
				}
			}
		});
	}

	private void stream(Path sourcePath, double startFraction, Consumer<ReverbPreviewPlayer.State> state, long session)
			throws IOException {
		Path temporaryDirectory = Files.createTempDirectory("mechana-leslie-preview-");
		Path converted = temporaryDirectory.resolve("dry.wav");
		try {
			Path prepared = DryAudioImporter.prepareNative(sourcePath, converted);
			try (WavFile.Reader reader = WavFile.open(prepared)) {
				WavFile.Format format = reader.format();
				if (format.channels() < 1 || format.channels() > 2)
					throw new IOException("Leslie Preview supports mono or stereo audio");
				long position = Math.min(format.frames(), Math.round(format.frames() * startFraction));
				reader.seekFrame(position);
				LeslieProcessor processor = new LeslieProcessor(format.sampleRate(), format.channels());
				try (ReverbPreviewPlayer.AudioSink output = sinkFactory.open(format.sampleRate(), format.channels())) {
					sink = output;
					output.start();
					state.accept(ReverbPreviewPlayer.State.PLAYING);
					process(reader, processor, output, format, position, session);
					if (active(session))
						output.drain();
				}
			}
		} finally {
			Files.deleteIfExists(converted);
			Files.deleteIfExists(temporaryDirectory);
		}
	}

	private void process(WavFile.Reader reader, LeslieProcessor processor, ReverbPreviewPlayer.AudioSink output,
			WavFile.Format format, long position, long session) throws IOException {
		double[][] samples = new double[format.channels()][BLOCK];
		byte[] pcm = new byte[BLOCK * format.channels() * 2];
		while (active(session) && position < format.frames()) {
			waitWhilePaused();
			int count = (int) Math.min(BLOCK, format.frames() - position);
			reader.read(samples, 0, count);
			LeslieSettings current = bypassed ? bypassed(liveSettings) : liveSettings;
			processor.process(samples, count, current);
			output.write(pcm, encode(samples, count, pcm));
			position += count;
			positionListener.accept(new ReverbPreviewPlayer.Position(position, format.frames(), format.sampleRate()));
		}
	}

	private static int encode(double[][] samples, int frames, byte[] pcm) {
		int byteIndex = 0;
		for (int frame = 0; frame < frames; frame++)
			for (double[] channel : samples) {
				int value = (int) Math.round(Math.max(-1, Math.min(0.999969, channel[frame])) * 32768);
				pcm[byteIndex++] = (byte) value;
				pcm[byteIndex++] = (byte) (value >>> 8);
			}
		return byteIndex;
	}

	void update(LeslieSettings settings) {
		liveSettings = Objects.requireNonNull(settings, "settings");
	}

	void setBypassed(boolean value) {
		bypassed = value;
	}

	private static LeslieSettings bypassed(LeslieSettings value) {
		return new LeslieSettings(value.speed(), value.drive(), value.hornLevel(), value.micDistance(),
				value.stereoWidth(), value.crossoverHertz(), 0, 1);
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
		generation.incrementAndGet();
		synchronized (pauseLock) {
			stopped.set(true);
			paused = false;
			pauseLock.notifyAll();
		}
		ReverbPreviewPlayer.AudioSink active = sink;
		if (active != null)
			active.stop();
	}

	private boolean active(long session) {
		return !stopped.get() && generation.get() == session;
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
