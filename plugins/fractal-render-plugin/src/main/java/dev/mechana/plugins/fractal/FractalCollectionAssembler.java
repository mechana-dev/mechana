package dev.mechana.plugins.fractal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * Validates worker batches and assembles the final fractal artifact collection.
 */
public final class FractalCollectionAssembler {
	private final ObjectMapper json = new ObjectMapper();

	public Result assemble(List<Path> batches, Path outputDirectory, int expectedImages, int width, int height,
			int maxIterations, long seed) throws IOException {
		Files.createDirectories(outputDirectory);
		Path images = outputDirectory.resolve("images");
		Files.createDirectories(images);
		for (Path batch : batches)
			extractBatch(batch, images);
		List<Path> rendered;
		try (var paths = Files.list(images)) {
			rendered = paths.filter(Files::isRegularFile).sorted().toList();
		}
		if (rendered.size() != expectedImages)
			throw new IOException("Expected " + expectedImages + " fractals but received " + rendered.size());
		for (int index = 0; index < expectedImages; index++) {
			String expected = "fractal-%05d.png".formatted(index);
			if (!fileName(rendered.get(index)).equals(expected))
				throw new IOException("Missing expected fractal image " + expected);
			if (ImageIO.read(rendered.get(index).toFile()) == null)
				throw new IOException("Invalid PNG image " + expected);
		}

		Path manifest = outputDirectory.resolve("manifest.json");
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("plugin", "fractal-render");
		document.put("version", "1.0.0");
		document.put("seed", seed);
		document.put("width", width);
		document.put("height", height);
		document.put("maxIterations", maxIterations);
		document.put("imageCount", expectedImages);
		document.put("images", rendered.stream().map(path -> "images/" + fileName(path)).toList());
		json.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(), document);

		Path contactSheet = outputDirectory.resolve("contact-sheet.png");
		writeContactSheet(rendered, contactSheet);
		Path collection = outputDirectory.resolve("fractal-collection.zip");
		writeCollection(rendered, manifest, contactSheet, collection);
		return new Result(manifest, contactSheet, collection, rendered);
	}

	private static void extractBatch(Path batch, Path images) throws IOException {
		try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(Files.newInputStream(batch)))) {
			for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
				if (entry.isDirectory() || !entry.getName().matches("fractal-[0-9]{5}\\.png"))
					throw new IOException("Unexpected batch entry " + entry.getName());
				Path destination = images.resolve(entry.getName()).normalize();
				if (!destination.startsWith(images) || Files.exists(destination))
					throw new IOException("Duplicate or invalid batch entry " + entry.getName());
				Files.copy(input, destination);
				input.closeEntry();
			}
		}
	}

	private static void writeContactSheet(List<Path> images, Path destination) throws IOException {
		int columns = Math.min(4, Math.max(1, (int) Math.ceil(Math.sqrt(images.size()))));
		int rows = (images.size() + columns - 1) / columns;
		int tileWidth = 320;
		int tileHeight = 200;
		BufferedImage sheet = new BufferedImage(columns * tileWidth, rows * tileHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = sheet.createGraphics();
		try {
			graphics.setColor(new Color(10, 16, 32));
			graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			for (int index = 0; index < images.size(); index++) {
				BufferedImage image = ImageIO.read(images.get(index).toFile());
				if (image == null)
					throw new IOException("Invalid PNG image " + images.get(index));
				int x = index % columns * tileWidth;
				int y = index / columns * tileHeight;
				graphics.drawImage(image, x, y, tileWidth, tileHeight, null);
			}
		} finally {
			graphics.dispose();
		}
		if (!ImageIO.write(sheet, "png", destination.toFile()))
			throw new IOException("PNG writer is unavailable");
	}

	private static void writeCollection(List<Path> images, Path manifest, Path contactSheet, Path destination)
			throws IOException {
		List<Path> files = new ArrayList<>(images);
		files.add(manifest);
		files.add(contactSheet);
		try (ZipOutputStream output = new ZipOutputStream(
				new BufferedOutputStream(Files.newOutputStream(destination)))) {
			for (Path file : files.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
				String name = file.equals(manifest) || file.equals(contactSheet)
						? fileName(file)
						: "images/" + fileName(file);
				output.putNextEntry(new ZipEntry(name));
				Files.copy(file, output);
				output.closeEntry();
			}
		}
	}

	private static String fileName(Path path) {
		return java.util.Objects.requireNonNull(path.getFileName(), "Path must have a file name").toString();
	}

	public record Result(Path manifest, Path contactSheet, Path collection, List<Path> images) {
		public Result {
			images = List.copyOf(images);
		}
	}
}
