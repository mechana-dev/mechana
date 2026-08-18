/* Copyright (c) 2026 Mark Vita. Licensed under the Apache License, Version 2.0. */
package dev.mechana.localreverb;

import dev.mechana.plugins.audio.DryAudioImporter;
import dev.mechana.plugins.audio.WavFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class OctaveFuzzPreviewPlayer implements AutoCloseable {
	private static final int BLOCK = 1_024;
	private final AtomicLong generation = new AtomicLong();
	private final Object pauseLock = new Object();
	private volatile boolean paused;
	private volatile boolean looping;
	private volatile boolean bypassed;
	private volatile OctaveFuzzSettings liveSettings = OctaveFuzzSettings.defaults();
	private volatile ReverbPreviewPlayer.AudioSink sink;
	private volatile ReverbPreviewPlayer.AudioSinkFactory sinkFactory = ReverbPreviewPlayer.javaSoundSink();
	private volatile Consumer<ReverbPreviewPlayer.Position> positionListener = ignored -> {
	};
	private volatile long activeSession;

	void play(Path source, OctaveFuzzSettings settings, double startFraction, Consumer<ReverbPreviewPlayer.State> state,
			Consumer<String> failure) {
		stop();
		liveSettings = Objects.requireNonNull(settings, "settings");
		paused = false;
		long session = generation.incrementAndGet();
		activeSession = session;
		Thread.ofVirtual().name("mechana-fuzz-preview")
				.start(() -> run(source, startFraction, state, failure, session));
	}

	private void run(Path source, double startFraction, Consumer<ReverbPreviewPlayer.State> state,
			Consumer<String> failure, long session) {
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
			if (activeSession == session) {
				activeSession = 0;
				sink = null;
			}
		}
	}

	private void stream(Path sourcePath, double startFraction, Consumer<ReverbPreviewPlayer.State> state, long session)
			throws IOException {
		Path directory = Files.createTempDirectory("mechana-fuzz-preview-");
		Path converted = directory.resolve("dry.wav");
		try {
			Path prepared = DryAudioImporter.prepareNative(sourcePath, converted);
			try (WavFile.Reader reader = WavFile.open(prepared)) {
				WavFile.Format format = reader.format();
				if (format.channels() < 1 || format.channels() > 2)
					throw new IOException("Octave Fuzz preview supports mono or stereo audio");
				long position = Math.min(format.frames(), Math.round(format.frames() * startFraction));
				reader.seekFrame(position);
				OctaveFuzzProcessor processor = new OctaveFuzzProcessor(format.sampleRate(), format.channels());
				try (ReverbPreviewPlayer.AudioSink output = sinkFactory.open(format.sampleRate(), format.channels())) {
					sink = output;
					output.start();
					state.accept(ReverbPreviewPlayer.State.PLAYING);
					double[][] samples = new double[format.channels()][BLOCK];
					byte[] pcm = new byte[BLOCK * format.channels() * 2];
					while (active(session) && position < format.frames()) {
						waitWhilePaused(session);
						int count = (int) Math.min(BLOCK, format.frames() - position);
						reader.read(samples, 0, count);
						OctaveFuzzSettings current = liveSettings;
						if (bypassed)
							current = new OctaveFuzzSettings(current.drive(), current.tone(), current.level(),
									current.octave(), true);
						processor.process(samples, count, current);
						output.write(pcm, encode(samples, count, pcm));
						position += count;
						positionListener.accept(
								new ReverbPreviewPlayer.Position(position, format.frames(), format.sampleRate()));
					}
					if (active(session))
						output.drain();
				}
			}
		} finally {
			Files.deleteIfExists(converted);
			Files.deleteIfExists(directory);
		}
	}

	private static int encode(double[][] samples, int frames, byte[] pcm) {
		int index = 0;
		for (int frame = 0; frame < frames; frame++)
			for (double[] channel : samples) {
				int value = (int) Math.round(Math.max(-1, Math.min(0.999969, channel[frame])) * 32768);
				pcm[index++] = (byte) value;
				pcm[index++] = (byte) (value >>> 8);
			}
		return index;
	}

	void update(OctaveFuzzSettings settings) {
		liveSettings = Objects.requireNonNull(settings);
	}
	void setBypassed(boolean value) {
		bypassed = value;
	}
	void setLooping(boolean value) {
		looping = value;
	}
	void setAudioSinkFactory(ReverbPreviewPlayer.AudioSinkFactory value) {
		sinkFactory = Objects.requireNonNull(value);
	}
	void onPosition(Consumer<ReverbPreviewPlayer.Position> value) {
		positionListener = Objects.requireNonNull(value);
	}
	boolean isActive() {
		return activeSession != 0;
	}

	void togglePause(Consumer<ReverbPreviewPlayer.State> state) {
		if (!isActive())
			return;
		synchronized (pauseLock) {
			paused = !paused;
			if (!paused)
				pauseLock.notifyAll();
		}
		ReverbPreviewPlayer.AudioSink output = sink;
		if (output != null) {
			if (paused)
				output.pause();
			else
				output.resume();
		}
		state.accept(paused ? ReverbPreviewPlayer.State.PAUSED : ReverbPreviewPlayer.State.PLAYING);
	}

	private void waitWhilePaused(long session) {
		synchronized (pauseLock) {
			while (paused && active(session))
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
		activeSession = 0;
		synchronized (pauseLock) {
			paused = false;
			pauseLock.notifyAll();
		}
		ReverbPreviewPlayer.AudioSink output = sink;
		if (output != null)
			output.stop();
	}

	private boolean active(long session) {
		return activeSession == session && generation.get() == session;
	}
	@Override
	public void close() {
		stop();
	}
}
