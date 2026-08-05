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
package dev.mechana.runtime.plugin;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;

/** Process-owned attempt workspace with crash-recovery metadata and locking. */
public final class OwnedAttemptWorkspace implements AutoCloseable {
	private static final String LOCK_FILE = ".owner.lock";
	private static final String METADATA_FILE = ".owner";
	private final AttemptWorkspace workspace;
	private final FileChannel channel;
	private final FileLock lock;

	private OwnedAttemptWorkspace(AttemptWorkspace workspace, FileChannel channel, FileLock lock) {
		this.workspace = workspace;
		this.channel = channel;
		this.lock = lock;
	}

	public static OwnedAttemptWorkspace create(Path sandboxRoot, String jobId, String attemptId, String workerId)
			throws IOException {
		AttemptWorkspace workspace = AttemptWorkspace.create(sandboxRoot, jobId, attemptId);
		FileChannel channel = FileChannel.open(workspace.root().resolve(LOCK_FILE), StandardOpenOption.CREATE,
				StandardOpenOption.WRITE);
		try {
			FileLock lock = channel.lock();
			Files.writeString(workspace.root().resolve(METADATA_FILE), metadata(workerId, jobId, attemptId),
					StandardCharsets.UTF_8);
			return new OwnedAttemptWorkspace(workspace, channel, lock);
		} catch (IOException | RuntimeException failure) {
			channel.close();
			throw failure;
		}
	}

	public AttemptWorkspace workspace() {
		return workspace;
	}

	/** Deletes unlocked attempt directories carrying ownership metadata. */
	public static int reclaimAbandoned(Path sandboxRoot) throws IOException {
		Path root = sandboxRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(root))
			return 0;
		int reclaimed = 0;
		try (var jobs = Files.list(root)) {
			for (Path job : jobs.filter(Files::isDirectory).toList()) {
				try (var attempts = Files.list(job)) {
					for (Path attempt : attempts.filter(Files::isDirectory).toList()) {
						if (reclaim(attempt))
							reclaimed++;
					}
				}
				deleteIfEmpty(job);
			}
		}
		return reclaimed;
	}

	private static boolean reclaim(Path attempt) throws IOException {
		if (!Files.isRegularFile(attempt.resolve(METADATA_FILE)))
			return false;
		try (FileChannel channel = FileChannel.open(attempt.resolve(LOCK_FILE), StandardOpenOption.CREATE,
				StandardOpenOption.WRITE)) {
			FileLock lock;
			try {
				lock = channel.tryLock();
			} catch (OverlappingFileLockException activeInThisProcess) {
				return false;
			}
			if (lock == null)
				return false;
			try (lock) {
				deleteTree(attempt);
			}
			return true;
		}
	}

	@Override
	public void close() throws IOException {
		try {
			lock.release();
		} finally {
			channel.close();
		}
		deleteTree(workspace.root());
		deleteIfEmpty(workspace.root().getParent());
	}

	private static String metadata(String workerId, String jobId, String attemptId) {
		return "worker=" + safe(workerId) + "\npid=" + ProcessHandle.current().pid() + "\njob=" + safe(jobId)
				+ "\nattempt=" + safe(attemptId) + "\ncreated=" + Instant.now() + "\n";
	}

	private static String safe(String value) {
		return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static void deleteTree(Path root) throws IOException {
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}

	private static void deleteIfEmpty(Path directory) throws IOException {
		if (directory == null || !Files.isDirectory(directory))
			return;
		try (var entries = Files.list(directory)) {
			if (entries.findAny().isEmpty())
				Files.deleteIfExists(directory);
		}
	}
}
