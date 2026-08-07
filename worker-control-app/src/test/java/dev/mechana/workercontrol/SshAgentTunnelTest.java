/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS.
 */
package dev.mechana.workercontrol;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SshAgentTunnelTest {
	@Test
	void forwardsOnlyLoopbackThroughAuthenticatedSsh() {
		var request = new SshProvisioner.Request("hyperion", "markf", 22, null, true, Path.of("agent.jar"),
				Path.of("worker.jar"), "remote", "http://coordinator", 8790, "token", "sleep", "sleep", "sandbox",
				Path.of("launcher.exe"));
		List<String> command = SshAgentTunnel.command(request, 43123);
		assertTrue(command.contains("ExitOnForwardFailure=yes"));
		assertTrue(command.contains("127.0.0.1:43123:127.0.0.1:8790"));
		assertTrue(command.contains("markf@hyperion"));
	}
}
