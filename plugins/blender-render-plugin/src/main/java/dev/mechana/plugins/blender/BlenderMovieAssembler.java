package dev.mechana.plugins.blender;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

/** Validates ordered frame batches and assembles an H.265 movie with FFmpeg. */
public final class BlenderMovieAssembler {
	public Path assemble(List<Path> batches, Path result, int firstFrame, int lastFrame, int width, int height, int fps,
			String ffmpeg) throws IOException, InterruptedException {
		Path frames = Files.createDirectories(result.resolve("frames"));
		for (Path batch : batches)
			extract(batch, frames);
		for (int frame = firstFrame; frame <= lastFrame; frame++) {
			Path image = frames.resolve("frame_%06d.png".formatted(frame));
			BufferedImage decoded = Files.isRegularFile(image) ? ImageIO.read(image.toFile()) : null;
			if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height)
				throw new IOException("Missing or incompatible frame " + frame);
		}
		Path movie = result.resolve("animation.mp4");
		Path logFile = result.resolve("ffmpeg-assembly.log");
		List<String> command = movieCommand(ffmpeg, frames.resolve("frame_%06d.png"), movie, firstFrame, fps);
		Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile.toFile())
				.start();
		if (!process.waitFor(Duration.ofMinutes(30)) || process.exitValue() != 0) {
			process.destroyForcibly();
			process.waitFor();
			String log = Files.readString(logFile);
			throw new IOException("FFmpeg movie assembly failed: " + log.strip());
		}
		return movie;
	}

	static List<String> movieCommand(String ffmpeg, Path input, Path output, int firstFrame, int fps) {
		return List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-framerate", Integer.toString(fps),
				"-start_number", Integer.toString(firstFrame), "-i", input.toString(), "-c:v", "libx265", "-crf", "20",
				"-preset", "medium", "-pix_fmt", "yuv420p", "-tag:v", "hvc1", "-movflags", "+faststart",
				output.toString());
	}

	private static void extract(Path archive, Path destination) throws IOException {
		try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
			for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
				if (!entry.isDirectory() && entry.getName().matches("frame_[0-9]{6}\\.png"))
					Files.copy(input, destination.resolve(entry.getName()),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}
}
