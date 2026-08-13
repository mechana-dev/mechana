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
package dev.mechana.localreverb;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import dev.mechana.plugins.audio.SweepDeconvolver;

/** Focused local launcher with no server or worker concepts. */
final class StandaloneReverbFrame extends JFrame {
	private static final long serialVersionUID = 1L;
	private final transient Preferences settings = Preferences.userNodeForPackage(StandaloneReverbFrame.class);
	private final transient LocalReverbEngine engine = new LocalReverbEngine();
	private final JTextField dryPath = field("dryPath", "");
	private final JTextField irPath = field("irPath", "");
	private final JTextField artifactRoot = field("artifactRoot",
			Path.of(System.getProperty("user.home"), "Documents", "Mechana Reverb Jobs").toString());
	private final JTextField outputName = field("outputName", "reverberated.wav");
	private final JTextField wet = field("wet", "0.35");
	private final JTextField dry = field("dry", "1.0");
	private final JTextField preDelay = field("preDelayMilliseconds", "20");
	private final JCheckBox normalizeIr = check("normalizeIr", true);
	private final JCheckBox peakProtection = check("peakProtection", true);
	private final JTextField headroom = field("headroomDecibels", "1.0");
	private final JTextField sweepPath = field("sweepPath", bundledSweepDefault());
	private final JTextField recordedSweepPath = field("recordedSweepPath", "");
	private final JTextField generatedIrPath = field("generatedIrPath",
			Path.of(System.getProperty("user.home"), "Documents", "RVB_Plug-IR.wav").toString());
	private final JButton run = new JButton("Run Reverb");
	private final JButton generateIr = new JButton("Generate IR Profile");
	private final JButton cancel = new JButton("Cancel");
	private final JButton reveal = new JButton("Show Artifacts");
	private final JButton playOutput = new JButton("Play Output");
	private final JButton showOutput = new JButton("Show in Finder");
	private final JLabel status = new JLabel("Ready — processing stays on this Mac");
	private final JProgressBar progress = new JProgressBar(0, 100);
	private final JobTableModel jobs = new JobTableModel();
	private final JTable jobTable = new JTable(jobs);
	private boolean outputOverridden;
	private transient Path latestOutput;

	StandaloneReverbFrame() {
		super("Mechana Reverb");
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		setMinimumSize(new Dimension(850, 650));
		setSize(980, 760);
		setLocationByPlatform(true);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent event) {
				engine.close();
				dispose();
			}
		});
		add(buildHeader(), BorderLayout.NORTH);
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildTools(), buildHistory());
		split.setResizeWeight(0.62);
		add(split, BorderLayout.CENTER);
		add(buildStatus(), BorderLayout.SOUTH);
		configureActions();
		configureSuggestedName();
		reloadHistory();
	}

	private JTabbedPane buildTools() {
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Apply Reverb", buildForm());
		tabs.addTab("Create IR from Sweep", buildIrCreator());
		return tabs;
	}

	private JPanel buildHeader() {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
		panel.add(new JLabel("<html><h2 style='margin:0'>Mechana Reverb</h2>"
				+ "<div>Apply an impulse response locally with the same pure-Java plugin used by Mechana workers.</div></html>"));
		return panel;
	}

	private JPanel buildForm() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createTitledBorder("New reverb job"));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(5, 8, 5, 8);
		c.gridy = 0;
		addPath(panel, c, "Dry audio WAV", dryPath, false);
		addPath(panel, c, "Impulse response WAV", irPath, false);
		JButton profiles = new JButton("Choose a bundled IR profile…");
		profiles.addActionListener(event -> chooseBundledProfile());
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.WEST;
		panel.add(profiles, c);
		c.gridy++;
		addPath(panel, c, "Artifacts folder", artifactRoot, true);
		addRow(panel, c, "Output WAV name", outputName);
		addRow(panel, c, "Wet level (0–2)", wet);
		addRow(panel, c, "Dry level (0–2)", dry);
		addRow(panel, c, "Pre-delay (ms)", preDelay);
		addRow(panel, c, "Normalize IR", normalizeIr);
		addRow(panel, c, "Peak protection", peakProtection);
		addRow(panel, c, "Safe headroom (dB)", headroom);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.WEST;
		JPanel buttons = new JPanel();
		buttons.add(run);
		buttons.add(cancel);
		panel.add(buttons, c);
		cancel.setEnabled(false);
		return panel;
	}

	private JPanel buildIrCreator() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createTitledBorder("Create an impulse response from a hardware sweep recording"));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(7, 8, 7, 8);
		c.gridy = 0;
		addPath(panel, c, "Mechana source sweep", sweepPath, false);
		addPath(panel, c, "Recorded wet sweep return", recordedSweepPath, false);
		addSavePath(panel, c, "Output IR WAV", generatedIrPath);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.WEST;
		panel.add(generateIr, c);
		c.gridy++;
		c.gridx = 0;
		c.gridwidth = 3;
		c.fill = GridBagConstraints.HORIZONTAL;
		panel.add(new JLabel("<html>Record the supplied sweep through the reverb at 100% wet. Keep the leading and "
				+ "trailing silence, then select that recording here. The generated WAV can be selected directly in "
				+ "the Apply Reverb tab.</html>"), c);
		return panel;
	}

	private JScrollPane buildHistory() {
		jobTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		jobTable.setFillsViewportHeight(true);
		jobTable.getSelectionModel().addListSelectionListener(event -> reveal.setEnabled(selectedJob() != null));
		JPanel panel = new JPanel(new BorderLayout(8, 8));
		panel.setBorder(BorderFactory.createTitledBorder("Local job history"));
		panel.add(new JScrollPane(jobTable), BorderLayout.CENTER);
		JPanel actions = new JPanel();
		actions.add(reveal);
		panel.add(actions, BorderLayout.SOUTH);
		reveal.setEnabled(false);
		JScrollPane wrapper = new JScrollPane(panel);
		wrapper.setBorder(BorderFactory.createEmptyBorder());
		return wrapper;
	}

	private JPanel buildStatus() {
		JPanel panel = new JPanel(new BorderLayout(8, 8));
		panel.setBorder(BorderFactory.createEmptyBorder(8, 16, 14, 16));
		progress.setStringPainted(true);
		JPanel resultActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
		resultActions.add(status);
		resultActions.add(playOutput);
		resultActions.add(showOutput);
		playOutput.setEnabled(false);
		showOutput.setEnabled(false);
		panel.add(resultActions, BorderLayout.WEST);
		panel.add(progress, BorderLayout.CENTER);
		return panel;
	}

	private void configureActions() {
		run.addActionListener(event -> submit());
		generateIr.addActionListener(event -> generateImpulseResponse());
		cancel.addActionListener(event -> engine.cancel());
		playOutput.addActionListener(event -> openLatestOutput(false));
		showOutput.addActionListener(event -> openLatestOutput(true));
		reveal.addActionListener(event -> {
			ReverbJob selected = selectedJob();
			if (selected != null)
				try {
					Desktop.getDesktop().open(selected.artifactDirectory().toFile());
				} catch (IOException failure) {
					showError(failure.getMessage());
				}
		});
		artifactRoot.getDocument().addDocumentListener(listener(this::reloadHistory));
	}

	private void generateImpulseResponse() {
		Path sweep = path(sweepPath);
		Path recorded = path(recordedSweepPath);
		Path output = path(generatedIrPath);
		if (sweep == null || recorded == null || output == null) {
			showError("Choose the source sweep, recorded wet return, and output IR WAV.");
			return;
		}
		generateIr.setEnabled(false);
		run.setEnabled(false);
		status.setText("Generating impulse response…");
		progress.setValue(0);
		Thread.ofVirtual().name("mechana-ir-deconvolution").start(() -> {
			try {
				SweepDeconvolver.Result result = new SweepDeconvolver().deconvolve(sweep, recorded, output,
						percent -> SwingUtilities.invokeLater(() -> progress.setValue(percent)));
				Files.writeString(output.resolveSibling(output.getFileName() + ".txt"),
						"Mechana impulse-response generation\n\nOriginal sweep: " + sweep + "\nRecorded wet return: "
								+ recorded + "\nOutput IR: " + output + "\nSample rate: " + result.sampleRate()
								+ " Hz\nChannels: " + result.channels() + "\nFrames: " + result.frames()
								+ "\nCapture latency: " + result.latencyMilliseconds() + " ms\nRecovered peak: "
								+ result.peak() + "\nAlgorithm: regularized FFT deconvolution\n");
				SwingUtilities.invokeLater(() -> {
					irPath.setText(output.toString());
					status.setText("IR ready — " + output.getFileName() + " — "
							+ String.format("%.2f seconds, %.1f ms capture latency",
									result.frames() / (double) result.sampleRate(), result.latencyMilliseconds()));
					generationFinished();
				});
			} catch (IOException | RuntimeException failure) {
				SwingUtilities.invokeLater(() -> {
					showError(failure.getMessage());
					status.setText("IR generation failed");
					generationFinished();
				});
			}
		});
	}

	private void generationFinished() {
		generateIr.setEnabled(true);
		run.setEnabled(true);
	}

	private void configureSuggestedName() {
		outputOverridden = !"reverberated.wav".equals(outputName.getText());
		outputName.getDocument().addDocumentListener(listener(() -> outputOverridden = true));
		Runnable update = () -> {
			if (outputOverridden || dryPath.getText().isBlank() || irPath.getText().isBlank())
				return;
			String suggested = suggestedOutputName(dryPath.getText(), irPath.getText(), wet.getText(), dry.getText(),
					preDelay.getText(), normalizeIr.isSelected());
			outputName.setText(suggested);
			outputOverridden = false;
		};
		for (JTextField field : List.of(dryPath, irPath))
			field.getDocument().addDocumentListener(listener(() -> {
				outputOverridden = false;
				update.run();
			}));
		for (JTextField field : List.of(wet, dry, preDelay))
			field.getDocument().addDocumentListener(listener(update));
		normalizeIr.addActionListener(event -> update.run());
		update.run();
	}

	private void submit() {
		try {
			ReverbRequest request = new ReverbRequest(path(dryPath), path(irPath), path(artifactRoot),
					outputName.getText().strip(), decimal(wet, "Wet level"), decimal(dry, "Dry level"),
					decimal(preDelay, "Pre-delay"), normalizeIr.isSelected(), peakProtection.isSelected(),
					decimal(headroom, "Safe headroom"));
			engine.submit(request, job -> SwingUtilities.invokeLater(() -> update(job)));
			run.setEnabled(false);
			cancel.setEnabled(true);
		} catch (IOException | RuntimeException failure) {
			showError(failure.getMessage());
		}
	}

	private void update(ReverbJob job) {
		jobs.upsert(job);
		progress.setValue(job.progress());
		status.setText(job.status() + " — " + job.id() + (job.error().isBlank() ? "" : " — " + job.error()));
		if (!"RUNNING".equals(job.status())) {
			run.setEnabled(true);
			cancel.setEnabled(false);
			if ("SUCCEEDED".equals(job.status()))
				setLatestOutput(job.artifactDirectory().resolve(job.outputName()));
		}
	}

	private void setLatestOutput(Path output) {
		latestOutput = output;
		boolean available = output != null && Files.isRegularFile(output);
		playOutput.setEnabled(available);
		showOutput.setEnabled(available);
	}

	private void openLatestOutput(boolean revealInFinder) {
		Path output = latestOutput;
		if (output == null || !Files.isRegularFile(output)) {
			showError("The completed output WAV is no longer available.");
			setLatestOutput(null);
			return;
		}
		try {
			if (revealInFinder && System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac"))
				new ProcessBuilder("/usr/bin/open", "-R", output.toString()).start();
			else
				Desktop.getDesktop().open(output.toFile());
		} catch (IOException | UnsupportedOperationException failure) {
			showError(failure.getMessage());
		}
	}

	private void reloadHistory() {
		try {
			jobs.replace(engine.loadJobs(path(artifactRoot)));
		} catch (IOException | RuntimeException ignored) {
			jobs.replace(List.of());
		}
	}

	private void addPath(JPanel panel, GridBagConstraints c, String label, JTextField field, boolean directory) {
		addRow(panel, c, label, field);
		JButton choose = new JButton("Choose…");
		choose.addActionListener(event -> choose(field, directory));
		c.gridx = 2;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		panel.add(choose, c);
		c.gridy++;
	}

	private void addSavePath(JPanel panel, GridBagConstraints c, String label, JTextField field) {
		addRow(panel, c, label, field);
		JButton choose = new JButton("Choose…");
		choose.addActionListener(event -> chooseOutput(field));
		c.gridx = 2;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		panel.add(choose, c);
		c.gridy++;
	}

	private static void addRow(JPanel panel, GridBagConstraints c, String label, java.awt.Component component) {
		c.gridx = 0;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.WEST;
		panel.add(new JLabel(label), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		panel.add(component, c);
		c.gridy++;
	}

	private void choose(JTextField target, boolean directory) {
		JFileChooser chooser = new JFileChooser();
		if (directory)
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		else
			chooser.setFileFilter(new FileNameExtensionFilter("WAV audio (.wav)", "wav"));
		if (!target.getText().isBlank())
			chooser.setSelectedFile(new File(target.getText()));
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
			target.setText(chooser.getSelectedFile().getAbsolutePath());
	}

	private void chooseOutput(JTextField target) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Save generated impulse response");
		chooser.setFileFilter(new FileNameExtensionFilter("WAV impulse response (.wav)", "wav"));
		if (!target.getText().isBlank())
			chooser.setSelectedFile(new File(target.getText()));
		if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
			String selected = chooser.getSelectedFile().getAbsolutePath();
			target.setText(selected.toLowerCase(java.util.Locale.ROOT).endsWith(".wav") ? selected : selected + ".wav");
		}
	}

	private void chooseBundledProfile() {
		Path directory = BundledProfiles.directory();
		if (directory == null) {
			showError("Bundled IR profiles could not be found.");
			return;
		}
		JFileChooser chooser = new JFileChooser(directory.toFile());
		chooser.setDialogTitle("Choose a bundled impulse response");
		chooser.setFileFilter(new FileNameExtensionFilter("WAV impulse responses (.wav)", "wav"));
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
			irPath.setText(chooser.getSelectedFile().getAbsolutePath());
	}

	private JTextField field(String key, String fallback) {
		JTextField result = new JTextField(settings.get(key, fallback), 34);
		result.getDocument().addDocumentListener(listener(() -> settings.put(key, result.getText())));
		return result;
	}

	private JCheckBox check(String key, boolean fallback) {
		JCheckBox result = new JCheckBox();
		result.setSelected(settings.getBoolean(key, fallback));
		result.addActionListener(event -> settings.putBoolean(key, result.isSelected()));
		return result;
	}

	private ReverbJob selectedJob() {
		int row = jobTable.getSelectedRow();
		return row < 0 ? null : jobs.get(jobTable.convertRowIndexToModel(row));
	}

	private static Path path(JTextField field) {
		return field.getText().isBlank() ? null : Path.of(field.getText().strip());
	}

	private static String bundledSweepDefault() {
		Path sweep = BundledProfiles.sweep();
		return sweep == null ? "" : sweep.toString();
	}

	private static double decimal(JTextField field, String label) {
		try {
			return Double.parseDouble(field.getText().strip());
		} catch (NumberFormatException invalid) {
			throw new IllegalArgumentException(label + " must be a number", invalid);
		}
	}

	private void showError(String message) {
		JOptionPane.showMessageDialog(this, message, "Mechana Reverb", JOptionPane.ERROR_MESSAGE);
	}

	static String suggestedOutputName(String dry, String ir, String wet, String dryLevel, String preDelay,
			boolean normalize) {
		return stem(dry, "audio") + "-reverb-ir-" + stem(ir, "impulse") + "-wet" + token(wet) + "-dry" + token(dryLevel)
				+ "-pre" + token(preDelay) + "ms-norm-" + (normalize ? "on" : "off") + ".wav";
	}

	private static String stem(String path, String fallback) {
		String fileName = new File(path).getName().replaceFirst("(?i)\\.wav$", "");
		String value = fileName.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
		return value.isBlank() ? fallback : value;
	}

	private static String token(String value) {
		try {
			return new BigDecimal(value.strip()).stripTrailingZeros().toPlainString().replace('-', 'm').replace('.',
					'p');
		} catch (NumberFormatException ignored) {
			return value.strip().replaceAll("[^A-Za-z0-9]+", "-");
		}
	}

	private static DocumentListener listener(Runnable action) {
		return new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent event) {
				action.run();
			}
			@Override
			public void removeUpdate(DocumentEvent event) {
				action.run();
			}
			@Override
			public void changedUpdate(DocumentEvent event) {
				action.run();
			}
		};
	}

	@SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "Swing table models are never serialized")
	private static final class JobTableModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		private final List<ReverbJob> items = new ArrayList<>();
		private final String[] columns = {"Submitted", "Status", "Progress", "Output", "Job"};

		void replace(List<ReverbJob> values) {
			items.clear();
			items.addAll(values);
			fireTableDataChanged();
		}
		void upsert(ReverbJob job) {
			int index = java.util.stream.IntStream.range(0, items.size())
					.filter(i -> items.get(i).id().equals(job.id())).findFirst().orElse(-1);
			if (index < 0)
				items.addFirst(job);
			else
				items.set(index, job);
			fireTableDataChanged();
		}
		ReverbJob get(int row) {
			return items.get(row);
		}
		@Override
		public int getRowCount() {
			return items.size();
		}
		@Override
		public int getColumnCount() {
			return columns.length;
		}
		@Override
		public String getColumnName(int column) {
			return columns[column];
		}
		@Override
		public Object getValueAt(int row, int column) {
			ReverbJob job = items.get(row);
			return switch (column) {
				case 0 -> job.submittedAt().toString().replace('T', ' ').replace("Z", " UTC");
				case 1 -> job.status();
				case 2 -> job.progress() + "%";
				case 3 -> job.outputName();
				default -> job.id();
			};
		}
	}
}
