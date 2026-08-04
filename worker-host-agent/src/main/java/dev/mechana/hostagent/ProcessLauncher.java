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
