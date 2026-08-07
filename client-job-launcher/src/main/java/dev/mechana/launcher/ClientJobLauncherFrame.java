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

package dev.mechana.launcher;

import dev.mechana.protocol.Messages.ArtifactReference;
import dev.mechana.protocol.Messages.JobLauncherDescriptor;
import dev.mechana.protocol.Messages.LauncherJob;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

final class ClientJobLauncherFrame extends JFrame {
	private static final long serialVersionUID = 1L;
	private final transient LauncherClient client;
	private final transient Preferences settings = Preferences.userNodeForPackage(ClientJobLauncherFrame.class);
	private final JTextField server = new JTextField(28);
	private final JButton connect = new JButton("Connect");
	private final JLabel connectionState = new JLabel("Disconnected");
	private final JComboBox<JobLauncherDescriptor> capabilities = new JComboBox<>();
	private final JPanel forms = new JPanel(new CardLayout());
	private final JButton start = new JButton("Start");
	private final JButton abort = new JButton("Abort selected");
	private final JButton purge = new JButton("Purge selected");
	private final JButton purgeAll = new JButton("Purge all");
	private final DefaultTableModel jobsModel = new ReadOnlyTableModel();
	private final JTable jobs = new JTable(jobsModel);
	@SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "Swing frames are not deserialized")
	private transient List<LauncherJob> jobItems = List.of();
	private Timer refreshTimer;
	private boolean busy;

	ClientJobLauncherFrame(LauncherClient client) {
		super("Mechana Client Job Launcher");
		this.client = client;
		server.setText(settings.get("server", "http://127.0.0.1:8787"));
		capabilities.setRenderer(new CapabilityRenderer());
		JPanel connection = new JPanel(new FlowLayout(FlowLayout.LEFT));
		connection.add(new JLabel("Server"));
		connection.add(server);
		connection.add(connect);
		connection.add(connectionState);
		JPanel selection = new JPanel(new FlowLayout(FlowLayout.LEFT));
		selection.add(new JLabel("Capability"));
		selection.add(capabilities);
		selection.add(start);
		selection.add(abort);
		JPanel submission = new JPanel(new BorderLayout());
		submission.add(selection, BorderLayout.NORTH);
		submission.add(forms, BorderLayout.CENTER);
		JPanel historyActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
		historyActions.add(new JLabel("Jobs and completed history"));
		historyActions.add(purge);
		historyActions.add(purgeAll);
		JPanel history = new JPanel(new BorderLayout());
		history.add(historyActions, BorderLayout.NORTH);
		history.add(new JScrollPane(jobs), BorderLayout.CENTER);
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, submission, history);
		split.setResizeWeight(0.48);
		add(connection, BorderLayout.NORTH);
		add(split, BorderLayout.CENTER);
		connect.addActionListener(event -> discover());
		capabilities.addActionListener(event -> showSelectedForm());
		start.addActionListener(event -> submit());
		abort.addActionListener(event -> abortSelected());
		purge.addActionListener(event -> purgeSelected());
		purgeAll.addActionListener(event -> purgeAll());
		setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		setSize(980, 720);
		setLocationByPlatform(true);
		updateControls();
		SwingUtilities.invokeLater(this::discover);
	}

	private void discover() {
		settings.put("server", server.getText().strip());
		run(() -> new Refresh(client.capabilities(serverUri()), client.jobs(serverUri())), result -> {
			capabilities.removeAllItems();
			forms.removeAll();
			for (JobLauncherDescriptor descriptor : result.capabilities()) {
				capabilities.addItem(descriptor);
				forms.add(new DescriptorForm(descriptor, settings.node("forms").node(descriptor.capabilityId())),
						descriptor.capabilityId());
			}
			showJobs(result.jobs());
			connectionState
					.setText(result.capabilities().isEmpty() ? "Connected — no schedulable capabilities" : "Connected");
			if (refreshTimer == null) {
				refreshTimer = new Timer(2000, event -> refreshJobs());
				refreshTimer.start();
			}
		});
	}

	private void refreshJobs() {
		if (busy)
			return;
		CompletableFuture.supplyAsync(() -> {
			try {
				return client.jobs(serverUri());
			} catch (Exception failure) {
				throw new java.util.concurrent.CompletionException(failure);
			}
		}).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
			if (failure == null) {
				showJobs(result);
				connectionState.setText("Connected");
			} else {
				connectionState.setText("Disconnected — showing stale data");
			}
		}));
	}

	private void showSelectedForm() {
		JobLauncherDescriptor selected = (JobLauncherDescriptor) capabilities.getSelectedItem();
		if (selected != null)
			((CardLayout) forms.getLayout()).show(forms, selected.capabilityId());
	}

	private void submit() {
		DescriptorForm form = selectedForm();
		if (form == null)
			return;
		try {
			var values = form.values();
			run(() -> client.submit(serverUri(), form.descriptor(), values), jobId -> {
				connectionState.setText("Submitted " + jobId);
				refreshJobs();
			});
		} catch (IllegalArgumentException invalid) {
			showError(invalid);
		}
	}

	private void abortSelected() {
		mutateSelected(false);
	}
	private void purgeSelected() {
		mutateSelected(true);
	}
	private void purgeAll() {
		long completed = jobItems.stream().filter(LauncherJob::purgeAllowed).count();
		if (completed == 0)
			return;
		int choice = JOptionPane.showConfirmDialog(this,
				"Permanently delete all " + completed + " completed jobs and their server-local artifacts?",
				"Purge all completed jobs", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.YES_OPTION)
			return;
		run(() -> {
			client.purgeAll(serverUri());
			return completed;
		}, ignored -> refreshJobs());
	}
	private void mutateSelected(boolean purgeJob) {
		int row = jobs.getSelectedRow();
		if (row < 0 || row >= jobItems.size())
			return;
		LauncherJob job = jobItems.get(row);
		if (purgeJob && !job.purgeAllowed()) {
			showError(new IllegalStateException("Only completed, server-owned history can be purged"));
			return;
		}
		run(() -> {
			if (purgeJob)
				client.purge(serverUri(), job.jobId());
			else
				client.abort(serverUri(), job.jobId());
			return job.jobId();
		}, ignored -> refreshJobs());
	}

	private DescriptorForm selectedForm() {
		JobLauncherDescriptor selected = (JobLauncherDescriptor) capabilities.getSelectedItem();
		if (selected == null)
			return null;
		for (java.awt.Component component : forms.getComponents())
			if (component instanceof DescriptorForm form
					&& form.descriptor().capabilityId().equals(selected.capabilityId()))
				return form;
		return null;
	}

	private void showJobs(List<LauncherJob> items) {
		jobItems = List.copyOf(items);
		jobsModel.setRowCount(0);
		for (LauncherJob job : jobItems)
			jobsModel.addRow(new Object[]{job.jobId(), job.plugin(), job.status(), job.progress() + "%",
					String.join(", ", job.workerAssignments()), job.completedAt(), artifactSummary(job.artifacts())});
	}

	private static String artifactSummary(List<ArtifactReference> artifacts) {
		return artifacts.stream().map(artifact -> artifact.provider() + ":" + artifact.key())
				.reduce((a, b) -> a + ", " + b).orElse("");
	}

	private URI serverUri() {
		String value = server.getText().strip();
		return URI.create(value.endsWith("/") ? value : value + "/");
	}
	private <T> void run(Operation<T> operation, java.util.function.Consumer<T> success) {
		setBusy(true);
		CompletableFuture.supplyAsync(() -> {
			try {
				return operation.run();
			} catch (Exception failure) {
				throw new java.util.concurrent.CompletionException(failure);
			}
		}).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
			setBusy(false);
			if (failure == null) {
				success.accept(result);
				updateControls();
			} else {
				connectionState.setText("Disconnected / stale");
				showError(failure.getCause());
			}
		}));
	}
	private void setBusy(boolean value) {
		busy = value;
		updateControls();
	}
	private void updateControls() {
		connect.setEnabled(!busy);
		start.setEnabled(!busy && capabilities.getItemCount() > 0);
		abort.setEnabled(!busy);
		purge.setEnabled(!busy);
		purgeAll.setEnabled(!busy && jobItems.stream().anyMatch(LauncherJob::purgeAllowed));
	}
	private void showError(Throwable failure) {
		JOptionPane.showMessageDialog(this, failure.getMessage(), "Mechana", JOptionPane.ERROR_MESSAGE);
	}
	@FunctionalInterface
	private interface Operation<T> {
		T run() throws java.io.IOException, InterruptedException;
	}
	private record Refresh(List<JobLauncherDescriptor> capabilities, List<LauncherJob> jobs) {
	}
	private static final class ReadOnlyTableModel extends DefaultTableModel {
		private static final long serialVersionUID = 1L;
		ReadOnlyTableModel() {
			super(new Object[]{"Job", "Plugin", "Status", "Progress", "Workers", "Completed", "Artifacts"}, 0);
		}
		@Override
		public boolean isCellEditable(int row, int column) {
			return false;
		}
	}
	private static final class CapabilityRenderer extends DefaultListCellRenderer {
		private static final long serialVersionUID = 1L;
		@Override
		public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
				boolean focus) {
			String label = value instanceof JobLauncherDescriptor descriptor ? descriptor.displayName() : "";
			return super.getListCellRendererComponent(list, label, index, selected, focus);
		}
	}
}
