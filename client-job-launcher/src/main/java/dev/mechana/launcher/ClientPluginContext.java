/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.mechana.launcher;

import java.nio.file.Path;

record ClientPluginContext(String plugin, Path scratch, Path output, ClientArtifactDataPlane dataPlane, int imageCount,
		int width, int height, int maxIterations, long seed, int firstPage, int pageCount, String title, int firstFrame,
		int lastFrame, int fps) {
}
