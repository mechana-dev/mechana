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

import dev.mechana.plugins.audio.WavFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Durable user-visible library of factory, imported, and generated IRs. */
final class IrProfileLibrary {
	record Profile(String name, Path path, boolean factory) {
		@Override
		public String toString() {
			return name;
		}
	}

	private final Path directory;
	private final Path factoryDirectory;

	IrProfileLibrary() {
		this(Path.of(System.getProperty("user.home"), "Library", "Application Support", "Mechana Reverb",
				"IR Profiles"), BundledProfiles.directory());
	}

	IrProfileLibrary(Path directory, Path factoryDirectory) {
		this.directory = Objects.requireNonNull(directory, "directory");
		this.factoryDirectory = factoryDirectory;
	}

	List<Profile> profiles() throws IOException {
		installFactoryProfiles();
		try (var entries = Files.list(directory)) {
			return entries.filter(Files::isRegularFile).filter(IrProfileLibrary::wav).sorted()
					.map(path -> new Profile(displayName(path), path.toAbsolutePath().normalize(), isFactory(path)))
					.toList();
		}
	}

	Profile add(Path source) throws IOException {
		return copy(source, Objects.requireNonNull(source.getFileName(), "IR filename").toString());
	}

	Profile addGenerated(Path source) throws IOException {
		return add(source);
	}

	Profile addGenerated(Path source, String requestedName) throws IOException {
		return addGenerated(source, requestedName, false);
	}

	Profile addGenerated(Path source, String requestedName, boolean replace) throws IOException {
		validate(source);
		Files.createDirectories(directory);
		Path destination = requestedDestination(requestedName);
		if (replace) {
			if (isFactory(destination))
				throw new IOException("Factory IR profiles cannot be replaced");
			Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		} else {
			destination = uniqueDestination(requestedName);
			Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
		}
		return new Profile(displayName(destination), destination.toAbsolutePath().normalize(), false);
	}

	boolean containsName(String requestedName) throws IOException {
		installFactoryProfiles();
		return Files.isRegularFile(requestedDestination(requestedName));
	}

	boolean isFactoryName(String requestedName) throws IOException {
		installFactoryProfiles();
		return isFactory(requestedDestination(requestedName));
	}

	private Profile copy(Path source, String requestedName) throws IOException {
		validate(source);
		Files.createDirectories(directory);
		Path destination = uniqueDestination(requestedName);
		Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
		return new Profile(displayName(destination), destination.toAbsolutePath().normalize(), false);
	}

	void remove(Profile profile) throws IOException {
		Path normalized = editableProfile(profile);
		Files.deleteIfExists(normalized);
		Files.deleteIfExists(reportPath(normalized));
	}

	Profile rename(Profile profile, String requestedName) throws IOException {
		Path source = editableProfile(profile);
		Path destination = requestedDestination(requestedName).toAbsolutePath().normalize();
		if (source.equals(destination))
			return profile;
		if (Files.exists(destination))
			throw new IOException("An IR profile with that name already exists");
		Files.move(source, destination);
		Path sourceReport = reportPath(source);
		if (Files.exists(sourceReport))
			Files.move(sourceReport, reportPath(destination));
		return new Profile(displayName(destination), destination, false);
	}

	Path directory() throws IOException {
		Files.createDirectories(directory);
		return directory;
	}

	private void installFactoryProfiles() throws IOException {
		Files.createDirectories(directory);
		if (factoryDirectory == null || !Files.isDirectory(factoryDirectory))
			return;
		try (var profiles = Files.list(factoryDirectory)) {
			for (Path source : profiles.filter(Files::isRegularFile).filter(IrProfileLibrary::wav).toList()) {
				Path destination = directory
						.resolve(Objects.requireNonNull(source.getFileName(), "factory IR filename"));
				if (!Files.exists(destination))
					Files.copy(source, destination);
			}
		}
	}

	private boolean isFactory(Path profile) {
		return factoryDirectory != null && Files
				.isRegularFile(factoryDirectory.resolve(Objects.requireNonNull(profile.getFileName(), "IR filename")));
	}

	private Path uniqueDestination(String requestedName) {
		Path candidate = requestedDestination(requestedName);
		String safeName = Objects.requireNonNull(candidate.getFileName(), "IR destination filename").toString();
		String stem = safeName.replaceFirst("(?i)\\.wav$", "");
		for (int suffix = 2; Files.exists(candidate); suffix++)
			candidate = directory.resolve(stem + "-" + suffix + ".wav");
		return candidate;
	}

	private Path requestedDestination(String requestedName) {
		String cleaned = Objects.requireNonNull(requestedName, "requestedName").strip()
				.replaceAll("[\\\\/:*?\"<>|]+", "-").replaceAll("^\\.+", "");
		if (cleaned.isBlank())
			cleaned = "captured-reverb";
		String safeName = cleaned.toLowerCase(Locale.ROOT).endsWith(".wav") ? cleaned : cleaned + ".wav";
		return directory.resolve(safeName);
	}

	private Path editableProfile(Profile profile) throws IOException {
		Objects.requireNonNull(profile, "profile");
		if (profile.factory())
			throw new IOException("Factory IR profiles cannot be changed");
		Path normalized = profile.path().toAbsolutePath().normalize();
		if (!directory.toAbsolutePath().normalize().equals(normalized.getParent()))
			throw new IOException("IR profile is outside the application library");
		return normalized;
	}

	private static Path reportPath(Path profile) {
		return profile.resolveSibling(Objects.requireNonNull(profile.getFileName(), "IR filename") + ".txt");
	}

	private static void validate(Path source) throws IOException {
		if (source == null || !Files.isRegularFile(source) || !wav(source))
			throw new IOException("Choose a readable WAV impulse response");
		try (WavFile.Reader reader = WavFile.open(source)) {
			if (reader.format().channels() > 2 || reader.format().frames() < 1)
				throw new IOException("IR profiles must contain mono or stereo WAV audio");
		}
	}

	private static boolean wav(Path path) {
		return Objects.requireNonNull(path.getFileName(), "IR filename").toString().toLowerCase(Locale.ROOT)
				.endsWith(".wav");
	}

	private static String displayName(Path path) {
		String stem = Objects.requireNonNull(path.getFileName(), "IR filename").toString()
				.replaceFirst("(?i)\\.wav$", "").replace('-', ' ').replace('_', ' ');
		StringBuilder result = new StringBuilder();
		for (String word : stem.strip().split("\\s+")) {
			if (!result.isEmpty())
				result.append(' ');
			result.append(word.length() <= 3
					? word.toUpperCase(Locale.ROOT)
					: Character.toUpperCase(word.charAt(0)) + word.substring(1));
		}
		return result.toString();
	}
}
