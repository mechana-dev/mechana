/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.mechana.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClientVideoDataPlaneTest {
	@Test
	void removesBonjourSuffixFromAutomaticFleetHost() {
		assertEquals("Marks-MacBook-Air-M4", ClientVideoDataPlane.automaticHost("Marks-MacBook-Air-M4.local"));
		assertEquals("mechana-client", ClientVideoDataPlane.automaticHost("mechana-client"));
	}
}
