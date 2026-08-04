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

package dev.mechana.hostagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@FunctionalInterface
interface ProcessLauncher {
	ManagedProcess launch(List<String> command, Path workingDirectory, Path logFile) throws IOException;

	static ProcessLauncher system() {
		return (command, directory, log) -> {
			Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
			return new ManagedProcess() {
				public long pid() {
					return process.pid();
				}
				public boolean isAlive() {
					return process.isAlive();
				}
				public void destroy() {
					process.destroy();
				}
				public void destroyForcibly() {
					process.destroyForcibly();
				}
				public boolean waitFor(java.time.Duration timeout) throws InterruptedException {
					return process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
				}
			};
		};
	}
}
