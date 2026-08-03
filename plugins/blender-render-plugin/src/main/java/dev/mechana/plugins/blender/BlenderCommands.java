package dev.mechana.plugins.blender;

import java.nio.file.Path;
import java.util.List;

/** Constructs stable headless Blender commands. */
public final class BlenderCommands {
	private final String blender;

	public BlenderCommands(String blender) {
		this.blender = blender;
	}

	public List<String> render(Path input, Path outputPattern, int firstFrame, int lastFrame, int width, int height,
			int samples, int threads) {
		String setup = "s=bpy.context.scene;s.render.resolution_x=%d;s.render.resolution_y=%d;".formatted(width, height)
				+ "s.render.resolution_percentage=100;s.render.engine='CYCLES';s.render.image_settings.file_format='PNG';"
				+ "s.render.film_transparent=False;s.render.use_persistent_data=True;s.cycles.samples=%d;"
						.formatted(samples)
				+ "s.cycles.use_denoising=True";
		return List.of(blender, "--background", "--disable-autoexec", input.toString(), "--python-expr",
				"import bpy;" + setup, "--threads", Integer.toString(threads), "--render-output",
				outputPattern.toString(), "--render-format", "PNG", "--frame-start", Integer.toString(firstFrame),
				"--frame-end", Integer.toString(lastFrame), "--render-anim", "--", "--cycles-device", "CPU");
	}

	public List<String> version() {
		return List.of(blender, "--version");
	}
}
