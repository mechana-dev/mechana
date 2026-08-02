package dev.mechana.plugins.fractal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mechana.api.TaskContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FractalTaskPluginTest {
	@TempDir
	Path temporary;

	@Test
	void rendersDeterministicBatchAndAssemblesCollection() throws Exception {
		List<Path> artifacts = new ArrayList<>();
		TaskContext context = new TaskContext() {
			@Override
			public long durationMillis() {
				return 1;
			}

			@Override
			public Map<String, String> parameters() {
				return Map.of("startIndex", "0", "imageCount", "2", "width", "96", "height", "64", "maxIterations",
						"80", "seed", "42", "batchIndex", "0");
			}

			@Override
			public void publishArtifact(String name, Path file) {
				try {
					Path copy = temporary.resolve(name);
					Files.copy(file, copy);
					artifacts.add(copy);
				} catch (java.io.IOException failure) {
					throw new IllegalStateException(failure);
				}
			}

			@Override
			public void reportProgress(int percent) {
				assertTrue(percent >= 0 && percent <= 100);
			}

			@Override
			public boolean isCancellationRequested() {
				return false;
			}
		};

		new FractalTaskPlugin().execute(context);
		assertEquals(1, artifacts.size());
		var result = new FractalCollectionAssembler().assemble(artifacts, temporary.resolve("result"), 2, 96, 64, 80,
				42);
		assertTrue(Files.size(result.collection()) > 0);
		assertTrue(Files.size(result.contactSheet()) > 0);
		assertEquals(2, result.images().size());
	}
}
