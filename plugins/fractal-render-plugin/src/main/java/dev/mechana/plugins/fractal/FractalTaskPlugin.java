package dev.mechana.plugins.fractal;

import dev.mechana.api.PluginDescriptor;
import dev.mechana.api.PluginExecutionException;
import dev.mechana.api.TaskContext;
import dev.mechana.api.TaskPlugin;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/** Renders one deterministic batch of Mandelbrot and Julia images. */
public final class FractalTaskPlugin implements TaskPlugin {
	private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("fractal-render", "1.0.0");
	private static final double[][] MANDELBROT_CENTERS = {{-0.743643887037151, 0.13182590420533},
			{-0.101096363845, 0.956286510809}, {-1.25066, 0.02012}, {-0.16, 1.0405}, {-0.7453, 0.1127}};
	private static final double[][] JULIA_CONSTANTS = {{-0.8, 0.156}, {0.285, 0.01}, {-0.4, 0.6}, {-0.70176, -0.3842},
			{-0.835, -0.2321}, {-0.7269, 0.1889}};

	@Override
	public PluginDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public void execute(TaskContext context) throws PluginExecutionException {
		Map<String, String> parameters = context.parameters();
		int startIndex = integer(parameters, "startIndex");
		int imageCount = integer(parameters, "imageCount");
		int width = integer(parameters, "width");
		int height = integer(parameters, "height");
		int maxIterations = integer(parameters, "maxIterations");
		long seed = Long.parseLong(parameters.get("seed"));
		int batchIndex = integer(parameters, "batchIndex");
		Path scratch = null;
		try {
			scratch = Files.createTempDirectory("mechana-fractal-");
			Path zip = scratch.resolve("batch-%05d.zip".formatted(batchIndex));
			try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
				for (int offset = 0; offset < imageCount; offset++) {
					if (context.isCancellationRequested())
						throw new PluginExecutionException("Fractal batch was cancelled", null);
					int imageIndex = startIndex + offset;
					BufferedImage image = render(imageIndex, width, height, maxIterations, seed, offset, imageCount,
							context);
					output.putNextEntry(new ZipEntry("fractal-%05d.png".formatted(imageIndex)));
					if (!ImageIO.write(image, "png", output))
						throw new IOException("PNG writer is unavailable");
					output.closeEntry();
				}
			}
			context.reportProgress(99);
			context.publishArtifact(
					java.util.Objects.requireNonNull(zip.getFileName(), "Batch path must have a file name").toString(),
					zip);
			context.reportProgress(100);
		} catch (IOException | RuntimeException failure) {
			throw new PluginExecutionException("Fractal batch failed", failure);
		} finally {
			deleteTree(scratch);
		}
	}

	private static BufferedImage render(int imageIndex, int width, int height, int maxIterations, long seed,
			int batchOffset, int batchSize, TaskContext context) throws PluginExecutionException {
		SplittableRandom random = new SplittableRandom(seed + 0x9E3779B97F4A7C15L * imageIndex);
		boolean julia = (imageIndex & 1) == 1;
		double[] center = MANDELBROT_CENTERS[Math.floorMod(imageIndex / 2, MANDELBROT_CENTERS.length)];
		double scale = julia ? 3.2 : 0.35 / Math.pow(2, random.nextInt(7));
		double centerX = julia ? 0 : center[0] + random.nextDouble(-scale / 12, scale / 12);
		double centerY = julia ? 0 : center[1] + random.nextDouble(-scale / 12, scale / 12);
		double[] juliaConstant = JULIA_CONSTANTS[Math.floorMod(imageIndex / 2, JULIA_CONSTANTS.length)];
		double juliaX = juliaConstant[0];
		double juliaY = juliaConstant[1];
		float hue = random.nextFloat();
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			if (context.isCancellationRequested())
				throw new PluginExecutionException("Fractal batch was cancelled", null);
			double imaginary = centerY + (y - height / 2.0) * scale / height;
			for (int x = 0; x < width; x++) {
				double real = centerX + (x - width / 2.0) * scale / height;
				double zx = julia ? real : 0;
				double zy = julia ? imaginary : 0;
				double cx = julia ? juliaX : real;
				double cy = julia ? juliaY : imaginary;
				int iteration = 0;
				while (zx * zx + zy * zy <= 4 && iteration < maxIterations) {
					double nextX = zx * zx - zy * zy + cx;
					zy = 2 * zx * zy + cy;
					zx = nextX;
					iteration++;
				}
				image.setRGB(x, y, color(iteration, maxIterations, zx, zy, hue));
			}
			if (y % 8 == 0) {
				double completed = batchOffset + (y + 1.0) / height;
				context.reportProgress(Math.min(98, (int) Math.floor(completed * 98 / batchSize)));
			}
		}
		return image;
	}

	private static int color(int iteration, int maximum, double zx, double zy, float hue) {
		if (iteration == maximum)
			return Color.BLACK.getRGB();
		double smooth = iteration + 1 - Math.log(Math.log(Math.sqrt(zx * zx + zy * zy))) / Math.log(2);
		float brightness = 0.3f + 0.7f * (float) Math.pow(Math.min(1, smooth / maximum), 0.22);
		return Color.HSBtoRGB((hue + (float) smooth / 48) % 1, 0.82f, brightness);
	}

	private static int integer(Map<String, String> parameters, String name) {
		return Integer.parseInt(parameters.get(name));
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
