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
package dev.mechana.runtime.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

/** Fixed job/attempt workspace layout. */
public record AttemptWorkspace(Path root, Path input, Path work, Path output, Path logs) {
	public static AttemptWorkspace create(Path sandboxRoot, String jobId, String attemptId) throws IOException {
		Path requestedRoot = sandboxRoot.toAbsolutePath().normalize().resolve(safe(jobId)).resolve(safe(attemptId));
		Files.createDirectories(requestedRoot);
		Path root = requestedRoot.toRealPath();
		try {
			Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
		} catch (UnsupportedOperationException ignored) {
		}
		Path input = Files.createDirectories(root.resolve("input"));
		Path work = Files.createDirectories(root.resolve("work"));
		Path output = Files.createDirectories(root.resolve("output"));
		Path logs = Files.createDirectories(root.resolve("logs"));
		return new AttemptWorkspace(root, input, work, output, logs);
	}
	public AttemptWorkspace {
		Objects.requireNonNull(root);
		Objects.requireNonNull(input);
		Objects.requireNonNull(work);
		Objects.requireNonNull(output);
		Objects.requireNonNull(logs);
	}
	private static String safe(String value) {
		if (value == null || !value.matches("[A-Za-z0-9._-]+"))
			throw new IllegalArgumentException("Unsafe workspace identity");
		return value;
	}
}
