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
package dev.mechana.localreverb;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Finds profiles placed beside the shaded application JAR by macOS packaging.
 */
final class BundledProfiles {
	private BundledProfiles() {
	}

	static Path directory() {
		return siblingDirectory("ir-profiles", "ir-profiles");
	}

	static Path sweep() {
		Path directory = siblingDirectory("capture", "capture");
		if (directory == null)
			return null;
		Path sweep = directory.resolve("mechana-ir-sweep-48k-24bit.wav");
		return Files.isRegularFile(sweep) ? sweep : null;
	}

	private static Path siblingDirectory(String packagedName, String developmentName) {
		try {
			Path application = Path
					.of(BundledProfiles.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			Path parent = Files.isDirectory(application)
					? application
					: Objects.requireNonNull(application.getParent());
			Path packaged = parent.resolve(packagedName);
			if (Files.isDirectory(packaged))
				return packaged;
		} catch (URISyntaxException | RuntimeException ignored) {
			// Development fallback below.
		}
		for (Path development : new Path[]{Path.of("src", "main", "distribution", developmentName),
				Path.of("standalone-reverb-app", "src", "main", "distribution", developmentName)}) {
			Path absolute = development.toAbsolutePath().normalize();
			if (Files.isDirectory(absolute))
				return absolute;
		}
		return null;
	}
}
