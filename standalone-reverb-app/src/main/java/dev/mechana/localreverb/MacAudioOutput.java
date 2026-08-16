/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package dev.mechana.localreverb;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Bridges Preview PCM to macOS Core Audio so system and AirPlay routes are
 * honored.
 */
final class MacAudioOutput {
	private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);

	private MacAudioOutput() {
	}

	record Device(String uid, String name, boolean javaCompatibility) {
		Device {
			Objects.requireNonNull(name, "name");
		}

		static Device systemDefault() {
			return new Device(null, "macOS System Output (AirPlay supported)", false);
		}

		static Device compatibilityOutput() {
			return new Device(null, "Java compatibility output", true);
		}

		@Override
		public String toString() {
			return name;
		}
	}

	static List<Device> devices() {
		Path helper = helperPath();
		if (helper == null)
			return List.of(Device.compatibilityOutput());
		List<Device> devices = new ArrayList<>();
		devices.add(Device.systemDefault());
		try {
			Process process = new ProcessBuilder(helper.toString(), "--list")
					.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) && process.exitValue() == 0)
				devices.addAll(parseDeviceList(output));
			else
				process.destroyForcibly();
		} catch (IOException failure) {
			// The system-default Core Audio route remains useful if enumeration fails.
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		devices.add(Device.compatibilityOutput());
		return List.copyOf(devices);
	}

	static List<Device> parseDeviceList(String output) {
		return output.lines().map(line -> line.split("\\t", 2)).filter(fields -> fields.length == 2)
				.filter(fields -> !fields[0].isBlank() && !fields[1].isBlank())
				.map(fields -> new Device(fields[0], fields[1], false)).toList();
	}

	static ReverbPreviewPlayer.AudioSinkFactory sinkFactory(Device device) {
		Objects.requireNonNull(device, "device");
		if (device.javaCompatibility())
			return ReverbPreviewPlayer.javaSoundSink();
		Path helper = helperPath();
		if (helper == null)
			return ReverbPreviewPlayer.javaSoundSink();
		return (sampleRate, channels) -> open(helper, device, sampleRate, channels);
	}

	private static ReverbPreviewPlayer.AudioSink open(Path helper, Device device, int sampleRate, int channels)
			throws IOException {
		List<String> command = new ArrayList<>(
				List.of(helper.toString(), "--play", Integer.toString(sampleRate), Integer.toString(channels)));
		if (device.uid() != null)
			command.add(device.uid());
		Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		return new CoreAudioSink(process, new BufferedOutputStream(process.getOutputStream(), 131_072));
	}

	private static Path helperPath() {
		String override = System.getProperty("mechana.preview.audio.helper");
		if (override != null && Files.isExecutable(Path.of(override)))
			return Path.of(override);
		String appPath = System.getProperty("jpackage.app-path");
		if (appPath == null)
			return null;
		Path executable = Path.of(appPath).toAbsolutePath().normalize();
		Path executableParent = executable.getParent();
		Path contents = executableParent == null ? null : executableParent.getParent();
		if (contents == null)
			return null;
		Path helper = contents.resolve("app").resolve("mechana-preview-audio");
		return Files.isExecutable(helper) ? helper : null;
	}

	private record CoreAudioSink(Process process, OutputStream output) implements ReverbPreviewPlayer.AudioSink {
		@Override
		public void start() throws IOException {
			if (!process.isAlive())
				throw new IOException("The selected macOS audio output could not be opened");
		}

		@Override
		public void write(byte[] samples, int length) throws IOException {
			output.write(samples, 0, length);
		}

		@Override
		public void drain() throws IOException {
			output.close();
			try {
				if (!process.waitFor(30, TimeUnit.SECONDS))
					throw new IOException("Timed out while finishing macOS audio playback");
				if (process.exitValue() != 0)
					throw new IOException("The selected macOS audio output stopped unexpectedly");
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IOException("Audio playback was interrupted", interrupted);
			}
		}

		@Override
		public void pause() {
			signal("-STOP");
		}

		@Override
		public void resume() {
			signal("-CONT");
		}

		@Override
		public void stop() {
			process.destroy();
		}

		@Override
		public void close() {
			try {
				output.close();
			} catch (IOException ignored) {
				// Process teardown below is authoritative.
			}
			if (process.isAlive())
				process.destroy();
		}

		private void signal(String signal) {
			if (!process.isAlive())
				return;
			try {
				new ProcessBuilder("/bin/kill", signal, Long.toString(process.pid())).start().waitFor(1,
						TimeUnit.SECONDS);
			} catch (IOException ignored) {
				// Playback will continue if process signaling is unavailable.
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
