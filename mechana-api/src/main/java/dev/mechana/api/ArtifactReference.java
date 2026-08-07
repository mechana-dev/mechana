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

/** Stable, storage-neutral identity for bytes managed by Mechana. */
public record ArtifactReference(String providerId, String key, long sizeBytes, String sha256) {

	public ArtifactReference {
		Objects.requireNonNull(providerId, "providerId");
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(sha256, "sha256");
		if (providerId.isBlank())
			throw new IllegalArgumentException("providerId must not be blank");
		if (key.isBlank())
			throw new IllegalArgumentException("key must not be blank");
		if (sizeBytes < 0)
			throw new IllegalArgumentException("sizeBytes must not be negative");
		if (!sha256.matches("[0-9a-fA-F]{64}"))
			throw new IllegalArgumentException("sha256 must contain 64 hexadecimal characters");
	}
}
