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

package dev.mechana.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mechana.protocol.Messages.JobStatusResponse;
import dev.mechana.protocol.Messages.JobSubmission;
import dev.mechana.protocol.Messages.JobSubmitRequest;
import dev.mechana.protocol.Messages.FractalJobSubmitRequest;
import dev.mechana.protocol.Messages.OcrJobSubmitRequest;
import dev.mechana.protocol.Messages.BlenderJobSubmitRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.List;

/** Minimal client for job submission and completion observation. */
public final class MechanaClient {

	private final ObjectMapper json = new ObjectMapper();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final URI server;

	public MechanaClient(URI server) {
		String value = Objects.requireNonNull(server, "server").toString();
		this.server = URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
	}

	public String submit(int taskCount, long durationMillis) throws IOException, InterruptedException {
		return submit(new JobSubmitRequest(taskCount, durationMillis));
	}

	public String submit(List<Long> taskDurationsMillis) throws IOException, InterruptedException {
		return submit(new JobSubmitRequest(0, 0, taskDurationsMillis));
	}

	public String submitFractals(FractalJobSubmitRequest submission) throws IOException, InterruptedException {
		return submitPlugin("/api/jobs/fractal", submission);
	}

	public String submitOcr(OcrJobSubmitRequest submission) throws IOException, InterruptedException {
		return submitPlugin("/api/jobs/ocr", submission);
	}

	public String submitBlender(BlenderJobSubmitRequest submission) throws IOException, InterruptedException {
		return submitPlugin("/api/jobs/blender", submission);
	}

	private String submitPlugin(String path, Object submission) throws IOException, InterruptedException {
		byte[] body = json.writeValueAsBytes(submission);
		HttpRequest request = HttpRequest.newBuilder(server.resolve(path)).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		requireStatus(response, 202);
		return json.readValue(response.body(), JobSubmission.class).jobId();
	}

	private String submit(JobSubmitRequest submission) throws IOException, InterruptedException {
		byte[] body = json.writeValueAsBytes(submission);
		HttpRequest request = HttpRequest.newBuilder(server.resolve("/api/jobs")).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		requireStatus(response, 202);
		return json.readValue(response.body(), JobSubmission.class).jobId();
	}

	public JobStatusResponse status(String jobId) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(server.resolve("/api/jobs/" + jobId))
				.timeout(Duration.ofSeconds(10)).GET().build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		requireStatus(response, 200);
		return json.readValue(response.body(), JobStatusResponse.class);
	}

	public URI dashboard(String jobId) {
		return server.resolve("/dashboard/jobs/" + jobId);
	}

	public JobStatusResponse waitForCompletion(String jobId, long pollMillis) throws IOException, InterruptedException {
		while (true) {
			JobStatusResponse current = status(jobId);
			System.out.printf("Job %s: %s %d%%%n", jobId, current.state(), current.progress());
			if (java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(current.state())) {
				return current;
			}
			Thread.sleep(pollMillis);
		}
	}

	private static void requireStatus(HttpResponse<byte[]> response, int expected) throws IOException {
		if (response.statusCode() != expected) {
			throw new IOException("Server returned HTTP " + response.statusCode() + ": "
					+ new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
		}
	}
}
