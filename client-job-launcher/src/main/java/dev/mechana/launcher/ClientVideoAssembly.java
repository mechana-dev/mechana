/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.mechana.launcher;

import dev.mechana.protocol.Messages.ArtifactReference;
import dev.mechana.protocol.Messages.ClientAssemblyCompletion;
import dev.mechana.protocol.Messages.VideoAssemblyManifest;
import dev.mechana.plugins.video.FfmpegCommands;
import dev.mechana.plugins.video.CancellationToken;
import dev.mechana.plugins.video.ExternalProcessRunner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Downloads verified worker segments and assembles the final FFmpeg artifact on
 * the launcher host.
 */
final class ClientVideoAssembly {
	private final LauncherClient client;

	ClientVideoAssembly(LauncherClient client) {
		this.client = client;
	}

	Path assemble(URI server, String jobId, Path source, Path scratchDirectory, Path outputDirectory,
			double startOffsetSeconds, double durationSeconds, long videoBitrate)
			throws IOException, InterruptedException {
		Files.createDirectories(scratchDirectory);
		Files.createDirectories(outputDirectory);
		Path jobScratch = scratchDirectory.resolve(jobId).toAbsolutePath().normalize();
		Path segmentsDirectory = jobScratch.resolve("segments");
		Files.createDirectories(segmentsDirectory);
		VideoAssemblyManifest manifest = client.videoAssemblyManifest(server, jobId);
		List<Path> segments = new ArrayList<>(manifest.segments().size());
		for (int index = 0; index < manifest.segments().size(); index++) {
			ArtifactReference artifact = manifest.segments().get(index);
			Path destination = segmentsDirectory.resolve("segment-%05d.mkv".formatted(index));
			client.downloadArtifact(server, artifact, destination);
			verify(destination, artifact);
			segments.add(destination);
		}
		return assemblePaths(server, jobId, source, outputDirectory, startOffsetSeconds, durationSeconds, videoBitrate,
				jobScratch, segments);
	}

	Path assembleDirect(URI server, String jobId, Path source, Path scratchDirectory, Path outputDirectory,
			double startOffsetSeconds, double durationSeconds, long videoBitrate, ClientArtifactDataPlane dataPlane)
			throws IOException, InterruptedException {
		Files.createDirectories(scratchDirectory);
		Files.createDirectories(outputDirectory);
		Path jobScratch = scratchDirectory.resolve(jobId).toAbsolutePath().normalize();
		Files.createDirectories(jobScratch);
		try {
			VideoAssemblyManifest manifest = client.videoAssemblyManifest(server, jobId);
			List<Path> segments = new ArrayList<>(manifest.segments().size());
			for (int index = 0; index < manifest.segments().size(); index++) {
				ArtifactReference accepted = manifest.segments().get(index);
				ClientArtifactDataPlane.LocalArtifact artifact = dataPlane.acceptedOutput(index, accepted.key());
				ArtifactReference identity = new ArtifactReference("client-local", artifact.path().toString(),
						artifact.size(), "", true, artifact.sha256());
				verify(artifact.path(), identity);
				segments.add(artifact.path());
			}
			return assemblePaths(server, jobId, source, outputDirectory, startOffsetSeconds, durationSeconds,
					videoBitrate, jobScratch, segments);
		} finally {
			deleteTree(jobScratch);
		}
	}

	private static void deleteTree(Path directory) throws IOException {
		if (!Files.exists(directory))
			return;
		Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
			@Override
			public java.nio.file.FileVisitResult visitFile(Path file,
					java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
				Files.deleteIfExists(file);
				return java.nio.file.FileVisitResult.CONTINUE;
			}

			@Override
			public java.nio.file.FileVisitResult postVisitDirectory(Path current, IOException failure)
					throws IOException {
				if (failure != null)
					throw failure;
				Files.deleteIfExists(current);
				return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
	}

	private Path assemblePaths(URI server, String jobId, Path source, Path outputDirectory, double startOffsetSeconds,
			double durationSeconds, long videoBitrate, Path jobScratch, List<Path> segments)
			throws IOException, InterruptedException {
		String ffmpeg = ffmpegExecutable();
		Path video = jobScratch.resolve("video.mkv");
		FfmpegCommands commands = new FfmpegCommands(ffmpeg, "ffprobe");
		run(commands.safeConcat(segments, video, videoBitrate, "slow"));
		Path output = finalOutputPath(outputDirectory, jobId);
		run(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", video.toString(), "-ss",
				Double.toString(startOffsetSeconds), "-i", source.toAbsolutePath().normalize().toString(), "-map",
				"0:v:0", "-map", "1:a:0?", "-t", Double.toString(durationSeconds), "-c", "copy", "-f", "matroska",
				output.toString()));
		validateDecode(commands, output);
		String sha256 = sha256(output);
		client.completeClientAssembly(server, jobId, new ClientAssemblyCompletion("client-local", output.toString(),
				Objects.requireNonNull(output.getFileName()).toString(), Files.size(output), sha256));
		return output;
	}

	static Path finalOutputPath(Path outputDirectory, String jobId) throws IOException {
		Path outputRoot = outputDirectory.toAbsolutePath().normalize();
		Path jobOutputDirectory = outputRoot.resolve(jobId).normalize();
		if (!Objects.equals(jobOutputDirectory.getParent(), outputRoot))
			throw new IOException("Invalid job ID for client output directory: " + jobId);
		Files.createDirectories(jobOutputDirectory);
		return jobOutputDirectory.resolve("compressed-" + jobId + ".mkv");
	}

	static void verify(Path file, ArtifactReference artifact) throws IOException {
		if (Files.size(file) != artifact.size() || !sha256(file).equalsIgnoreCase(artifact.sha256())) {
			Files.deleteIfExists(file);
			throw new IOException("Downloaded artifact failed integrity verification: " + artifact.key());
		}
	}

	private static void run(List<String> command) throws IOException, InterruptedException {
		Path log = Files.createTempFile("mechana-client-ffmpeg-", ".log");
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
					.start();
			if (!process.waitFor(Duration.ofHours(6).toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new IOException("FFmpeg client assembly timed out");
			}
			if (process.exitValue() != 0)
				throw new IOException("FFmpeg client assembly failed: " + Files.readString(log));
		} finally {
			Files.deleteIfExists(log);
		}
	}

	private static void validateDecode(FfmpegCommands commands, Path output) throws IOException, InterruptedException {
		var decoded = new ExternalProcessRunner().run(commands.decodeValidate(output), Duration.ofHours(6),
				CancellationToken.NEVER, ignored -> {
				});
		if (decoded.exitCode() != 0 || !decoded.stderr().isBlank())
			throw new IOException("Final video does not decode cleanly: " + decoded.stderr().strip());
	}

	@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "Standard Homebrew FFmpeg locations")
	private static String ffmpegExecutable() {
		for (Path candidate : List.of(Path.of("/opt/homebrew/bin/ffmpeg"), Path.of("/usr/local/bin/ffmpeg")))
			if (Files.isExecutable(candidate))
				return candidate.toString();
		return "ffmpeg";
	}

	private static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (var input = Files.newInputStream(file)) {
				byte[] buffer = new byte[64 * 1024];
				for (int read; (read = input.read(buffer)) >= 0;)
					if (read > 0)
						digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
