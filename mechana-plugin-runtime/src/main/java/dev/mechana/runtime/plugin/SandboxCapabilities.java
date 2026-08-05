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

import java.util.Map;
import java.util.Objects;

/** Immutable, honest description of controls enforced by one backend. */
public record SandboxCapabilities(String backend, Map<SandboxControl, Boolean> enforced, String warning) {
	public SandboxCapabilities {
		Objects.requireNonNull(backend, "backend");
		enforced = Map.copyOf(enforced);
		warning = warning == null ? "" : warning;
	}
	public boolean enforces(SandboxControl control) {
		return enforced.getOrDefault(control, false);
	}
}
