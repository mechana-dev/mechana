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

/** Independent provider choices for a job's input, intermediate, and final artifacts. */
public record StorageSelection(String inputProviderId, String intermediateProviderId, String outputProviderId) {

	public static final String SERVER_LOCAL = "server-local";

	public static StorageSelection defaults() {
		return new StorageSelection(SERVER_LOCAL, SERVER_LOCAL, SERVER_LOCAL);
	}

	public StorageSelection {
		inputProviderId = normalized(inputProviderId);
		intermediateProviderId = normalized(intermediateProviderId);
		outputProviderId = normalized(outputProviderId);
	}

	private static String normalized(String providerId) {
		String value = Objects.requireNonNullElse(providerId, SERVER_LOCAL).trim();
		return value.isEmpty() ? SERVER_LOCAL : value;
	}
}
