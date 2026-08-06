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

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;

final class WorkerControlFrame extends JFrame {
	private static final long serialVersionUID = 1L;
	private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
	private static final String SANDBOXED_PLUGINS = "sleep,video-ffmpeg,fractal-render,ocr-tesseract,blender-render";
	private final transient AgentClient client;
	private final transient SettingsStore store;
	private final transient SshProvisioner provisioner;
	private final JComboBox<String> host = new JComboBox<>();
	private final JSpinner port = new JSpinner(new SpinnerNumberModel(8790, 1, 65535, 1));
	private final JPasswordField token = new JPasswordField(16);
	private final JSpinner count = new JSpinner(new SpinnerNumberModel(1, 0, 128, 1));
	private final JComboBox<AgentClient.LaunchMode> launchMode = new JComboBox<>(AgentClient.LaunchMode.values());
	private final JTextField capabilities = new JTextField("fractal-render", 28);
	private final JTextField sshUser = new JTextField(System.getProperty("user.name"), 10);
	private final JSpinner sshPort = new JSpinner(new SpinnerNumberModel(22, 1, 65535, 1));
	private final JTextField identityFile = new JTextField(18);
	private final JCheckBox acceptNewHostKey = new JCheckBox("Accept new host key");
	private final JTextField coordinator = new JTextField("http://127.0.0.1:8787", 20);
	private final JTextField remoteDirectory = new JTextField(".mechana/host-agent", 18);
	private final JTextField agentJar = new JTextField("worker-host-agent/target/mechana-worker-host-agent.jar", 24);
	private final JTextField workerJar = new JTextField("mechana-worker/target/mechana-worker.jar", 24);
	private final JTextField sandboxRoot = new JTextField("/private/tmp/mechana-sandbox", 22);
	private final JLabel state = new JLabel("Not checked");
	private final JTextArea workers = new JTextArea(9, 52);
	private final JButton refresh = new JButton("Refresh");
	private final JButton start = new JButton("Start");
	private final JButton stop = new JButton("Stop all");
	private final JButton deploy = new JButton("Reinstall + start via SSH");
	private final JButton restartAgent = new JButton("Restart agent via SSH");
	private final JButton stopAgent = new JButton("Stop remote agent via SSH");
	private boolean changingHostList;
	private boolean agentReady;
	private boolean busy;
	private long requestGeneration;

	WorkerControlFrame(AgentClient client, SettingsStore store) {
		this(client, store, new SshProvisioner());
	}

	WorkerControlFrame(AgentClient client, SettingsStore store, SshProvisioner provisioner) {
		super("Mechana Worker Control");
		this.client = client;
		this.store = store;
		this.provisioner = provisioner;
		usePlainIntegerFormat(port);
		usePlainIntegerFormat(sshPort);
		host.setEditable(true);
		workers.setEditable(false);
		capabilities.setToolTipText("Comma-separated plugin capabilities allowed by the selected host agent");
		workers.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		JPanel connection = new JPanel(new FlowLayout(FlowLayout.LEFT));
		connection.add(new JLabel("Host"));
		connection.add(host);
		connection.add(new JLabel("Port"));
		connection.add(port);
		connection.add(new JLabel("Token"));
		connection.add(token);
		connection.add(refresh);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
		actions.add(new JLabel("Workers"));
		actions.add(count);
		actions.add(new JLabel("Mode"));
		actions.add(launchMode);
		actions.add(new JLabel("Plugins"));
		actions.add(capabilities);
		actions.add(start);
		actions.add(stop);
		actions.add(state);
		JPanel top = new JPanel(new BorderLayout());
		top.add(connection, BorderLayout.NORTH);
		top.add(actions, BorderLayout.CENTER);
		JPanel provisioning = new JPanel(new java.awt.GridLayout(0, 1));
		JPanel ssh = new JPanel(new FlowLayout(FlowLayout.LEFT));
		ssh.add(new JLabel("SSH user"));
		ssh.add(sshUser);
		ssh.add(new JLabel("SSH port"));
		ssh.add(sshPort);
		ssh.add(new JLabel("Identity"));
		ssh.add(identityFile);
		ssh.add(acceptNewHostKey);
		ssh.add(new JLabel("Coordinator"));
		ssh.add(coordinator);
		JPanel paths = new JPanel(new FlowLayout(FlowLayout.LEFT));
		paths.add(new JLabel("Remote dir"));
		paths.add(remoteDirectory);
		paths.add(new JLabel("Sandbox root"));
		paths.add(sandboxRoot);
		JPanel artifacts = new JPanel(new FlowLayout(FlowLayout.LEFT));
		artifacts.add(new JLabel("Agent JAR"));
		artifacts.add(agentJar);
		artifacts.add(new JLabel("Worker JAR"));
		artifacts.add(workerJar);
		artifacts.add(deploy);
		artifacts.add(restartAgent);
		artifacts.add(stopAgent);
		provisioning.add(ssh);
		provisioning.add(paths);
		provisioning.add(artifacts);
		top.add(provisioning, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);
		add(new JScrollPane(workers), BorderLayout.CENTER);
		refresh.addActionListener(event -> refreshStatus());
		host.addActionListener(event -> {
			if (!changingHostList)
				refreshStatus();
		});
		launchMode.addActionListener(event -> applyModeDefaults());
		start.addActionListener(event -> run("Starting", () -> client.start(baseUri(), tokenValue(),
				(Integer) count.getValue(), selectedMode(), capabilities.getText().strip()), Availability.KEEP));
		stop.addActionListener(event -> run("Stopping", () -> client.stop(baseUri(), tokenValue()), Availability.KEEP));
		deploy.addActionListener(event -> {
			ensureToken();
			run("Reinstalling", () -> {
				provisioner.deploy(provisionRequest());
				waitForAgent();
				return client.start(baseUri(), tokenValue(), (Integer) count.getValue(), selectedMode(),
						capabilities.getText().strip());
			}, Availability.ON_SUCCESS);
		});
		restartAgent.addActionListener(event -> run("Restarting agent", () -> {
			try {
				client.stop(baseUri(), tokenValue());
			} catch (IOException ignored) {
				// SSH can recover an agent that does not answer its HTTP API.
			}
			provisioner.restart(provisionRequest());
			waitForAgent();
			return client.status(baseUri(), tokenValue());
		}, Availability.PROBE));
		stopAgent.addActionListener(event -> run("Stopping remote agent", () -> {
			try {
				client.stop(baseUri(), tokenValue());
			} catch (IOException ignored) {
				// SSH service shutdown remains available when the HTTP agent is unavailable.
			}
			provisioner.stop(provisionRequest());
			return new AgentClient.Status(0, 0, "STOPPED", List.of(), "Remote agent stopped", null, "", "");
		}, Availability.OFF_ON_SUCCESS));
		loadSettings();
		setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		pack();
		setLocationByPlatform(true);
		updateControls();
		SwingUtilities.invokeLater(this::refreshStatus);
	}

	private void refreshStatus() {
		run("Checking", () -> client.status(baseUri(), tokenValue()), Availability.PROBE);
	}

	private void run(String activity, Operation operation, Availability availability) {
		long generation = ++requestGeneration;
		setBusy(true);
		state.setText(activity + "…");
		remember();
		CompletableFuture.supplyAsync(() -> {
			try {
				return operation.run();
			} catch (Exception failure) {
				throw new java.util.concurrent.CompletionException(failure);
			}
		}).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
			if (generation != requestGeneration)
				return;
			if (failure == null) {
				if (availability == Availability.PROBE || availability == Availability.ON_SUCCESS)
					agentReady = true;
				else if (availability == Availability.OFF_ON_SUCCESS)
					agentReady = false;
				show(result);
			} else {
				if (availability == Availability.PROBE)
					agentReady = false;
				showError(failure, availability == Availability.PROBE);
			}
			setBusy(false);
		}));
	}

	private void show(AgentClient.Status status) {
		state.setText((agentReady ? "AGENT ONLINE — " : "AGENT STOPPED — ") + status.state() + " — "
				+ status.runningCount() + " running / " + status.requestedCount() + " requested");
		if (status.launchMode() != null) {
			launchMode.setSelectedItem(status.launchMode());
			capabilities.setText(status.capabilities());
		}
		StringBuilder text = new StringBuilder();
		if (status.launchMode() != null) {
			text.append("Mode: ").append(status.launchMode()).append("   Plugins: ").append(status.capabilities());
			if (status.sandboxRoot() != null && !status.sandboxRoot().isBlank())
				text.append("   Sandbox: ").append(status.sandboxRoot());
			text.append("\n\n");
		}
		for (AgentClient.Worker worker : status.workers())
			text.append(worker.id()).append("   PID ").append(worker.pid()).append("   ")
					.append(worker.alive() ? "RUNNING" : "STOPPED").append("   since ").append(worker.startedAt())
					.append('\n');
		if (!status.diagnostic().isBlank())
			text.append("Diagnostic: ").append(status.diagnostic());
		workers.setText(text.toString());
	}

	private void showError(Throwable failure, boolean agentUnavailable) {
		Throwable cause = failure.getCause() == null ? failure : failure.getCause();
		if (agentUnavailable && cause instanceof AgentClient.AgentResponseException response)
			state.setText("AGENT DETECTED — HTTP " + response.statusCode() + " — CONTROLS LOCKED");
		else
			state.setText(agentUnavailable ? "AGENT UNAVAILABLE" : "ERROR");
		workers.setText(cause.getMessage());
	}
	private URI baseUri() {
		return URI.create("http://" + hostValue() + ":" + port.getValue());
	}
	private String hostValue() {
		return String.valueOf(host.getEditor().getItem()).trim();
	}
	private String tokenValue() {
		return new String(token.getPassword());
	}
	private AgentClient.LaunchMode selectedMode() {
		return (AgentClient.LaunchMode) launchMode.getSelectedItem();
	}
	private void applyModeDefaults() {
		boolean sandboxed = selectedMode() == AgentClient.LaunchMode.SANDBOXED;
		if (sandboxed)
			capabilities.setText("fractal-render");
	}
	private void setBusy(boolean busy) {
		this.busy = busy;
		updateControls();
	}
	private void updateControls() {
		refresh.setEnabled(!busy);
		start.setEnabled(!busy && agentReady);
		stop.setEnabled(!busy && agentReady);
		deploy.setEnabled(!busy);
		restartAgent.setEnabled(!busy);
		stopAgent.setEnabled(!busy);
	}

	private void loadSettings() {
		changingHostList = true;
		try {
			SettingsStore.Settings s = store.load();
			s.hosts().forEach(host::addItem);
			host.setSelectedItem(s.lastHost());
			port.setValue(s.port());
			token.setText(s.token());
			count.setValue(s.count());
			launchMode.setSelectedItem(s.launchMode());
			capabilities.setText(s.capabilities());
			sshUser.setText(s.sshUser());
			sshPort.setValue(s.sshPort());
			identityFile.setText(s.identityFile());
			acceptNewHostKey.setSelected(s.acceptNewHostKey());
			coordinator.setText(s.coordinator());
			remoteDirectory.setText(s.remoteDirectory());
			agentJar.setText(s.agentJar());
			workerJar.setText(s.workerJar());
			sandboxRoot.setText(s.sandboxRoot());
			applyModeDefaults();
		} catch (Exception failure) {
			workers.setText("Could not load settings: " + failure.getMessage());
		} finally {
			changingHostList = false;
		}
	}
	private void remember() {
		try {
			ArrayList<String> hosts = new ArrayList<>();
			for (int i = 0; i < host.getItemCount(); i++)
				hosts.add(host.getItemAt(i));
			if (!hosts.contains(hostValue())) {
				hosts.add(hostValue());
				changingHostList = true;
				try {
					host.addItem(hostValue());
				} finally {
					changingHostList = false;
				}
			}
			store.save(new SettingsStore.Settings(hosts, hostValue(), (Integer) port.getValue(), tokenValue(),
					(Integer) count.getValue(), selectedMode(), capabilities.getText().strip(),
					sshUser.getText().strip(), (Integer) sshPort.getValue(), identityFile.getText().strip(),
					acceptNewHostKey.isSelected(), coordinator.getText().strip(), remoteDirectory.getText().strip(),
					agentJar.getText().strip(), workerJar.getText().strip(), sandboxRoot.getText().strip()));
		} catch (IOException failure) {
			workers.setText("Could not save settings: " + failure.getMessage());
		}
	}

	private SshProvisioner.Request provisionRequest() {
		String identity = identityFile.getText().strip();
		return new SshProvisioner.Request(hostValue(), sshUser.getText().strip(), (Integer) sshPort.getValue(),
				identity.isBlank() ? null : Path.of(identity), acceptNewHostKey.isSelected(),
				Path.of(agentJar.getText().strip()), Path.of(workerJar.getText().strip()),
				remoteDirectory.getText().strip(), coordinator.getText().strip(), (Integer) port.getValue(),
				tokenValue(), SANDBOXED_PLUGINS, SANDBOXED_PLUGINS, sandboxRoot.getText().strip());
	}

	private void waitForAgent() throws IOException, InterruptedException {
		IOException last = null;
		for (int attempt = 0; attempt < 20; attempt++) {
			try {
				client.status(baseUri(), tokenValue());
				return;
			} catch (IOException unavailable) {
				last = unavailable;
				Thread.sleep(500);
			}
		}
		throw new IOException("Installed agent did not become reachable: " + last.getMessage(), last);
	}

	private void ensureToken() {
		if (tokenValue().isBlank()) {
			byte[] random = new byte[32];
			TOKEN_RANDOM.nextBytes(random);
			token.setText(Base64.getUrlEncoder().withoutPadding().encodeToString(random));
		}
	}

	static void usePlainIntegerFormat(JSpinner spinner) {
		spinner.setEditor(new JSpinner.NumberEditor(spinner, "0"));
	}
	@FunctionalInterface
	private interface Operation {
		AgentClient.Status run() throws IOException, InterruptedException;
	}
	private enum Availability {
		PROBE, ON_SUCCESS, OFF_ON_SUCCESS, KEEP
	}
}
