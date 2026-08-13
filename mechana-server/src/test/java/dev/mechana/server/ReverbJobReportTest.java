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

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mechana.coordinator.InMemoryJobMonitor;
import dev.mechana.protocol.Messages.AudioReverbJobSubmitRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReverbJobReportTest {
	@Test
	void rendersInputsParametersTimingWorkersAndArtifacts() {
		var request = new AudioReverbJobSubmitRequest("/audio/Scott Voice.wav", "/irs/Small Plate.wav",
				"Scott-Voice-reverb.wav", 1, 0.35, 1.0, 20.0, true, true, 1.0, "server-local", "/audio/results");
		var work = new InMemoryJobMonitor.WorkUnitSnapshot("job-1-1", "Convolution", "SUCCEEDED", 100, "1.250s",
				"mba-worker", Map.of("samples", "96000"));
		var snapshot = new InMemoryJobMonitor.Snapshot("job-1", "audio-convolution-reverb", "SUCCEEDED", 100, "1.500s",
				1, 0, 1, 1, "", "2026-08-13T10:00:02Z", Map.of(), List.of(work), List.of());
		var output = new CompletedJobStore.Artifact("Scott-Voice-reverb.wav", 123_456, "server-local",
				"jobs/job-1/artifacts/Scott-Voice-reverb.wav", "abc123");

		String report = ReverbJobReport.render(Instant.parse("2026-08-13T10:00:00Z"), snapshot, request, 48_000, 24_000,
				0.5, List.of(output));

		assertTrue(report.contains("Job ID: job-1"));
		assertTrue(report.contains("Wall-clock duration: 2.000 seconds"));
		assertTrue(report.contains("Workers: mba-worker"));
		assertTrue(report.contains("Dry audio: Scott Voice.wav"));
		assertTrue(report.contains("Impulse-response WAV: Small Plate.wav"));
		assertTrue(report.contains("Wet level: 0.35"));
		assertTrue(report.contains("Normalize IR: Yes"));
		assertTrue(report.contains("Safe headroom: 1.0 dB"));
		assertTrue(report.contains("Applied output gain: 0.500000000 (-6.021 dB)"));
		assertTrue(report.contains("Peak protection engaged: Yes"));
		assertTrue(report.contains("Shared artifact root: /audio/results"));
		assertTrue(report.contains("- Scott-Voice-reverb.wav"));
		assertTrue(report.contains("123,456 bytes"));
		assertTrue(report.contains("SHA-256: abc123"));
	}
}
