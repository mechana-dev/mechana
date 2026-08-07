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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.mechana.api.TaskContext;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TesseractOcrPluginIT {
	@TempDir
	Path temporary;

	@Test
	void stagesWorkerProvidedPageWithoutNetworkAccess() throws Exception {
		Path source = temporary.resolve("staged-page.png");
		Path destination = temporary.resolve("work/page.png");
		Files.writeString(source, "page-image");
		Files.createDirectories(destination.getParent());

		TesseractOcrPlugin.stageInput(Map.of("pagePath.0", source.toString()), 0, destination);

		assertTrue(Files.mismatch(source, destination) == -1);
	}

	@Test
	void recognizesAWorkerDownloadedPage() throws Exception {
		Assumptions.assumeTrue(new TesseractRuntimeProbe().inspect("tesseract", "eng").usable(),
				"Tesseract with English traineddata is required");
		Path image = temporary.resolve("page.png");
		BufferedImage rendered = new BufferedImage(1600, 500, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D graphics = rendered.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, rendered.getWidth(), rendered.getHeight());
			graphics.setColor(Color.BLACK);
			graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 96));
			graphics.drawString("MECHANA OCR TEST", 100, 270);
		} finally {
			graphics.dispose();
		}
		ImageIO.write(rendered, "png", image.toFile());
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/page.png", exchange -> {
			exchange.sendResponseHeaders(200, Files.size(image));
			try (var output = exchange.getResponseBody()) {
				Files.copy(image, output);
			}
		});
		server.start();
		AtomicReference<Path> artifact = new AtomicReference<>();
		try {
			new TesseractOcrPlugin().execute(new TaskContext() {
				@Override
				public long durationMillis() {
					return 1;
				}

				@Override
				public Map<String, String> parameters() {
					return Map.of("startPage", "1", "pageCount", "1", "batchIndex", "0", "language", "eng", "pageUrl.0",
							"http://127.0.0.1:" + server.getAddress().getPort() + "/page.png");
				}

				@Override
				public void publishArtifact(String name, Path file) {
					try {
						Path copy = temporary.resolve(name);
						Files.copy(file, copy);
						artifact.set(copy);
					} catch (java.io.IOException failure) {
						throw new IllegalStateException(failure);
					}
				}

				@Override
				public void reportProgress(int percent) {
				}

				@Override
				public boolean isCancellationRequested() {
					return false;
				}
			});
		} finally {
			server.stop(0);
		}
		try (ZipInputStream input = new ZipInputStream(Files.newInputStream(artifact.get()))) {
			input.getNextEntry();
			String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(text.contains("MECHANA OCR TEST"), text);
		}
	}
}
