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
package dev.mechana.localreverb;

import java.nio.file.Path;
import java.time.Instant;

/** Durable local job state presented by the standalone application. */
public record ReverbJob(String id, String status, int progress, Instant submittedAt, Instant completedAt,
		Path artifactDirectory, String outputName, String parameterSummary, String error) {
	public ReverbJob {
		parameterSummary = parameterSummary == null ? "" : parameterSummary;
		error = error == null ? "" : error;
	}

	ReverbJob withProgress(int value) {
		return new ReverbJob(id, status, value, submittedAt, completedAt, artifactDirectory, outputName,
				parameterSummary, error);
	}

	ReverbJob terminal(String terminalStatus, String message) {
		return new ReverbJob(id, terminalStatus, "SUCCEEDED".equals(terminalStatus) ? 100 : progress, submittedAt,
				Instant.now(), artifactDirectory, outputName, parameterSummary, message);
	}
}
