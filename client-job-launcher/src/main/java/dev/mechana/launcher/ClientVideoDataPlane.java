/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.mechana.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mechana.plugins.video.CancellationToken;
import dev.mechana.plugins.video.ExternalProcessRunner;
import dev.mechana.plugins.video.FfmpegCommands;
import dev.mechana.plugins.video.MediaProbe;
import dev.mechana.plugins.video.VideoTypes;
import dev.mechana.protocol.Messages.ClientVideoChunk;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Requester-hosted, tokenized HTTP data plane for direct FFmpeg worker
 * transfers.
 */
final class ClientVideoDataPlane implements AutoCloseable {
	record Prepared(List<ClientVideoChunk> chunks, long videoBitrate, double durationSeconds) {
	}
	record LocalArtifact(Path path, long size, String sha256) {
	}

	private final Path root;
	private final String token = UUID.randomUUID().toString();
	private final String advertisedHost;
	private final HttpServer http;
	private final Map<String, LocalArtifact> outputs = new ConcurrentHashMap<>();
	private List<Path> chunks = List.of();

	ClientVideoDataPlane(Path scratchDirectory, String configuredHost) throws IOException {
		root = scratchDirectory.resolve("client-transfer-" + token).toAbsolutePath().normalize();
		Files.createDirectories(root.resolve("chunks"));
		Files.createDirectories(root.resolve("worker-outputs"));
		advertisedHost = configuredHost == null || configuredHost.isBlank()
				? automaticHost(InetAddress.getLocalHost().getHostName())
				: configuredHost.strip();
		http = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
		http.createContext("/client-video/" + token + "/", this::handle);
		http.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		http.start();
	}

	Prepared prepare(Path source, double requestedDuration, int segmentCount, double targetSizeRatio)
			throws IOException, InterruptedException {
		String ffmpeg = executable("ffmpeg");
		String ffprobe = executable("ffprobe");
		ExternalProcessRunner runner = new ExternalProcessRunner();
		FfmpegCommands commands = new FfmpegCommands(ffmpeg, ffprobe);
		MediaProbe probe = new MediaProbe(commands, runner);
		VideoTypes.MediaInfo original = probe.inspect(source, Duration.ofMinutes(5));
		double duration = Math.min(requestedDuration, original.durationSeconds());
		Path prepared = root.resolve("prepared-input.mp4");
		var clipped = runner.run(
				List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", source.toString(), "-t",
						Double.toString(duration), "-c", "copy", prepared.toString()),
				Duration.ofMinutes(15), CancellationToken.NEVER, ignored -> {
				});
		if (clipped.exitCode() != 0)
			throw new IOException("FFmpeg input preparation failed: " + clipped.stderr().strip());
		VideoTypes.MediaInfo info = probe.inspect(prepared, Duration.ofMinutes(5));
		VideoTypes.Options options = new VideoTypes.Options(VideoTypes.Container.MKV,
				VideoTypes.QualityMode.VISUALLY_LOSSLESS, 28, "slow",
				Duration.ofMillis(Math.max(1, Math.round(info.durationSeconds() * 1000 / segmentCount))), segmentCount,
				Duration.ofHours(2));
		List<VideoTypes.Segment> plan = exactSegments(info, options, probe.keyframes(prepared, Duration.ofMinutes(5)),
				segmentCount);
		List<Path> localChunks = new ArrayList<>(plan.size());
		List<ClientVideoChunk> references = new ArrayList<>(plan.size());
		for (VideoTypes.Segment segment : plan) {
			Path chunk = root.resolve("chunks/input-%05d.mp4".formatted(segment.index()));
			var copied = runner.run(commands.copySegment(prepared, segment, chunk), Duration.ofMinutes(5),
					CancellationToken.NEVER, ignored -> {
					});
			if (copied.exitCode() != 0)
				throw new IOException("FFmpeg chunk creation failed: " + copied.stderr().strip());
			localChunks.add(chunk);
			references.add(new ClientVideoChunk(baseUrl() + "/chunks/" + segment.index(), segment.startSeconds(),
					segment.endSeconds(), Files.size(chunk), sha256(chunk)));
		}
		chunks = List.copyOf(localChunks);
		long targetBytes = Math.max(1, Math.round(info.inputBytes() * targetSizeRatio));
		long audioAllowance = info.audioStreams() > 0 ? 192_000 : 0;
		long bitrate = Math.max(100_000, Math.round(targetBytes * 8 / info.durationSeconds()) - audioAllowance);
		return new Prepared(List.copyOf(references), bitrate, info.durationSeconds());
	}

	String outputUrl(int index) {
		return baseUrl() + "/outputs/" + index;
	}

	LocalArtifact acceptedOutput(int index, String leaseHash) throws IOException {
		LocalArtifact artifact = outputs.get(index + ":" + leaseHash);
		if (artifact == null || !Files.isRegularFile(artifact.path()))
			throw new IOException("Accepted worker output is unavailable for segment " + index);
		return artifact;
	}

	private String baseUrl() {
		return "http://" + advertisedHost + ":" + http.getAddress().getPort() + "/client-video/" + token;
	}

	static String automaticHost(String localHostName) {
		String host = localHostName.strip();
		return host.toLowerCase(java.util.Locale.ROOT).endsWith(".local")
				? host.substring(0, host.length() - ".local".length())
				: host;
	}

	private void handle(HttpExchange exchange) throws IOException {
		try {
			String suffix = exchange.getRequestURI().getPath().substring(("/client-video/" + token + "/").length());
			if ("GET".equals(exchange.getRequestMethod()) && suffix.startsWith("chunks/")) {
				int index = Integer.parseInt(suffix.substring("chunks/".length()));
				Path chunk = chunks.get(index);
				exchange.getResponseHeaders().set("Content-Type", "video/mp4");
				exchange.getResponseHeaders().set("X-Checksum-Sha256", sha256(chunk));
				exchange.sendResponseHeaders(200, Files.size(chunk));
				try (var output = exchange.getResponseBody()) {
					Files.copy(chunk, output);
				}
				return;
			}
			if ("PUT".equals(exchange.getRequestMethod()) && suffix.startsWith("outputs/")) {
				int index = Integer.parseInt(suffix.substring("outputs/".length()));
				if (index < 0 || index >= chunks.size())
					throw new IllegalArgumentException("Invalid segment index");
				String lease = exchange.getRequestHeaders().getFirst("X-Mechana-Lease");
				if (lease == null || lease.isBlank())
					throw new IllegalArgumentException("Missing worker lease");
				String leaseHash = sha256(lease.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				Path destination = root.resolve("worker-outputs/segment-%05d-%s.mkv".formatted(index, leaseHash));
				Path temporary = Files.createTempFile(root.resolve("worker-outputs"), ".upload-", ".tmp");
				try (var input = exchange.getRequestBody()) {
					Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
				}
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				outputs.put(index + ":" + leaseHash,
						new LocalArtifact(destination, Files.size(destination), sha256(destination)));
				exchange.sendResponseHeaders(204, -1);
				exchange.close();
				return;
			}
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		} catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
			exchange.sendResponseHeaders(400, -1);
			exchange.close();
		}
	}

	private static List<VideoTypes.Segment> exactSegments(VideoTypes.MediaInfo input, VideoTypes.Options options,
			List<Double> keyframes, int segmentCount) throws IOException {
		List<Double> candidates = keyframes.stream().filter(keyframe -> keyframe >= 0.5)
				.filter(keyframe -> input.durationSeconds() - keyframe >= 0.5).sorted().toList();
		int cuts = segmentCount - 1;
		if (candidates.size() < cuts)
			throw new IOException("The clip has too few usable keyframes for " + segmentCount + " chunks");
		List<Double> boundaries = new ArrayList<>(segmentCount + 1);
		boundaries.add(0.0);
		int previous = -1;
		for (int cut = 1; cut <= cuts; cut++) {
			double desired = input.durationSeconds() * cut / segmentCount;
			int lastAllowed = candidates.size() - (cuts - cut) - 1;
			int chosen = previous + 1;
			for (int index = chosen + 1; index <= lastAllowed; index++)
				if (Math.abs(candidates.get(index) - desired) < Math.abs(candidates.get(chosen) - desired))
					chosen = index;
			boundaries.add(candidates.get(chosen));
			previous = chosen;
		}
		boundaries.add(input.durationSeconds());
		List<VideoTypes.Segment> result = new ArrayList<>(segmentCount);
		for (int index = 0; index < segmentCount; index++)
			result.add(new VideoTypes.Segment(index, boundaries.get(index), boundaries.get(index + 1),
					Path.of("segment-%05d.mkv".formatted(index))));
		return result;
	}

	@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "Standard Homebrew tool locations")
	private static String executable(String name) {
		for (Path directory : List.of(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"))) {
			Path candidate = directory.resolve(name);
			if (Files.isExecutable(candidate))
				return candidate.toString();
		}
		return name;
	}

	private static String sha256(Path file) throws IOException {
		try (var input = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			for (int read; (read = input.read(buffer)) >= 0;)
				if (read > 0)
					digest.update(buffer, 0, read);
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	@Override
	public void close() {
		http.stop(0);
	}
}
