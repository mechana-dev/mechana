package dev.mechana.api;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class JobIdTest {

	@Test
	void createsDistinctIdentifiers() {
		JobId first = JobId.random();
		JobId second = JobId.random();

		assertNotNull(first.value());
		assertNotEquals(first, second);
	}
}
