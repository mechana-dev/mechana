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

import dev.mechana.api.ArtifactReference;
import dev.mechana.api.ArtifactStore;
import dev.mechana.api.StorageSelection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Default artifact store preserving Mechana's existing server-owned storage topology. */
public final class ServerLocalArtifactStore implements ArtifactStore {

	private final Path root;

	public ServerLocalArtifactStore(Path root) throws IOException {
		this.root = root.toAbsolutePath().normalize();
		Files.createDirectories(this.root);
	}

	@Override
	public String providerId() {
		return StorageSelection.SERVER_LOCAL;
	}

	@Override
	public ArtifactReference put(String key, InputStream content) throws IOException {
		Path destination = resolve(key);
		Files.createDirectories(destination.getParent());
		Path temporary = Files.createTempFile(destination.getParent(), ".upload-", ".tmp");
		try {
			MessageDigest digest = sha256();
			try (DigestInputStream verified = new DigestInputStream(content, digest)) {
				Files.copy(verified, temporary, StandardCopyOption.REPLACE_EXISTING);
			}
			long size = Files.size(temporary);
			Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return new ArtifactReference(providerId(), key, size, HexFormat.of().formatHex(digest.digest()));
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	@Override
	public InputStream open(ArtifactReference artifact) throws IOException {
		requireProvider(artifact);
		return Files.newInputStream(resolve(artifact.key()));
	}

	@Override
	public boolean exists(ArtifactReference artifact) throws IOException {
		requireProvider(artifact);
		return Files.isRegularFile(resolve(artifact.key()));
	}

	@Override
	public void delete(ArtifactReference artifact) throws IOException {
		requireProvider(artifact);
		Files.deleteIfExists(resolve(artifact.key()));
	}

	private Path resolve(String key) {
		Path resolved = root.resolve(key).normalize();
		if (!resolved.startsWith(root))
			throw new IllegalArgumentException("Artifact key escapes storage root: " + key);
		return resolved;
	}

	private void requireProvider(ArtifactReference artifact) {
		if (!providerId().equals(artifact.providerId()))
			throw new IllegalArgumentException("Artifact belongs to provider " + artifact.providerId());
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 unavailable", impossible);
		}
	}
}
