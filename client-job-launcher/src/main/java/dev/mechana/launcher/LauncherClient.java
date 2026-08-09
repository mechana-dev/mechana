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

package dev.mechana.launcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mechana.protocol.Messages.JobLauncherDescriptor;
import dev.mechana.protocol.Messages.JobSubmission;
import dev.mechana.protocol.Messages.LauncherJob;
import dev.mechana.protocol.Messages.ArtifactReference;
import dev.mechana.protocol.Messages.ClientAssemblyCompletion;
import dev.mechana.protocol.Messages.VideoAssemblyManifest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class LauncherClient {
	private final HttpClient http;
	private final ObjectMapper json;

	LauncherClient() {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper());
	}

	LauncherClient(HttpClient http, ObjectMapper json) {
		this.http = http;
		this.json = json;
	}

	List<JobLauncherDescriptor> capabilities(URI server) throws IOException, InterruptedException {
		return read(server.resolve("/api/client/capabilities"), new TypeReference<>() {
		});
	}

	List<LauncherJob> jobs(URI server) throws IOException, InterruptedException {
		return read(server.resolve("/api/client/jobs"), new TypeReference<>() {
		});
	}

	String submit(URI server, JobLauncherDescriptor descriptor, Map<String, Object> values)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(server.resolve(descriptor.submitPath()))
				.timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(values))).build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		requireSuccess(response);
		return json.readValue(response.body(), JobSubmission.class).jobId();
	}

	void uploadVideoInput(URI server, String token, Path source) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(server.resolve("/api/client/video-inputs/" + token))
				.timeout(Duration.ofHours(1)).PUT(HttpRequest.BodyPublishers.ofFile(source)).build();
		requireSuccess(http.send(request, HttpResponse.BodyHandlers.ofByteArray()));
	}

	VideoAssemblyManifest videoAssemblyManifest(URI server, String jobId) throws IOException, InterruptedException {
		return read(server.resolve("/api/jobs/" + jobId + "/client-assembly"), new TypeReference<>() {
		});
	}

	void downloadArtifact(URI server, ArtifactReference artifact, Path destination)
			throws IOException, InterruptedException {
		HttpResponse<Path> response = http.send(
				HttpRequest.newBuilder(server.resolve(artifact.url())).timeout(Duration.ofHours(1)).build(),
				HttpResponse.BodyHandlers.ofFile(destination));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			Files.deleteIfExists(destination);
			throw new IOException(
					"Server returned HTTP " + response.statusCode() + " while downloading " + artifact.key());
		}
	}

	void completeClientAssembly(URI server, String jobId, ClientAssemblyCompletion completion)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(server.resolve("/api/jobs/" + jobId + "/client-assembly/complete"))
				.timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(completion))).build();
		requireSuccess(http.send(request, HttpResponse.BodyHandlers.ofByteArray()));
	}

	void abort(URI server, String jobId) throws IOException, InterruptedException {
		mutate(HttpRequest.newBuilder(server.resolve("/api/jobs/" + jobId + "/abort"))
				.POST(HttpRequest.BodyPublishers.noBody()).build());
	}

	void purge(URI server, String jobId) throws IOException, InterruptedException {
		mutate(HttpRequest.newBuilder(server.resolve("/api/jobs/" + jobId)).DELETE().build());
	}

	void purgeAll(URI server) throws IOException, InterruptedException {
		mutate(HttpRequest.newBuilder(server.resolve("/api/jobs/completed")).DELETE().build());
	}

	private <T> T read(URI uri, TypeReference<T> type) throws IOException, InterruptedException {
		HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build(),
				HttpResponse.BodyHandlers.ofByteArray());
		requireSuccess(response);
		return json.readValue(response.body(), type);
	}

	private void mutate(HttpRequest request) throws IOException, InterruptedException {
		requireSuccess(http.send(request, HttpResponse.BodyHandlers.ofByteArray()));
	}

	private static void requireSuccess(HttpResponse<byte[]> response) throws IOException {
		if (response.statusCode() < 200 || response.statusCode() >= 300)
			throw new IOException("Server returned HTTP " + response.statusCode() + ": "
					+ new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
	}
}
