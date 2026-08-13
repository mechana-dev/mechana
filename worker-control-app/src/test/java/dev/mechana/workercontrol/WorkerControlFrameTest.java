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
package dev.mechana.workercontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class WorkerControlFrameTest {
	@Test
	void rendersPortsWithoutThousandsSeparators() throws Exception {
		AtomicReference<String> text = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			JSpinner port = new JSpinner(new SpinnerNumberModel(21012, 1, 65535, 1));
			WorkerControlFrame.usePlainIntegerFormat(port);
			JFormattedTextField field = ((JSpinner.DefaultEditor) port.getEditor()).getTextField();
			text.set(field.getText());
		});
		assertEquals("21012", text.get());
	}

	@Test
	void switchingHostsRestoresSavedProfileAndSeedsUnknownSelection() {
		SettingsStore.HostSettings customized = new SettingsStore.HostSettings(8790, "token", 3,
				AgentClient.LaunchMode.SANDBOXED, "sleep", "custom", 2222, "", false, "http://127.0.0.1:8787",
				"~/.mechana/host-agent", "agent.jar", "worker.jar", "~/.mechana/sandbox", "sandbox.exe");
		Map<String, SettingsStore.HostSettings> profiles = new HashMap<>();
		profiles.put("hyperion", customized);

		assertEquals(customized, WorkerControlFrame.profileForSelectedHost(profiles, "hyperion"));
		SettingsStore.HostSettings rocinante = WorkerControlFrame.profileForSelectedHost(profiles, "rocinante");
		assertEquals("markvita", rocinante.sshUser());
		assertEquals(21012, rocinante.sshPort());
		assertEquals("", rocinante.capabilities());
	}
}
