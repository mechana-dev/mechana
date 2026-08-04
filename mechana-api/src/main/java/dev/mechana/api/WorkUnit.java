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

import java.util.Map;

/**
 * Plugin-supplied display description and progress weight for one work unit.
 */
public record WorkUnit(String id, String label, double weight, Map<String, String> details) {
	public WorkUnit {
		if (id == null || id.isBlank())
			throw new IllegalArgumentException("Work-unit ID is required");
		if (label == null || label.isBlank())
			throw new IllegalArgumentException("Work-unit label is required");
		if (!Double.isFinite(weight) || weight <= 0)
			throw new IllegalArgumentException("Work-unit weight must be positive and finite");
		details = details == null ? Map.of() : Map.copyOf(details);
	}
}
