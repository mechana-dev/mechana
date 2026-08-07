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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Path;
import java.util.function.Consumer;

/** One process launch under an attempt policy. */
public record SandboxRequest(List<String> command, Map<String, String> environment, AttemptWorkspace workspace,
		SandboxPolicy policy, Path standardInput, Consumer<String> stdoutLineConsumer,
		List<Path> runtimeReadOnlyPaths) {
	public SandboxRequest(List<String> command, Map<String, String> environment, AttemptWorkspace workspace,
			SandboxPolicy policy) {
		this(command, environment, workspace, policy, null, null, List.of());
	}
	public SandboxRequest(List<String> command, Map<String, String> environment, AttemptWorkspace workspace,
			SandboxPolicy policy, Path standardInput, Consumer<String> stdoutLineConsumer) {
		this(command, environment, workspace, policy, standardInput, stdoutLineConsumer, List.of());
	}
	public SandboxRequest {
		command = List.copyOf(command);
		environment = Map.copyOf(environment);
		runtimeReadOnlyPaths = List.copyOf(runtimeReadOnlyPaths);
		Objects.requireNonNull(workspace);
		Objects.requireNonNull(policy);
		if (command.isEmpty())
			throw new IllegalArgumentException("Command is required");
	}
}
