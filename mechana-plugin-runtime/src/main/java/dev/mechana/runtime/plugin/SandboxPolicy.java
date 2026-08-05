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

import java.time.Duration;
import java.util.Objects;

/** Resolved attempt budget. Fields are requirements, not enforcement claims. */
public record SandboxPolicy(TrustMode trustMode, boolean networkAllowed, int cpuCount, long memoryBytes,
		long scratchBytes, Duration timeout, int maxProcesses) {
	public SandboxPolicy {
		Objects.requireNonNull(trustMode, "trustMode");
		Objects.requireNonNull(timeout, "timeout");
		if (cpuCount < 1 || memoryBytes < 1 || scratchBytes < 1 || timeout.isNegative() || timeout.isZero()
				|| maxProcesses < 1)
			throw new IllegalArgumentException("Resource limits must be positive");
	}
}
