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

package dev.mechana.macos;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.awt.Desktop;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.swing.JOptionPane;

/** Dock launcher for the launchd-managed Mechana server. */
public final class ServerAppMain {
	static final String LABEL = "dev.mechana.server";
	static final URI STATUS_URI = URI.create("http://127.0.0.1:8787/api/dashboard");
	static final URI DASHBOARD_URI = URI.create("http://127.0.0.1:8787/dashboard");

	private ServerAppMain() {
	}

	public static void main(String[] args) {
		try {
			if (!isReady()) {
				installAndStartAgent(appBundle(), Path.of(System.getProperty("user.home")));
				waitUntilReady(Duration.ofSeconds(30));
			}
			if (shouldOpenBrowser(args)) {
				Desktop.getDesktop().browse(DASHBOARD_URI);
			}
		} catch (IOException | InterruptedException | RuntimeException failure) {
			if (failure instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			JOptionPane.showMessageDialog(null,
					"Mechana Server could not be started.\n\n" + failure.getMessage()
							+ "\n\nSee ~/.mechana/logs/server-error.log for details.",
					"Mechana Server", JOptionPane.ERROR_MESSAGE);
		}
	}

	static boolean shouldOpenBrowser(String[] args) {
		return !List.of(args).contains("--start-only");
	}

	static Path appBundle() {
		String executable = System.getProperty("jpackage.app-path");
		if (executable == null || executable.isBlank()) {
			throw new IllegalStateException("This launcher must run from its packaged macOS app.");
		}
		Path appExecutable = Path.of(executable).toAbsolutePath();
		Path macOsDirectory = Objects.requireNonNull(appExecutable.getParent(), "packaged launcher directory");
		Path contentsDirectory = Objects.requireNonNull(macOsDirectory.getParent(), "packaged Contents directory");
		return Objects.requireNonNull(contentsDirectory.getParent(), "packaged app bundle");
	}

	static boolean isReady() {
		try {
			HttpURLConnection connection = (HttpURLConnection) STATUS_URI.toURL().openConnection();
			connection.setConnectTimeout(1_000);
			connection.setReadTimeout(1_000);
			connection.setRequestMethod("GET");
			return connection.getResponseCode() == 200;
		} catch (IOException unavailable) {
			return false;
		}
	}

	static void installAndStartAgent(Path bundle, Path home) throws IOException, InterruptedException {
		Path agents = home.resolve("Library/LaunchAgents");
		Path logs = home.resolve(".mechana/logs");
		Files.createDirectories(agents);
		Files.createDirectories(logs);
		Path plist = agents.resolve(LABEL + ".plist");
		Files.writeString(plist, launchAgentPlist(bundle, home), StandardCharsets.UTF_8);

		String domain = "gui/" + runAndRead(List.of("/usr/bin/id", "-u"));
		runAllowingFailure(List.of("/bin/launchctl", "bootout", domain + "/" + LABEL));
		runRequired(List.of("/bin/launchctl", "bootstrap", domain, plist.toString()));
		runRequired(List.of("/bin/launchctl", "kickstart", domain + "/" + LABEL));
	}

	private static long runAndRead(List<String> command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			if (process.waitFor() != 0) {
				throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
			}
			return Long.parseLong(output);
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(failure);
		} catch (IOException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@SuppressFBWarnings(value = "VA_FORMAT_STRING_USES_NEWLINE", justification = "The generated XML plist deliberately uses LF line endings")
	static String launchAgentPlist(Path bundle, Path home) {
		Path contents = bundle.resolve("Contents");
		Path daemon = contents.resolve("MacOS/Mechana Server Daemon");
		Path data = existingDataDirectory(home);
		Path logs = home.resolve(".mechana/logs");
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
				<plist version="1.0"><dict>
				<key>Label</key><string>%s</string>
				<key>ProgramArguments</key><array>
				<string>%s</string><string>8787</string>
				<string>http://marks-macbook-air-m4:8787</string><string>%s</string>
				</array>
				<key>WorkingDirectory</key><string>%s</string>
				<key>KeepAlive</key><true/><key>RunAtLoad</key><true/>
				<key>StandardOutPath</key><string>%s</string>
				<key>StandardErrorPath</key><string>%s</string>
				<key>ProcessType</key><string>Background</string>
				</dict></plist>
				""".formatted(LABEL, xml(daemon), xml(data), xml(home), xml(logs.resolve("server.log")),
				xml(logs.resolve("server-error.log")));
	}

	static Path existingDataDirectory(Path home) {
		Path priorDesktopLauncherData = home.resolve("Projects/mechana/.mechana/server");
		return Files.exists(priorDesktopLauncherData) ? priorDesktopLauncherData : home.resolve(".mechana/server");
	}

	private static String xml(Path path) {
		return path.toAbsolutePath().normalize().toString().replace("&", "&amp;").replace("<", "&lt;")
				.replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
	}

	private static void waitUntilReady(Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (isReady()) {
				return;
			}
			Thread.sleep(250);
		}
		throw new IllegalStateException("The server did not become ready within " + timeout.toSeconds() + " seconds.");
	}

	private static void runRequired(List<String> command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		if (process.waitFor() != 0) {
			throw new IOException(String.join(" ", command) + " failed: " + output);
		}
	}

	private static void runAllowingFailure(List<String> command) throws IOException, InterruptedException {
		new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
	}
}
