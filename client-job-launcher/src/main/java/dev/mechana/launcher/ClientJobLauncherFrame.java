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

import dev.mechana.plugins.ocr.OcrPageSplitter;
import dev.mechana.protocol.Messages.ArtifactReference;
import dev.mechana.protocol.Messages.JobLauncherDescriptor;
import dev.mechana.protocol.Messages.LauncherJob;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
	private final JButton openArtifacts = new JButton("Open artifacts folder");
	private final JButton purge = new JButton("Purge selected");
	private final JButton purgeAll = new JButton("Purge all");
	private final DefaultTableModel jobsModel = new ReadOnlyTableModel();
	private final JTable jobs = new JTable(jobsModel);
	@SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "Swing frames are not deserialized")
	private transient List<LauncherJob> jobItems = List.of();
	@SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "Swing frames are not deserialized")
	private final transient Map<String, ClientVideoContext> clientVideoJobs = new ConcurrentHashMap<>();
	@SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "Swing frames are not deserialized")
	private final transient Map<String, ClientPluginContext> clientPluginJobs = new ConcurrentHashMap<>();
	@SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "Swing frames are not deserialized")
	private final transient Set<String> assemblingClientJobs = ConcurrentHashMap.newKeySet();
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
		historyActions.add(openArtifacts);
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
		openArtifacts.addActionListener(event -> openSelectedArtifacts());
		purge.addActionListener(event -> purgeSelected());
		purgeAll.addActionListener(event -> purgeAll());
		jobs.getSelectionModel().addListSelectionListener(event -> updateControls());
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
			ClientVideoRequest clientVideo = clientVideoRequest(form.descriptor(), values);
			boolean clientPlugin = clientVideo == null && "client-local".equals(values.get("storageProvider"));
			if (clientVideo != null || clientPlugin)
				connectionState.setText("SPLITTING — preparing local artifacts…");
			run(() -> {
				if (clientVideo != null) {
					ClientArtifactDataPlane plane = new ClientArtifactDataPlane(clientVideo.scratch(),
							clientVideo.transferHost());
					try {
						ClientArtifactDataPlane.Prepared prepared = plane.prepare(clientVideo.source(),
								clientVideo.startOffsetSeconds(), clientVideo.durationSeconds(),
								clientVideo.segmentCount(), clientVideo.targetSizeRatio());
						values.put("durationSeconds", prepared.durationSeconds());
						values.put("segmentCount", prepared.chunks().size());
						values.put("clientChunks", prepared.chunks());
						values.put("clientOutputUrl", plane.outputUrl(0).replace("/outputs/0", "/outputs/{index}"));
						values.put("videoBitrate", prepared.videoBitrate());
						removeClientControls(values);
						String jobId = client.submit(serverUri(), form.descriptor(), values);
						return new SubmissionResult(jobId,
								new ClientVideoContext(clientVideo.source(), plane.scratchDirectory(),
										clientVideo.output(), clientVideo.startOffsetSeconds(),
										prepared.durationSeconds(), prepared.videoBitrate(), plane),
								null);
					} catch (java.io.IOException | InterruptedException | RuntimeException failure) {
						plane.close();
						throw failure;
					}
				}
				ClientPluginContext context = clientPlugin ? prepareClientPlugin(form.descriptor(), values) : null;
				try {
					removeClientControls(values);
					String jobId = client.submit(serverUri(), form.descriptor(), values);
					return new SubmissionResult(jobId, null, context);
				} catch (java.io.IOException | InterruptedException | RuntimeException failure) {
					if (context != null)
						context.dataPlane().close();
					throw failure;
				}
			}, submission -> {
				if (submission.clientVideo() != null)
					clientVideoJobs.put(submission.jobId(), submission.clientVideo());
				if (submission.clientPlugin() != null)
					clientPluginJobs.put(submission.jobId(), submission.clientPlugin());
				connectionState.setText("Submitted " + submission.jobId());
				refreshJobs();
			});
		} catch (IllegalArgumentException invalid) {
			showError(invalid);
		}
	}

	private static void removeClientControls(Map<String, Object> values) {
		values.remove("clientScratchDirectory");
		values.remove("clientOutputDirectory");
		values.remove("clientTransferHost");
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
	private void openSelectedArtifacts() {
		LauncherJob job = selectedJob();
		if (job == null || !job.purgeAllowed())
			return;
		run(() -> {
			client.revealArtifacts(serverUri(), job.jobId());
			return job.jobId();
		}, ignored -> connectionState.setText("Opened artifacts for " + job.jobId()));
	}
	private void mutateSelected(boolean purgeJob) {
		LauncherJob job = selectedJob();
		if (job == null)
			return;
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
		LauncherJob selected = selectedJob();
		String selectedJobId = selected == null ? null : selected.jobId();
		jobItems = List.copyOf(items);
		for (LauncherJob job : jobItems)
			if (Set.of("FAILED", "CANCELLED").contains(job.status())) {
				ClientVideoContext abandoned = clientVideoJobs.remove(job.jobId());
				if (abandoned != null)
					abandoned.dataPlane().close();
				ClientPluginContext abandonedPlugin = clientPluginJobs.remove(job.jobId());
				if (abandonedPlugin != null)
					abandonedPlugin.dataPlane().close();
			}
		jobsModel.setRowCount(0);
		int selectedRow = rowForJobId(jobItems, selectedJobId);
		for (int row = 0; row < jobItems.size(); row++) {
			LauncherJob job = jobItems.get(row);
			jobsModel.addRow(new Object[]{job.jobId(), job.plugin(), job.status(), job.progress() + "%",
					String.join(", ", job.workerAssignments()), job.completedAt(), artifactSummary(job.artifacts())});
		}
		if (selectedRow >= 0) {
			int selectedViewRow = jobs.convertRowIndexToView(selectedRow);
			jobs.setRowSelectionInterval(selectedViewRow, selectedViewRow);
		}
		continueClientAssemblies();
		updateControls();
	}

	static int rowForJobId(List<LauncherJob> items, String jobId) {
		if (jobId == null)
			return -1;
		for (int row = 0; row < items.size(); row++)
			if (jobId.equals(items.get(row).jobId()))
				return row;
		return -1;
	}

	private LauncherJob selectedJob() {
		int viewRow = jobs.getSelectedRow();
		if (viewRow < 0)
			return null;
		int row = jobs.convertRowIndexToModel(viewRow);
		return row < jobItems.size() ? jobItems.get(row) : null;
	}

	private ClientVideoRequest clientVideoRequest(JobLauncherDescriptor descriptor, Map<String, Object> values) {
		if (!"video-ffmpeg".equals(descriptor.capabilityId())
				|| !"client-local".equals(String.valueOf(values.get("storageProvider"))))
			return null;
		Path source = Path.of(String.valueOf(values.get("sourcePath"))).toAbsolutePath().normalize();
		Path scratch = optionalDirectory(values, "clientScratchDirectory");
		Path output = requiredDirectory(values, "clientOutputDirectory", "Client output directory");
		if (!Files.isRegularFile(source))
			throw new IllegalArgumentException("Input video does not exist: " + source);
		int requestedTasks = ((Number) values.get("segmentCount")).intValue();
		int segmentCount = requestedTasks > 0 ? requestedTasks : Math.max(1, descriptor.availableWorkers());
		return new ClientVideoRequest(source, scratch, output,
				((Number) values.get("startOffsetSeconds")).doubleValue(),
				((Number) values.get("durationSeconds")).doubleValue(), segmentCount,
				((Number) values.get("targetSizeRatio")).doubleValue(),
				String.valueOf(values.getOrDefault("clientTransferHost", "")));
	}

	private static Path requiredDirectory(Map<String, Object> values, String name, String label) {
		String value = String.valueOf(values.getOrDefault(name, "")).strip();
		if (value.isBlank())
			throw new IllegalArgumentException(label + " is required for client-local storage");
		return Path.of(value).toAbsolutePath().normalize();
	}

	private static Path optionalDirectory(Map<String, Object> values, String name) {
		String value = String.valueOf(values.getOrDefault(name, "")).strip();
		return value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
	}

	private void continueClientAssemblies() {
		for (LauncherJob job : jobItems) {
			ClientPluginContext pluginContext = clientPluginJobs.get(job.jobId());
			if (pluginContext != null && "ASSEMBLING".equals(job.status()) && assemblingClientJobs.add(job.jobId())) {
				connectionState.setText("ASSEMBLING " + job.jobId() + " on this client…");
				CompletableFuture.runAsync(() -> {
					try {
						new ClientPluginAssembly(client).assemble(serverUri(), job.jobId(), pluginContext);
					} catch (java.io.IOException | InterruptedException failure) {
						throw new java.util.concurrent.CompletionException(failure);
					}
				}).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
					assemblingClientJobs.remove(job.jobId());
					ClientPluginContext finished = clientPluginJobs.remove(job.jobId());
					if (finished != null)
						finished.dataPlane().close();
					if (failure == null) {
						connectionState.setText("Client assembly completed for " + job.jobId());
						refreshJobs();
					} else {
						connectionState.setText("Client assembly failed");
						showError(failure.getCause());
					}
				}));
				continue;
			}
			ClientVideoContext context = clientVideoJobs.get(job.jobId());
			if (context == null || !"ASSEMBLING".equals(job.status()) || !assemblingClientJobs.add(job.jobId()))
				continue;
			connectionState.setText("Assembling " + job.jobId() + " on this client…");
			CompletableFuture.runAsync(() -> {
				try {
					new ClientVideoAssembly(client).assembleDirect(serverUri(), job.jobId(), context.source(),
							context.scratch(), context.output(), context.startOffsetSeconds(),
							context.durationSeconds(), context.videoBitrate(), context.dataPlane());
				} catch (java.io.IOException | InterruptedException failure) {
					throw new java.util.concurrent.CompletionException(failure);
				}
			}).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
				assemblingClientJobs.remove(job.jobId());
				ClientVideoContext finished = clientVideoJobs.remove(job.jobId());
				if (finished != null)
					finished.dataPlane().close();
				if (failure == null) {
					connectionState.setText("Client assembly completed for " + job.jobId());
					refreshJobs();
				} else {
					connectionState.setText("Client assembly failed");
					showError(failure.getCause());
				}
			}));
		}
	}

	private ClientPluginContext prepareClientPlugin(JobLauncherDescriptor descriptor, Map<String, Object> values)
			throws java.io.IOException {
		Path scratch = optionalDirectory(values, "clientScratchDirectory");
		Path output = requiredDirectory(values, "clientOutputDirectory", "Client output directory");
		ClientArtifactDataPlane plane = new ClientArtifactDataPlane(scratch,
				String.valueOf(values.getOrDefault("clientTransferHost", "")));
		try {
			String plugin = descriptor.capabilityId();
			int requestedTasks = ((Number) values.get("taskCount")).intValue();
			int taskCount;
			ClientPluginContext context;
			switch (plugin) {
				case "fractal-render" -> {
					int images = ((Number) values.get("imageCount")).intValue();
					taskCount = requestedTasks > 0 ? requestedTasks : Math.min(images, descriptor.availableWorkers());
					context = new ClientPluginContext(plugin, plane.scratchDirectory(), output, plane, images,
							((Number) values.get("width")).intValue(), ((Number) values.get("height")).intValue(),
							((Number) values.get("maxIterations")).intValue(),
							((Number) values.get("seed")).longValue(), 0, 0, "", 0, 0, 0);
				}
				case "ocr-tesseract" -> {
					Path source = Path.of(String.valueOf(values.get("sourcePath"))).toAbsolutePath().normalize();
					if (!Files.isRegularFile(source))
						throw new IllegalArgumentException("Input PDF does not exist: " + source);
					int firstPage = ((Number) values.get("firstPage")).intValue();
					var split = new OcrPageSplitter().split(source, plane.scratchDirectory().resolve("ocr-pages"),
							firstPage, ((Number) values.get("pageCount")).intValue(),
							((Number) values.get("dpi")).intValue());
					List<ArtifactReference> pages = new java.util.ArrayList<>(split.pages().size());
					for (int index = 0; index < split.pages().size(); index++)
						pages.add(plane.serveInput(index, split.pages().get(index), "image/png"));
					values.put("clientPages", pages);
					values.put("pageCount", pages.size());
					taskCount = requestedTasks > 0
							? Math.min(requestedTasks, pages.size())
							: Math.min(pages.size(), descriptor.availableWorkers());
					context = new ClientPluginContext(plugin, plane.scratchDirectory(), output, plane, 0, 0, 0, 0, 0,
							firstPage, pages.size(), String.valueOf(values.get("title")), 0, 0, 0);
				}
				case "blender-render" -> {
					Path source = Path.of(String.valueOf(values.get("sourcePath"))).toAbsolutePath().normalize();
					if (!Files.isRegularFile(source))
						throw new IllegalArgumentException("Packed Blender scene does not exist: " + source);
					values.put("clientScene", plane.serveInput(0, source, "application/octet-stream"));
					int first = ((Number) values.get("firstFrame")).intValue();
					int last = ((Number) values.get("lastFrame")).intValue();
					taskCount = requestedTasks > 0
							? requestedTasks
							: Math.min(last - first + 1, descriptor.availableWorkers());
					context = new ClientPluginContext(plugin, plane.scratchDirectory(), output, plane, 0,
							((Number) values.get("width")).intValue(), ((Number) values.get("height")).intValue(), 0, 0,
							0, 0, "", first, last, ((Number) values.get("fps")).intValue());
				}
				default -> throw new IllegalArgumentException("Unsupported client-local plugin " + plugin);
			}
			plane.configureOutputs(taskCount, ".zip");
			values.put("taskCount", taskCount);
			values.put("clientOutputUrl", plane.outputUrl(0).replace("/outputs/0", "/outputs/{index}"));
			return context;
		} catch (java.io.IOException | RuntimeException failure) {
			plane.close();
			throw failure;
		}
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
		LauncherJob selected = selectedJob();
		openArtifacts.setEnabled(!busy && selected != null && selected.purgeAllowed());
		purge.setEnabled(!busy && selected != null && selected.purgeAllowed());
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
	private record ClientVideoRequest(Path source, Path scratch, Path output, double startOffsetSeconds,
			double durationSeconds, int segmentCount, double targetSizeRatio, String transferHost) {
	}
	private record ClientVideoContext(Path source, Path scratch, Path output, double startOffsetSeconds,
			double durationSeconds, long videoBitrate, ClientArtifactDataPlane dataPlane) {
	}
	private record SubmissionResult(String jobId, ClientVideoContext clientVideo, ClientPluginContext clientPlugin) {
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
