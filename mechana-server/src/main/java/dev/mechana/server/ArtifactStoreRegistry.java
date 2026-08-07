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

package dev.mechana.server;

import dev.mechana.api.ArtifactStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Server-side lookup of configured artifact storage providers. */
public final class ArtifactStoreRegistry {

	private final Map<String, ArtifactStore> stores = new LinkedHashMap<>();

	public ArtifactStoreRegistry register(ArtifactStore store) {
		Objects.requireNonNull(store, "store");
		ArtifactStore previous = stores.putIfAbsent(store.providerId(), store);
		if (previous != null)
			throw new IllegalArgumentException("Duplicate artifact provider: " + store.providerId());
		return this;
	}

	public ArtifactStore require(String providerId) {
		ArtifactStore store = stores.get(providerId);
		if (store == null)
			throw new IllegalArgumentException("Unknown artifact provider: " + providerId);
		return store;
	}

	public Map<String, ArtifactStore> providers() {
		return Map.copyOf(stores);
	}
}
