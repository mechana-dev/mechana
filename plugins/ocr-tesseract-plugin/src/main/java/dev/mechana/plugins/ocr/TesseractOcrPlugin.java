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

package dev.mechana.plugins.ocr;

import dev.mechana.api.PluginDescriptor;
import dev.mechana.api.PluginExecutionException;
import dev.mechana.api.TaskContext;
import dev.mechana.api.TaskPlugin;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Runs Tesseract over one server-rendered batch of page images. */
public final class TesseractOcrPlugin implements TaskPlugin {
	private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("ocr-tesseract", "1.0.0");

	@Override
	public PluginDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public void execute(TaskContext context) throws PluginExecutionException {
		Map<String, String> parameters = context.parameters();
		int startPage = integer(parameters, "startPage");
		int pageCount = integer(parameters, "pageCount");
		int batchIndex = integer(parameters, "batchIndex");
		String language = parameters.getOrDefault("language", "eng");
		String tesseract = parameters.getOrDefault("tesseractCommand", "tesseract");
		Path scratch = null;
		try {
			scratch = Files.createTempDirectory("mechana-ocr-");
			List<Path> textPages = new ArrayList<>(pageCount);
			for (int offset = 0; offset < pageCount; offset++) {
				if (context.isCancellationRequested())
					throw new PluginExecutionException("OCR batch was cancelled", null);
				int page = startPage + offset;
				Path image = scratch.resolve("page-%06d.png".formatted(page));
				stageInput(parameters, offset, image);
				Path outputBase = scratch.resolve("page-%06d".formatted(page));
				runTesseract(tesseract, image, outputBase, language, context);
				Path text = Path.of(outputBase + ".txt");
				if (!Files.isRegularFile(text))
					throw new IOException("Tesseract did not create text for page " + page);
				textPages.add(text);
				context.reportProgress(Math.min(95, (offset + 1) * 95 / pageCount));
			}
			Path archive = scratch.resolve("ocr-batch-%05d.zip".formatted(batchIndex));
			writeArchive(textPages, archive);
			context.reportProgress(98);
			context.publishArtifact(fileName(archive), archive);
			context.reportProgress(100);
		} catch (IOException | InterruptedException | RuntimeException failure) {
			if (failure instanceof InterruptedException)
				Thread.currentThread().interrupt();
			throw new PluginExecutionException("Tesseract OCR batch failed", failure);
		} finally {
			deleteTree(scratch);
		}
	}

	static void stageInput(Map<String, String> parameters, int offset, Path destination)
			throws IOException, InterruptedException {
		String local = parameters.get("pagePath." + offset);
		if (local != null) {
			Files.copy(Path.of(local), destination);
			return;
		}
		download(parameters.get("pageUrl." + offset), destination);
	}

	static List<String> command(String executable, Path image, Path outputBase, String language) {
		return List.of(executable, image.toString(), outputBase.toString(), "-l", language, "--psm", "1", "txt");
	}

	private static void runTesseract(String executable, Path image, Path outputBase, String language,
			TaskContext context) throws IOException, InterruptedException {
		Path log = outputBase.resolveSibling(outputBase.getFileName() + ".log");
		Process process = new ProcessBuilder(command(executable, image, outputBase, language)).redirectErrorStream(true)
				.redirectOutput(log.toFile()).start();
		long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
		while (!process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
			if (context.isCancellationRequested()) {
				process.destroyForcibly();
				throw new IOException("Tesseract was cancelled");
			}
			if (System.nanoTime() >= deadline) {
				process.destroyForcibly();
				throw new IOException("Tesseract timed out");
			}
		}
		if (process.exitValue() != 0)
			throw new IOException("Tesseract exited " + process.exitValue() + ": "
					+ Files.readString(log, StandardCharsets.UTF_8).strip());
	}

	private static void download(String url, Path destination) throws IOException, InterruptedException {
		if (url == null || url.isBlank())
			throw new IllegalArgumentException("Missing page image URL");
		HttpResponse<Path> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
				HttpResponse.BodyHandlers.ofFile(destination));
		if (response.statusCode() != 200)
			throw new IOException("Page image download returned HTTP " + response.statusCode());
	}

	private static void writeArchive(List<Path> pages, Path destination) throws IOException {
		try (ZipOutputStream output = new ZipOutputStream(
				new BufferedOutputStream(Files.newOutputStream(destination)))) {
			for (Path page : pages) {
				output.putNextEntry(new ZipEntry(fileName(page)));
				try (var input = new BufferedInputStream(Files.newInputStream(page))) {
					input.transferTo(output);
				}
				output.closeEntry();
			}
		}
	}

	private static int integer(Map<String, String> parameters, String name) {
		return Integer.parseInt(parameters.get(name));
	}

	private static String fileName(Path path) {
		return java.util.Objects.requireNonNull(path.getFileName()).toString();
	}

	private static void deleteTree(Path root) {
		if (root == null)
			return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// Worker scratch cleanup is best effort.
		}
	}
}
