/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.mechana.launcher;

import dev.mechana.plugins.blender.BlenderMovieAssembler;
import dev.mechana.plugins.fractal.FractalCollectionAssembler;
import dev.mechana.plugins.ocr.OcrMarkdownAssembler;
import dev.mechana.protocol.Messages.ClientAssemblyCompletion;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Runs plugin-owned assembly over requester-local, lease-fenced worker outputs.
 */
final class ClientPluginAssembly {
	private final LauncherClient client;

	ClientPluginAssembly(LauncherClient client) {
		this.client = client;
	}

	void assemble(URI server, String jobId, ClientPluginContext context) throws IOException, InterruptedException {
		var manifest = client.videoAssemblyManifest(server, jobId);
		List<Path> batches = new ArrayList<>(manifest.segments().size());
		for (int index = 0; index < manifest.segments().size(); index++)
			batches.add(context.dataPlane().acceptedOutput(index, manifest.segments().get(index).key()).path());
		Files.createDirectories(context.output());
		Path result = context.scratch().resolve("assembled-" + jobId);
		Files.createDirectories(result);
		Path finalArtifact;
		String finalName;
		switch (context.plugin()) {
			case "fractal-render" -> {
				var assembled = new FractalCollectionAssembler().assemble(batches, result, context.imageCount(),
						context.width(), context.height(), context.maxIterations(), context.seed());
				finalName = "fractal-collection.zip";
				finalArtifact = context.output().resolve(finalName);
				Files.copy(assembled.collection(), finalArtifact, StandardCopyOption.REPLACE_EXISTING);
			}
			case "ocr-tesseract" -> {
				new OcrMarkdownAssembler().assemble(batches, result, context.firstPage(), context.pageCount(),
						context.title());
				finalName = "ocr-document.zip";
				finalArtifact = context.output().resolve(finalName);
				zipTree(result, finalArtifact);
			}
			case "blender-render" -> {
				Path movie = new BlenderMovieAssembler().assemble(batches, result, context.firstFrame(),
						context.lastFrame(), context.width(), context.height(), context.fps(), "ffmpeg");
				finalName = "animation.mp4";
				finalArtifact = context.output().resolve(finalName);
				Files.copy(movie, finalArtifact, StandardCopyOption.REPLACE_EXISTING);
			}
			default -> throw new IllegalArgumentException("Unsupported client assembly plugin " + context.plugin());
		}
		client.completeClientAssembly(server, jobId, new ClientAssemblyCompletion("client-local",
				finalArtifact.toString(), finalName, Files.size(finalArtifact), sha256(finalArtifact)));
	}

	private static void zipTree(Path root, Path destination) throws IOException {
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(destination));
				var paths = Files.walk(root)) {
			for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
				output.putNextEntry(new ZipEntry(root.relativize(path).toString().replace('\\', '/')));
				Files.copy(path, output);
				output.closeEntry();
			}
		}
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
}
