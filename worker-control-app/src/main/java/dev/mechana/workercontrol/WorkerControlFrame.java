package dev.mechana.workercontrol;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;

final class WorkerControlFrame extends JFrame {
	private static final long serialVersionUID = 1L;
	private final transient AgentClient client;
	private final transient SettingsStore store;
	private final JComboBox<String> host = new JComboBox<>();
	private final JSpinner port = new JSpinner(new SpinnerNumberModel(8790, 1, 65535, 1));
	private final JPasswordField token = new JPasswordField(16);
	private final JSpinner count = new JSpinner(new SpinnerNumberModel(1, 0, 128, 1));
	private final JLabel state = new JLabel("Not checked");
	private final JTextArea workers = new JTextArea(9, 52);
	private final JButton refresh = new JButton("Refresh");
	private final JButton start = new JButton("Start");
	private final JButton stop = new JButton("Stop all");
	private boolean changingHostList;
	private long requestGeneration;

	WorkerControlFrame(AgentClient client, SettingsStore store) {
		super("Mechana Worker Control");
		this.client = client;
		this.store = store;
		host.setEditable(true);
		workers.setEditable(false);
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
		actions.add(start);
		actions.add(stop);
		actions.add(state);
		JPanel top = new JPanel(new BorderLayout());
		top.add(connection, BorderLayout.NORTH);
		top.add(actions, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);
		add(new JScrollPane(workers), BorderLayout.CENTER);
		refresh.addActionListener(event -> refreshStatus());
		host.addActionListener(event -> {
			if (!changingHostList)
				refreshStatus();
		});
		start.addActionListener(
				event -> run("Starting", () -> client.start(baseUri(), tokenValue(), (Integer) count.getValue())));
		stop.addActionListener(event -> run("Stopping", () -> client.stop(baseUri(), tokenValue())));
		loadSettings();
		setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		pack();
		setLocationByPlatform(true);
		SwingUtilities.invokeLater(this::refreshStatus);
	}

	private void refreshStatus() {
		run("Checking", () -> client.status(baseUri(), tokenValue()));
	}

	private void run(String activity, Operation operation) {
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
			setBusy(false);
			if (failure == null)
				show(result);
			else
				showError(failure);
		}));
	}

	private void show(AgentClient.Status status) {
		state.setText(status.state() + " — " + status.runningCount() + " running / " + status.requestedCount()
				+ " requested");
		StringBuilder text = new StringBuilder();
		for (AgentClient.Worker worker : status.workers())
			text.append(worker.id()).append("   PID ").append(worker.pid()).append("   ")
					.append(worker.alive() ? "RUNNING" : "STOPPED").append("   since ").append(worker.startedAt())
					.append('\n');
		if (!status.diagnostic().isBlank())
			text.append("Diagnostic: ").append(status.diagnostic());
		workers.setText(text.toString());
	}

	private void showError(Throwable failure) {
		Throwable cause = failure.getCause() == null ? failure : failure.getCause();
		state.setText("ERROR");
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
	private void setBusy(boolean busy) {
		refresh.setEnabled(!busy);
		start.setEnabled(!busy);
		stop.setEnabled(!busy);
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
					(Integer) count.getValue()));
		} catch (IOException failure) {
			workers.setText("Could not save settings: " + failure.getMessage());
		}
	}
	@FunctionalInterface
	private interface Operation {
		AgentClient.Status run() throws IOException, InterruptedException;
	}
}
