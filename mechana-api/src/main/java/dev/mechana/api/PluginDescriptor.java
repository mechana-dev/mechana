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

package dev.mechana.api;

import java.util.Objects;

/** Identity and compatibility information for an executable plugin. */
public record PluginDescriptor(String id, String version) {

	public PluginDescriptor {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(version, "version");
		if (id.isBlank() || version.isBlank()) {
			throw new IllegalArgumentException("Plugin id and version must not be blank");
		}
	}
}
