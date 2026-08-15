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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import dev.mechana.plugins.audio.ImpulseResponseCalibration;
import dev.mechana.plugins.audio.WavFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Durable user-visible library of factory, imported, and generated IRs. */
final class IrProfileLibrary {
	record Profile(String name, Path path, boolean factory, double calibrationGain) {
		Profile(String name, Path path, boolean factory) {
			this(name, path, factory, 1);
		}
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
			List<Profile> result = new java.util.ArrayList<>();
			for (Path path : entries.filter(Files::isRegularFile).filter(IrProfileLibrary::wav).sorted().toList())
				result.add(calibrateProfile(path, isFactory(path)));
			return List.copyOf(result);
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
		return calibrateProfile(destination, false);
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
		return calibrateProfile(destination, false);
	}

	void remove(Profile profile) throws IOException {
		Path normalized = editableProfile(profile);
		Files.deleteIfExists(normalized);
		Files.deleteIfExists(reportPath(normalized));
		Files.deleteIfExists(calibrationPath(normalized));
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
		Path sourceCalibration = calibrationPath(source);
		if (Files.exists(sourceCalibration))
			Files.move(sourceCalibration, calibrationPath(destination));
		return calibrateProfile(destination, false);
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
				if (!Files.exists(destination) || !sha256(source).equals(sha256(destination))) {
					Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
					Files.deleteIfExists(calibrationPath(destination));
				}
				ensureCalibration(destination);
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

	private static Path calibrationPath(Path profile) {
		return profile.resolveSibling(
				Objects.requireNonNull(profile.getFileName(), "IR filename") + ".calibration.properties");
	}

	private Profile calibrateProfile(Path path, boolean factory) throws IOException {
		Calibration calibration = ensureCalibration(path);
		return new Profile(displayName(path), path.toAbsolutePath().normalize(), factory, calibration.gain());
	}

	@SuppressFBWarnings(value = "VA_FORMAT_STRING_USES_NEWLINE", justification = "Properties metadata intentionally uses LF on every platform")
	private Calibration ensureCalibration(Path path) throws IOException {
		String hash = sha256(path);
		Path metadata = calibrationPath(path);
		if (Files.isRegularFile(metadata)) {
			java.util.Properties properties = new java.util.Properties();
			try (var input = Files.newInputStream(metadata)) {
				properties.load(input);
			}
			if (hash.equals(properties.getProperty("sourceSha256")))
				try {
					return new Calibration(Double.parseDouble(properties.getProperty("gain")));
				} catch (NumberFormatException ignored) {
					// Rebuild malformed or obsolete calibration metadata.
				}
		}
		ImpulseResponseCalibration.Result result = ImpulseResponseCalibration.analyze(path);
		String contents = """
				schemaVersion=1
				method=stereo-linked-energy-v1
				sourceSha256=%s
				gain=%s
				gainDecibels=%s
				measuredEnergy=%s
				measuredPeak=%s
				boostLimited=%s
				calibratedAt=%s
				""".formatted(hash, result.gain(), result.gainDecibels(), result.energy(), result.peak(),
				result.boostLimited(), Instant.now());
		Path temporary = metadata.resolveSibling(metadata.getFileName() + ".tmp");
		Files.writeString(temporary, contents);
		Files.move(temporary, metadata, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return new Calibration(result.gain());
	}

	private static String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (var input = Files.newInputStream(path)) {
				byte[] buffer = new byte[16_384];
				for (int count; (count = input.read(buffer)) >= 0;)
					digest.update(buffer, 0, count);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 unavailable", impossible);
		}
	}

	private record Calibration(double gain) {
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
