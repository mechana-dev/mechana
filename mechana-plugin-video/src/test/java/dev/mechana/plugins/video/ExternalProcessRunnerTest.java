package dev.mechana.plugins.video;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalProcessRunnerTest {
	@TempDir
	Path temp;

	@Test
	void capturesOutputWithoutFfmpeg() throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		var result = new ExternalProcessRunner().run(List.of(java, "-version"), Duration.ofSeconds(10),
				CancellationToken.NEVER, ignored -> {
				});
		assertEquals(0, result.exitCode());
		assertTrue(result.stderr().contains("version"));
	}

	@Test
	void enforcesTimeoutAndTerminatesChild() throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Path source = temp.resolve("Sleeper.java");
		Files.writeString(source,
				"public class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(30000); } }");
		assertThrows(ExternalProcessRunner.ProcessTimeoutException.class, () -> new ExternalProcessRunner()
				.run(List.of(java, source.toString()), Duration.ofMillis(100), CancellationToken.NEVER, ignored -> {
				}));
	}
}
