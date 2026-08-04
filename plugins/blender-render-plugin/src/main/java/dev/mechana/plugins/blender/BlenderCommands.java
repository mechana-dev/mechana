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
