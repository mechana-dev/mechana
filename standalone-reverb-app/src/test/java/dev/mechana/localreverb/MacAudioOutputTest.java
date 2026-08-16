/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package dev.mechana.localreverb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class MacAudioOutputTest {
	@Test
	void parsesNativeCoreAudioDeviceList() {
		var devices = MacAudioOutput.parseDeviceList(
				"BuiltInSpeakerDevice\tMacBook Air Speakers\nAppleTV-uid\tLiving Room Apple TV\ninvalid\n");

		assertEquals(2, devices.size());
		assertEquals("AppleTV-uid", devices.get(1).uid());
		assertEquals("Living Room Apple TV", devices.get(1).name());
		assertFalse(devices.get(1).javaCompatibility());
	}
}
