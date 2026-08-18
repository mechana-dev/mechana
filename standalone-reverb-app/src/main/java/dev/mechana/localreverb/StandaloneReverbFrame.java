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
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.prefs.Preferences;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JDialog;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import dev.mechana.plugins.audio.SweepDeconvolver;
import dev.mechana.plugins.audio.WavFile;

/** Focused local launcher with no server or worker concepts. */
final class StandaloneReverbFrame extends JFrame {
	private static final long serialVersionUID = 1L;
	private final transient Preferences settings = Preferences.userNodeForPackage(StandaloneReverbFrame.class);
	private final transient LocalReverbEngine engine = new LocalReverbEngine();
	private final transient LocalEchoEngine echoEngine = new LocalEchoEngine();
	private final transient LocalLeslieEngine leslieEngine = new LocalLeslieEngine();
	private final transient ImpulseResponseCache impulseResponseCache = new ImpulseResponseCache();
	private final transient IrProfileLibrary profileLibrary = new IrProfileLibrary();
	private final transient ReverbPreviewPlayer previewPlayer = new ReverbPreviewPlayer(impulseResponseCache);
	private final transient WavPreviewPlayer echoPreviewPlayer = new WavPreviewPlayer();
	private final transient LesliePreviewPlayer lesliePreviewPlayer = new LesliePreviewPlayer();
	private final JTextField dryPath = field("dryPath", "");
	private final JTextField irPath = field("irPath", "");
	private final JTextField artifactRoot = field("artifactRoot",
			Path.of(System.getProperty("user.home"), "Documents", "Mechana Reverb Jobs").toString());
	private final JTextField outputName = field("outputName", "reverberated.wav");
	private final JTextField wet = field("wet", "0.35");
	private final JTextField dry = field("dry", "1.0");
	private final JTextField preDelay = field("preDelayMilliseconds", "20");
	private final JTextField lowCut = field("lowCutHertz", "0");
	private final JTextField highCut = field("highCutHertz", "0");
	private final JTextField earlyLevel = field("earlyLevel", "1.0");
	private final JTextField lateLevel = field("lateLevel", "1.0");
	private final JTextField attack = field("attackMilliseconds", "0");
	private final JTextField decayLength = field("decayLengthSeconds", "2.0");
	private final JComboBox<EchoSettings.Model> echoModel = new JComboBox<>(EchoSettings.Model.values());
	private final JTextField echoDelay = field("echoDelayMilliseconds", "375");
	private final JTextField echoFeedback = field("echoFeedback", "0.38");
	private final JTextField echoWet = field("echoWet", "0.26");
	private final JTextField echoDry = field("echoDry", "1.0");
	private final JTextField echoLowCut = field("echoLowCutHertz", "45");
	private final JTextField echoHighCut = field("echoHighCutHertz", "6000");
	private final JTextField echoSaturation = field("echoSaturation", "0.22");
	private final JTextField echoRate = field("echoModulationRateHertz", "0.55");
	private final JTextField echoDepth = field("echoModulationDepthMilliseconds", "1.6");
	private final JCheckBox echoPingPong = check("echoPingPong", false);
	private final JComboBox<LeslieSettings.Speed> leslieSpeed = new JComboBox<>(LeslieSettings.Speed.values());
	private final JTextField leslieDrive = field("leslieDrive", "0.18");
	private final JTextField leslieHornLevel = field("leslieHornLevel", "0.52");
	private final JTextField leslieMicDistance = field("leslieMicDistance", "0.35");
	private final JTextField leslieStereoWidth = field("leslieStereoWidth", "0.72");
	private final JTextField leslieCrossover = field("leslieCrossoverHertz", "800");
	private final JTextField leslieWet = field("leslieWet", "1.0");
	private final JTextField leslieDry = field("leslieDry", "0.0");
	private final JSlider leslieDriveSlider = new JSlider(0, 100, boundedSliderValue(leslieDrive, 100, 18, 0, 100));
	private final JSlider leslieHornSlider = new JSlider(0, 100, boundedSliderValue(leslieHornLevel, 100, 52, 0, 100));
	private final JSlider leslieMicSlider = new JSlider(0, 100, boundedSliderValue(leslieMicDistance, 100, 35, 0, 100));
	private final JSlider leslieWidthSlider = new JSlider(0, 100,
			boundedSliderValue(leslieStereoWidth, 100, 72, 0, 100));
	private final JSlider leslieCrossoverSlider = new JSlider(200, 2_000,
			boundedSliderValue(leslieCrossover, 1, 800, 200, 2_000));
	private final JSlider leslieWetSlider = new JSlider(0, 200, boundedSliderValue(leslieWet, 100, 100, 0, 200));
	private final JSlider leslieDrySlider = new JSlider(0, 200, boundedSliderValue(leslieDry, 100, 0, 0, 200));
	private final JSlider echoDelaySlider = new JSlider(1, 1_500, boundedSliderValue(echoDelay, 1, 375, 1, 1_500));
	private final JSlider echoFeedbackSlider = new JSlider(0, 95, boundedSliderValue(echoFeedback, 100, 38, 0, 95));
	private final JSlider echoWetSlider = new JSlider(0, 200, boundedSliderValue(echoWet, 100, 26, 0, 200));
	private final JSlider echoDrySlider = new JSlider(0, 200, boundedSliderValue(echoDry, 100, 100, 0, 200));
	private final JSlider echoLowCutSlider = new JSlider(0, 1000, frequencySliderValue(echoLowCut));
	private final JSlider echoHighCutSlider = new JSlider(0, 1000, frequencySliderValue(echoHighCut));
	private final JSlider echoSaturationSlider = new JSlider(0, 100,
			boundedSliderValue(echoSaturation, 100, 22, 0, 100));
	private final JSlider echoRateSlider = new JSlider(0, 1_000, boundedSliderValue(echoRate, 100, 55, 0, 1_000));
	private final JSlider echoDepthSlider = new JSlider(0, 1_000, boundedSliderValue(echoDepth, 100, 160, 0, 1_000));
	private final JTabbedPane effectTabs = new JTabbedPane();
	private final JSlider wetSlider = new JSlider(0, 200, sliderValue(wet, 100, 35));
	private final JSlider drySlider = new JSlider(0, 200, sliderValue(dry, 100, 100));
	private final JSlider preDelaySlider = new JSlider(0, 200, Math.min(200, sliderValue(preDelay, 1, 20)));
	private final JSlider lowCutSlider = new JSlider(0, 1000, frequencySliderValue(lowCut));
	private final JSlider highCutSlider = new JSlider(0, 1000, frequencySliderValue(highCut));
	private final JSlider earlySlider = new JSlider(0, 200, sliderValue(earlyLevel, 100, 100));
	private final JSlider lateSlider = new JSlider(0, 200, sliderValue(lateLevel, 100, 100));
	private final JSlider attackSlider = new JSlider(0, 2000, sliderValue(attack, 1, 0));
	private final JSlider decaySlider = new JSlider(5, 3000, sliderValue(decayLength, 100, 200));
	private final JComboBox<IrProfileLibrary.Profile> profileSelector = new JComboBox<>();
	private final Timer irPreviewChangeTimer = new Timer(350, event -> updatePreviewImpulseResponse());
	private final Timer dryPreviewChangeTimer = new Timer(350, event -> restartPreviewWithSelectedSource());
	private static final boolean AUTOMATIC_IR_PEAK_SAFETY = true;
	private static final boolean AUTOMATIC_PEAK_PROTECTION = true;
	private static final double AUTOMATIC_HEADROOM_DECIBELS = 1.0;
	private final JTextField sweepPath = field("sweepPath", bundledSweepDefault());
	private final JTextField recordedSweepPath = field("recordedSweepPath", "");
	private final JButton run = new JButton("Apply");
	private final JButton preview = transportButton("▶", "Play preview", 78, 58, 30);
	private final JButton stopPreview = transportButton("■", "Stop preview", 78, 58, 44);
	private final JComboBox<MacAudioOutput.Device> audioOutput = new JComboBox<>();
	private final JButton refreshAudioOutputs = new JButton("Refresh");
	private final JCheckBox bypassPreview = new JCheckBox("Bypass (original audio)");
	private final JCheckBox loopPreview = check("loopPreview", false);
	private final JSlider previewPosition = new JSlider(0, 1000, 0);
	private final JLabel previewTime = new JLabel("0:00 / 0:00");
	private boolean updatingPreviewPosition;
	private final JButton generateIr = new JButton("Generate IR Profile");
	private final JButton addProfile = new JButton("Add…");
	private final JButton manageProfiles = new JButton("Manage…");
	private final JButton resetMix = new JButton("Reset Mix and Timing");
	private final JButton resetCaptured = new JButton("Reset Captured Response");
	private final JButton resetEq = new JButton("Reset EQ");
	private final JButton resetLeslie = new JButton("Reset Leslie");
	private final JButton playOutput = new JButton("Play Output");
	private final JButton showOutput = new JButton("Show in Finder");
	private final JButton deleteJob = new JButton("Delete…");
	private final JLabel status = new JLabel("Ready — processing stays on this Mac");
	private final JProgressBar progress = new JProgressBar(0, 100);
	private final JobTableModel jobs = new JobTableModel();
	private final JTable jobTable = new JTable(jobs);
	private boolean outputOverridden;
	private boolean synchronizingLiveControls;
	private boolean configuringAudioOutput;
	private transient Path latestOutput;

	StandaloneReverbFrame() {
		super("Mechana Effects");
		try {
			leslieSpeed.setSelectedItem(LeslieSettings.Speed.valueOf(settings.get("leslieSpeed", "SLOW")));
		} catch (IllegalArgumentException ignored) {
			leslieSpeed.setSelectedItem(LeslieSettings.Speed.SLOW);
		}
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		setMinimumSize(new Dimension(850, 650));
		setSize(980, 760);
		setLocationByPlatform(true);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent event) {
				previewPlayer.close();
				echoPreviewPlayer.close();
				lesliePreviewPlayer.close();
				engine.close();
				echoEngine.close();
				leslieEngine.close();
				dispose();
			}
		});
		add(buildHeader(), BorderLayout.NORTH);
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildTools(), buildHistory());
		split.setResizeWeight(0.62);
		add(split, BorderLayout.CENTER);
		add(buildStatus(), BorderLayout.SOUTH);
		configureAudioOutputs();
		configureLiveControls();
		configureActions();
		configureSuggestedName();
		reloadProfiles(path(irPath));
		reloadHistory();
	}

	private JPanel buildTools() {
		JPanel workspace = new JPanel(new BorderLayout(0, 6));
		workspace.add(buildSharedInputs(), BorderLayout.NORTH);
		JScrollPane form = new JScrollPane(buildForm());
		form.setBorder(BorderFactory.createEmptyBorder());
		form.getVerticalScrollBar().setUnitIncrement(18);
		effectTabs.addTab("Reverb", form);
		effectTabs.addTab("Echo", new JScrollPane(buildEchoForm()));
		effectTabs.addTab("Leslie", new JScrollPane(buildLeslieForm()));
		effectTabs.addTab("Create IR from Sweep", buildIrCreator());
		workspace.add(effectTabs, BorderLayout.CENTER);
		workspace.add(buildSharedActions(), BorderLayout.SOUTH);
		return workspace;
	}

	private JPanel buildSharedInputs() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createTitledBorder("New audio job"));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 8, 4, 8);
		c.gridy = 0;
		addPath(panel, c, "Dry audio", dryPath, false);
		addPath(panel, c, "Output folder", artifactRoot, true);
		addRow(panel, c, "Output WAV name", outputName);
		return panel;
	}

	private JPanel buildHeader() {
		JPanel panel = new JPanel(new BorderLayout(12, 0));
		panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
		panel.add(headerIcon(), BorderLayout.WEST);
		panel.add(new JLabel("<html><h2 style='margin:0'>Mechana Effects</h2>"
				+ "<div>Apply captured reverbs, modeled echoes, or a rotating-speaker effect to your audio.</div></html>"),
				BorderLayout.CENTER);
		return panel;
	}

	private static JLabel headerIcon() {
		java.net.URL resource = StandaloneReverbFrame.class.getResource("mechana-reverb-header.png");
		if (resource == null)
			return new JLabel();
		Image image = new ImageIcon(resource).getImage().getScaledInstance(56, 56, Image.SCALE_SMOOTH);
		JLabel label = new JLabel(new ImageIcon(image));
		label.getAccessibleContext().setAccessibleName("Mechana Reverb icon");
		return label;
	}

	private JPanel buildForm() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Reverb settings"),
				BorderFactory.createEmptyBorder(10, 8, 8, 8)));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(5, 8, 5, 8);
		c.gridy = 0;
		JPanel profileRow = new JPanel(new BorderLayout(8, 0));
		profileRow.add(profileSelector, BorderLayout.CENTER);
		JPanel profileActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
		profileActions.add(addProfile);
		profileActions.add(manageProfiles);
		profileRow.add(profileActions, BorderLayout.EAST);
		addRow(panel, c, "Impulse response", profileRow);
		addRow(panel, c, "Decay (seconds)", sliderWithOverride(decaySlider, decayLength));
		addSection(panel, c, "Mix and timing");
		addRow(panel, c, "Wet level (0–2)", sliderWithOverride(wetSlider, wet));
		addRow(panel, c, "Dry level (0–2)", sliderWithOverride(drySlider, dry));
		addRow(panel, c, "Pre-delay (0–200 ms slider)", sliderWithOverride(preDelaySlider, preDelay));
		addRow(panel, c, "", resetMix);
		addSection(panel, c, "Captured-response shaping");
		addRow(panel, c, "Early reflections level (0–2)", sliderWithOverride(earlySlider, earlyLevel));
		addRow(panel, c, "Late tail level (0–2)", sliderWithOverride(lateSlider, lateLevel));
		addRow(panel, c, "Attack (ms)", sliderWithOverride(attackSlider, attack));
		addRow(panel, c, "", resetCaptured);
		addSection(panel, c, "Wet EQ");
		addRow(panel, c, "Low-cut (Hz, 0 = off)", sliderWithOverride(lowCutSlider, lowCut));
		addRow(panel, c, "High-cut (Hz, 0 = off)", sliderWithOverride(highCutSlider, highCut));
		addRow(panel, c, "", resetEq);
		stopPreview.setEnabled(false);
		return panel;
	}

	private JPanel buildEchoForm() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Echo settings"),
				BorderFactory.createEmptyBorder(10, 8, 8, 8)));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(6, 8, 6, 8);
		c.gridy = 0;
		addRow(panel, c, "Echo model", echoModel);
		addRow(panel, c, "Delay (1–1500 ms slider)", sliderWithOverride(echoDelaySlider, echoDelay));
		addRow(panel, c, "Feedback (0–0.95 slider)", sliderWithOverride(echoFeedbackSlider, echoFeedback));
		addRow(panel, c, "Wet level (0–2)", sliderWithOverride(echoWetSlider, echoWet));
		addRow(panel, c, "Dry level (0–2)", sliderWithOverride(echoDrySlider, echoDry));
		addSection(panel, c, "Repeat color");
		addRow(panel, c, "Low-cut (Hz, 0 = off)", sliderWithOverride(echoLowCutSlider, echoLowCut));
		addRow(panel, c, "High-cut (Hz, 0 = off)", sliderWithOverride(echoHighCutSlider, echoHighCut));
		addRow(panel, c, "Saturation (0–1)", sliderWithOverride(echoSaturationSlider, echoSaturation));
		addSection(panel, c, "Modulation");
		addRow(panel, c, "Rate (0–10 Hz slider)", sliderWithOverride(echoRateSlider, echoRate));
		addRow(panel, c, "Depth (0–10 ms slider)", sliderWithOverride(echoDepthSlider, echoDepth));
		echoPingPong.setText("Stereo ping-pong");
		addRow(panel, c, "", echoPingPong);
		return panel;
	}

	private JPanel buildLeslieForm() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Leslie settings"),
				BorderFactory.createEmptyBorder(10, 8, 8, 8)));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(6, 8, 6, 8);
		c.gridy = 0;
		addRow(panel, c, "Rotor speed", leslieSpeed);
		addRow(panel, c, "Drive (0–1)", sliderWithOverride(leslieDriveSlider, leslieDrive));
		addRow(panel, c, "Horn balance (0–1)", sliderWithOverride(leslieHornSlider, leslieHornLevel));
		addRow(panel, c, "Mic distance (0–1)", sliderWithOverride(leslieMicSlider, leslieMicDistance));
		addRow(panel, c, "Stereo width (0–1)", sliderWithOverride(leslieWidthSlider, leslieStereoWidth));
		addRow(panel, c, "Crossover (200–2000 Hz)", sliderWithOverride(leslieCrossoverSlider, leslieCrossover));
		addSection(panel, c, "Mix");
		addRow(panel, c, "Wet level (0–2)", sliderWithOverride(leslieWetSlider, leslieWet));
		addRow(panel, c, "Dry level (0–2)", sliderWithOverride(leslieDrySlider, leslieDry));
		addRow(panel, c, "", resetLeslie);
		return panel;
	}

	private JPanel buildSharedActions() {
		JPanel wrapper = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(2, 8, 2, 8);
		c.gridy = 0;
		addActionBar(wrapper, c);
		return wrapper;
	}

	private void addActionBar(JPanel panel, GridBagConstraints c) {
		JPanel previewAndApply = new JPanel(new BorderLayout(0, 4));
		JPanel outputActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 10, 2));
		outputActions.add(new JLabel("Preview output:"));
		audioOutput.setPreferredSize(new Dimension(310, audioOutput.getPreferredSize().height));
		audioOutput.setToolTipText("Choose the macOS or AirPlay destination used by Preview");
		outputActions.add(audioOutput);
		refreshAudioOutputs.setToolTipText("Refresh available macOS and AirPlay outputs");
		outputActions.add(refreshAudioOutputs);
		previewAndApply.add(outputActions, BorderLayout.NORTH);
		JPanel actions = new JPanel(new BorderLayout(24, 0));
		JPanel previewActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 10, 5));
		previewActions.add(new JLabel("Preview:"));
		previewActions.add(preview);
		previewActions.add(stopPreview);
		loopPreview.setText("Loop");
		previewActions.add(loopPreview);
		previewActions.add(bypassPreview);
		actions.add(previewActions, BorderLayout.WEST);
		run.setToolTipText("Create a new processed WAV with the current settings");
		run.setFont(run.getFont().deriveFont(Font.BOLD, 17f));
		run.setPreferredSize(new Dimension(118, 50));
		JPanel applyAction = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 7));
		applyAction.add(run);
		actions.add(applyAction, BorderLayout.EAST);
		previewAndApply.add(actions, BorderLayout.CENTER);
		JPanel timeline = new JPanel(new BorderLayout(10, 0));
		previewPosition.setToolTipText("Preview position");
		previewPosition.getAccessibleContext().setAccessibleName("Preview position");
		timeline.add(previewPosition, BorderLayout.CENTER);
		previewTime.setPreferredSize(new Dimension(92, previewTime.getPreferredSize().height));
		timeline.add(previewTime, BorderLayout.EAST);
		previewAndApply.add(timeline, BorderLayout.SOUTH);
		addRow(panel, c, "", previewAndApply);
	}

	private void configureAudioOutputs() {
		String savedName = settings.get("previewAudioOutput", "macOS Selected Output (including AirPlay)");
		reloadAudioOutputs(savedName);
		applyAudioOutput(false);
		audioOutput.addActionListener(event -> {
			if (!configuringAudioOutput)
				applyAudioOutput(true);
		});
		refreshAudioOutputs.addActionListener(event -> {
			MacAudioOutput.Device selected = (MacAudioOutput.Device) audioOutput.getSelectedItem();
			reloadAudioOutputs(selected == null ? "" : selected.name());
			applyAudioOutput(true);
		});
	}

	private void reloadAudioOutputs(String selectedName) {
		configuringAudioOutput = true;
		audioOutput.removeAllItems();
		MacAudioOutput.Device selected = null;
		for (MacAudioOutput.Device device : MacAudioOutput.devices()) {
			audioOutput.addItem(device);
			if (device.name().equals(selectedName))
				selected = device;
		}
		if (selected != null)
			audioOutput.setSelectedItem(selected);
		configuringAudioOutput = false;
	}

	private void applyAudioOutput(boolean restartActivePreview) {
		MacAudioOutput.Device selected = (MacAudioOutput.Device) audioOutput.getSelectedItem();
		if (selected == null)
			return;
		settings.put("previewAudioOutput", selected.name());
		previewPlayer.setAudioSinkFactory(MacAudioOutput.sinkFactory(selected));
		echoPreviewPlayer.setAudioSinkFactory(MacAudioOutput.sinkFactory(selected));
		lesliePreviewPlayer.setAudioSinkFactory(MacAudioOutput.sinkFactory(selected));
		if (restartActivePreview && previewActive()) {
			previewPlayer.stop();
			echoPreviewPlayer.stop();
			lesliePreviewPlayer.stop();
			previewFinished();
			status.setText("Switching Preview to " + selected.name() + "…");
			startPreview();
		}
	}

	private JPanel buildIrCreator() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Create an impulse response from a hardware sweep recording"),
				BorderFactory.createEmptyBorder(10, 8, 8, 8)));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(7, 8, 7, 8);
		c.gridy = 0;
		addPath(panel, c, "Mechana source sweep", sweepPath, false);
		addPath(panel, c, "Recorded wet sweep return", recordedSweepPath, false);
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
				+ "trailing silence, then select that recording here. After generation, choose whether to add the IR "
				+ "to the profile library or save it as a WAV file.</html>"), c);
		return panel;
	}

	private JScrollPane buildHistory() {
		jobTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		jobTable.setFillsViewportHeight(true);
		jobTable.getColumnModel().getColumn(0).setPreferredWidth(150);
		jobTable.getColumnModel().getColumn(1).setPreferredWidth(80);
		jobTable.getColumnModel().getColumn(2).setPreferredWidth(280);
		jobTable.getColumnModel().getColumn(3).setPreferredWidth(620);
		jobTable.getSelectionModel().addListSelectionListener(event -> {
			if (!event.getValueIsAdjusting())
				selectHistoryOutput();
		});
		JPanel panel = new JPanel(new BorderLayout(8, 8));
		panel.setBorder(BorderFactory.createTitledBorder("History"));
		panel.add(new JScrollPane(jobTable), BorderLayout.CENTER);
		JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
		actions.add(playOutput);
		actions.add(showOutput);
		actions.add(deleteJob);
		panel.add(actions, BorderLayout.NORTH);
		playOutput.setEnabled(false);
		showOutput.setEnabled(false);
		deleteJob.setEnabled(false);
		JScrollPane wrapper = new JScrollPane(panel);
		wrapper.setBorder(BorderFactory.createEmptyBorder());
		return wrapper;
	}

	private JPanel buildStatus() {
		JPanel panel = new JPanel(new BorderLayout(8, 8));
		panel.setBorder(BorderFactory.createEmptyBorder(8, 16, 14, 16));
		progress.setStringPainted(true);
		panel.add(status, BorderLayout.WEST);
		panel.add(progress, BorderLayout.CENTER);
		return panel;
	}

	private void configureActions() {
		irPreviewChangeTimer.setRepeats(false);
		dryPreviewChangeTimer.setRepeats(false);
		run.addActionListener(event -> submit());
		preview.addActionListener(event -> {
			if (isLeslieSelected() && lesliePreviewPlayer.isActive())
				lesliePreviewPlayer.togglePause(state -> SwingUtilities.invokeLater(() -> updatePreviewState(state)));
			else if (isEchoSelected() && echoPreviewPlayer.isActive())
				echoPreviewPlayer.togglePause(state -> SwingUtilities.invokeLater(() -> updatePreviewState(state)));
			else if (previewPlayer.isActive())
				previewPlayer.togglePause(state -> SwingUtilities.invokeLater(() -> updatePreviewState(state)));
			else
				startPreview();
		});
		previewPlayer.onPosition(position -> SwingUtilities.invokeLater(() -> updatePreviewPosition(position)));
		echoPreviewPlayer.onPosition(position -> SwingUtilities.invokeLater(() -> updatePreviewPosition(position)));
		lesliePreviewPlayer.onPosition(position -> SwingUtilities.invokeLater(() -> updatePreviewPosition(position)));
		previewPosition.addChangeListener(event -> {
			if (updatingPreviewPosition || previewPosition.getValueIsAdjusting() || !previewActive())
				return;
			restartPreviewAt(previewPosition.getValue() / 1000.0);
		});
		stopPreview.addActionListener(event -> stopPreview());
		bypassPreview.addActionListener(event -> {
			previewPlayer.setBypassed(bypassPreview.isSelected());
			echoPreviewPlayer.setBypassed(bypassPreview.isSelected());
			lesliePreviewPlayer.setBypassed(bypassPreview.isSelected());
			if (isLeslieSelected() && lesliePreviewPlayer.isActive())
				status.setText(bypassPreview.isSelected()
						? "Preview bypassed — playing original audio"
						: "Leslie preview active");
			else if (isEchoSelected() && echoPreviewPlayer.isActive())
				status.setText(bypassPreview.isSelected()
						? "Preview bypassed — playing original audio"
						: "Echo preview active");
			else if (previewPlayer.isActive())
				status.setText(bypassPreview.isSelected()
						? "Preview bypassed — playing original audio"
						: "Reverb preview active");
		});
		loopPreview.addActionListener(event -> {
			previewPlayer.setLooping(loopPreview.isSelected());
			echoPreviewPlayer.setLooping(loopPreview.isSelected());
			lesliePreviewPlayer.setLooping(loopPreview.isSelected());
			if (previewActive())
				status.setText(loopPreview.isSelected() ? "Preview will loop until stopped" : "Preview loop disabled");
		});
		generateIr.addActionListener(event -> generateImpulseResponse());
		addProfile.addActionListener(event -> addProfile());
		manageProfiles.addActionListener(event -> manageProfiles());
		profileSelector.addActionListener(event -> selectProfile());
		resetMix.addActionListener(event -> resetMixAndTiming());
		resetCaptured.addActionListener(event -> resetCapturedResponse());
		resetEq.addActionListener(event -> resetEqualizer());
		resetLeslie.addActionListener(event -> resetLeslie());
		playOutput.addActionListener(event -> openLatestOutput(false));
		showOutput.addActionListener(event -> openLatestOutput(true));
		deleteJob.addActionListener(event -> deleteSelectedJob());
		for (JTextField field : List.of(wet, dry, preDelay, lowCut, highCut, earlyLevel, lateLevel, attack,
				decayLength))
			field.getDocument().addDocumentListener(listener(this::updatePreviewParameters));
		for (JTextField field : List.of(earlyLevel, lateLevel, attack, decayLength))
			field.getDocument().addDocumentListener(listener(() -> {
				if (previewPlayer.isActive())
					irPreviewChangeTimer.restart();
			}));
		irPath.getDocument().addDocumentListener(listener(() -> {
			if (previewPlayer.isActive())
				irPreviewChangeTimer.restart();
		}));
		dryPath.getDocument().addDocumentListener(listener(() -> {
			if (previewActive())
				dryPreviewChangeTimer.restart();
		}));
		artifactRoot.getDocument().addDocumentListener(listener(this::reloadHistory));
		effectTabs.addChangeListener(event -> {
			boolean wasActive = previewActive();
			double position = previewPosition.getValue() / 1000.0;
			stopPreview();
			run.setEnabled(effectTabs.getSelectedIndex() < 3);
			if (effectTabs.getSelectedIndex() < 3) {
				outputOverridden = false;
				updateSuggestedName();
				if (wasActive)
					startPreview(position);
			}
		});
		for (JTextField field : List.of(echoDelay, echoFeedback, echoWet, echoDry, echoLowCut, echoHighCut,
				echoSaturation, echoRate, echoDepth))
			field.getDocument().addDocumentListener(listener(this::echoParametersChanged));
		echoModel.addActionListener(event -> applyEchoModelDefaults());
		echoPingPong.addActionListener(event -> echoParametersChanged());
		for (JTextField field : List.of(leslieDrive, leslieHornLevel, leslieMicDistance, leslieStereoWidth,
				leslieCrossover, leslieWet, leslieDry))
			field.getDocument().addDocumentListener(listener(this::leslieParametersChanged));
		leslieSpeed.addActionListener(event -> {
			LeslieSettings.Speed selected = (LeslieSettings.Speed) leslieSpeed.getSelectedItem();
			if (selected != null)
				settings.put("leslieSpeed", selected.name());
			leslieParametersChanged();
		});
	}

	private void configureLiveControls() {
		configureLiveControl(wetSlider, wet, 100);
		configureLiveControl(drySlider, dry, 100);
		configureLiveControl(preDelaySlider, preDelay, 1);
		configureFrequencyControl(lowCutSlider, lowCut);
		configureFrequencyControl(highCutSlider, highCut);
		configureLiveControl(earlySlider, earlyLevel, 100);
		configureLiveControl(lateSlider, lateLevel, 100);
		configureLiveControl(attackSlider, attack, 1);
		configureLiveControl(decaySlider, decayLength, 100);
		configureLiveControl(echoDelaySlider, echoDelay, 1);
		configureLiveControl(echoFeedbackSlider, echoFeedback, 100);
		configureLiveControl(echoWetSlider, echoWet, 100);
		configureLiveControl(echoDrySlider, echoDry, 100);
		configureFrequencyControl(echoLowCutSlider, echoLowCut);
		configureFrequencyControl(echoHighCutSlider, echoHighCut);
		configureLiveControl(echoSaturationSlider, echoSaturation, 100);
		configureLiveControl(echoRateSlider, echoRate, 100);
		configureLiveControl(echoDepthSlider, echoDepth, 100);
		configureLiveControl(leslieDriveSlider, leslieDrive, 100);
		configureLiveControl(leslieHornSlider, leslieHornLevel, 100);
		configureLiveControl(leslieMicSlider, leslieMicDistance, 100);
		configureLiveControl(leslieWidthSlider, leslieStereoWidth, 100);
		configureLiveControl(leslieCrossoverSlider, leslieCrossover, 1);
		configureLiveControl(leslieWetSlider, leslieWet, 100);
		configureLiveControl(leslieDrySlider, leslieDry, 100);
	}

	private void configureFrequencyControl(JSlider slider, JTextField override) {
		override.setColumns(7);
		slider.addChangeListener(event -> {
			if (synchronizingLiveControls)
				return;
			synchronizingLiveControls = true;
			try {
				override.setText(Integer.toString(sliderFrequency(slider.getValue())));
			} finally {
				synchronizingLiveControls = false;
			}
		});
		override.getDocument().addDocumentListener(listener(() -> {
			if (synchronizingLiveControls)
				return;
			try {
				int value = frequencySliderValue(Double.parseDouble(override.getText().strip()));
				if (value != slider.getValue()) {
					synchronizingLiveControls = true;
					try {
						slider.setValue(value);
					} finally {
						synchronizingLiveControls = false;
					}
				}
			} catch (NumberFormatException ignored) {
				// A partially edited value keeps the last slider position.
			}
		}));
	}

	private void configureLiveControl(JSlider slider, JTextField override, int scale) {
		override.setColumns(7);
		slider.addChangeListener(event -> {
			if (synchronizingLiveControls)
				return;
			synchronizingLiveControls = true;
			try {
				override.setText(sliderText(slider.getValue(), scale));
			} finally {
				synchronizingLiveControls = false;
			}
		});
		override.getDocument().addDocumentListener(listener(() -> {
			if (synchronizingLiveControls)
				return;
			try {
				int value = (int) Math.round(Double.parseDouble(override.getText().strip()) * scale);
				int boundedValue = Math.max(slider.getMinimum(), Math.min(slider.getMaximum(), value));
				if (boundedValue != slider.getValue()) {
					synchronizingLiveControls = true;
					try {
						slider.setValue(boundedValue);
					} finally {
						synchronizingLiveControls = false;
					}
				}
			} catch (NumberFormatException ignored) {
				// Keep the last slider value while the override is partially edited.
			}
		}));
	}

	private static JPanel sliderWithOverride(JSlider slider, JTextField override) {
		JPanel panel = new JPanel(new BorderLayout(8, 0));
		panel.add(slider, BorderLayout.CENTER);
		panel.add(override, BorderLayout.EAST);
		return panel;
	}

	private static int sliderValue(JTextField field, int scale, int fallback) {
		try {
			int value = (int) Math.round(Double.parseDouble(field.getText().strip()) * scale);
			return Math.max(0, Math.min(scale == 1 ? 10_000 : 200, value));
		} catch (NumberFormatException invalid) {
			return fallback;
		}
	}

	private static int boundedSliderValue(JTextField field, int scale, int fallback, int minimum, int maximum) {
		try {
			int value = (int) Math.round(Double.parseDouble(field.getText().strip()) * scale);
			return Math.max(minimum, Math.min(maximum, value));
		} catch (NumberFormatException invalid) {
			return fallback;
		}
	}

	private static int frequencySliderValue(JTextField field) {
		try {
			return frequencySliderValue(Double.parseDouble(field.getText().strip()));
		} catch (NumberFormatException invalid) {
			return 0;
		}
	}

	private static int frequencySliderValue(double hertz) {
		if (hertz <= 0)
			return 0;
		double position = 1 + 999 * Math.log(hertz / 20) / Math.log(20_000.0 / 20);
		return (int) Math.max(1, Math.min(1000, Math.round(position)));
	}

	private static int sliderFrequency(int value) {
		return value == 0 ? 0 : (int) Math.round(20 * Math.pow(20_000.0 / 20, (value - 1) / 999.0));
	}

	static String sliderText(int value, int scale) {
		return scale == 1 ? Integer.toString(value) : BigDecimal.valueOf(value, 2).stripTrailingZeros().toPlainString();
	}

	private void generateImpulseResponse() {
		stopPreview();
		Path sweep = path(sweepPath);
		Path recorded = path(recordedSweepPath);
		if (sweep == null || recorded == null) {
			showError("Choose the source sweep and recorded wet return.");
			return;
		}
		generateIr.setEnabled(false);
		run.setEnabled(false);
		status.setText("Generating impulse response…");
		progress.setValue(0);
		Thread.ofVirtual().name("mechana-ir-deconvolution").start(() -> {
			Path temporaryDirectory = null;
			try {
				temporaryDirectory = Files.createTempDirectory("mechana-generated-ir-");
				Path output = temporaryDirectory.resolve(generatedIrName(recorded));
				SweepDeconvolver.Result result = new SweepDeconvolver().deconvolve(sweep, recorded, output,
						percent -> SwingUtilities.invokeLater(() -> progress.setValue(percent)));
				Path generatedDirectory = temporaryDirectory;
				SwingUtilities.invokeLater(
						() -> finishGeneratedImpulseResponse(sweep, recorded, output, generatedDirectory, result));
			} catch (IOException | RuntimeException failure) {
				cleanupGeneratedIr(temporaryDirectory);
				SwingUtilities.invokeLater(() -> {
					showError(failure.getMessage());
					status.setText("IR generation failed");
					generationFinished();
				});
			}
		});
	}

	private void finishGeneratedImpulseResponse(Path sweep, Path recorded, Path output, Path temporaryDirectory,
			SweepDeconvolver.Result result) {
		Object[] choices = {"Add to Library", "Save to File…", "Cancel"};
		int choice = JOptionPane.showOptionDialog(this,
				"The impulse response is ready. What would you like to do with it?", "Generated IR Profile",
				JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
		try {
			if (choice == 0) {
				String outputFileName = Objects.requireNonNull(output.getFileName(), "generated IR filename")
						.toString();
				String requestedName = promptForGeneratedProfileName(outputFileName);
				if (requestedName == null)
					status.setText("Generated IR was not added to the library");
				else {
					IrProfileLibrary.Profile generated = addGeneratedProfile(output, requestedName);
					if (generated == null)
						status.setText("Generated IR was not added to the library");
					else {
						writeGenerationReport(generated.path(), sweep, recorded, result);
						reloadProfiles(generated.path());
						status.setText("Added IR profile — " + generated.name());
					}
				}
			} else if (choice == 1) {
				Path saved = chooseGeneratedIrDestination(
						Objects.requireNonNull(output.getFileName(), "generated IR filename").toString());
				if (saved == null)
					status.setText("Generated IR was not saved");
				else {
					Files.copy(output, saved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					writeGenerationReport(saved, sweep, recorded, result);
					status.setText("Saved IR — " + saved.getFileName());
				}
			} else
				status.setText("Generated IR discarded");
		} catch (IOException failure) {
			showError(failure.getMessage());
			status.setText("Could not keep the generated IR");
		} finally {
			cleanupGeneratedIr(temporaryDirectory);
			generationFinished();
		}
	}

	private IrProfileLibrary.Profile addGeneratedProfile(Path output, String requestedName) throws IOException {
		if (!profileLibrary.containsName(requestedName))
			return profileLibrary.addGenerated(output, requestedName);
		if (profileLibrary.isFactoryName(requestedName)) {
			Object[] choices = {"Keep Both", "Cancel"};
			int choice = JOptionPane.showOptionDialog(this,
					"A factory profile already uses this name and cannot be replaced.", "Profile Already Exists",
					JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, choices, choices[0]);
			return choice == 0 ? profileLibrary.addGenerated(output, requestedName) : null;
		}
		Object[] choices = {"Replace Existing", "Keep Both", "Cancel"};
		int choice = JOptionPane.showOptionDialog(this, "An IR profile with this name already exists.",
				"Profile Already Exists", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, choices,
				choices[0]);
		return switch (choice) {
			case 0 -> profileLibrary.addGenerated(output, requestedName, true);
			case 1 -> profileLibrary.addGenerated(output, requestedName);
			default -> null;
		};
	}

	private String promptForGeneratedProfileName(String suggestedName) {
		String withoutExtension = suggestedName.replaceFirst("(?i)\\.wav$", "");
		while (true) {
			String value = (String) JOptionPane.showInputDialog(this,
					"Name this impulse-response profile before adding it to the library:", "Add IR to Library",
					JOptionPane.PLAIN_MESSAGE, null, null, withoutExtension);
			if (value == null)
				return null;
			if (!value.isBlank())
				return value.strip();
			JOptionPane.showMessageDialog(this, "Enter a name for the IR profile.", "Add IR to Library",
					JOptionPane.WARNING_MESSAGE);
		}
	}

	private Path chooseGeneratedIrDestination(String suggestedName) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Save generated impulse response");
		chooser.setFileFilter(new FileNameExtensionFilter("WAV impulse response (.wav)", "wav"));
		chooser.setSelectedFile(new File(suggestedName));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
			return null;
		String selected = chooser.getSelectedFile().getAbsolutePath();
		Path destination = Path
				.of(selected.toLowerCase(java.util.Locale.ROOT).endsWith(".wav") ? selected : selected + ".wav");
		if (Files.exists(destination)
				&& JOptionPane.showConfirmDialog(this, "Replace the existing file?\n" + destination, "Replace File",
						JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION)
			return null;
		return destination;
	}

	private static String generatedIrName(Path recorded) {
		String name = Objects.requireNonNull(recorded.getFileName(), "recorded sweep filename").toString()
				.replaceFirst("(?i)\\.(?:wav|wave)$", "").replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
		return (name.isBlank() ? "captured-reverb" : name) + "-IR.wav";
	}

	private static void writeGenerationReport(Path output, Path sweep, Path recorded, SweepDeconvolver.Result result)
			throws IOException {
		Files.writeString(output.resolveSibling(output.getFileName() + ".txt"),
				"Mechana impulse-response generation\n\nOriginal sweep: " + sweep + "\nRecorded wet return: " + recorded
						+ "\nOutput IR: " + output + "\nSample rate: " + result.sampleRate() + " Hz\nChannels: "
						+ result.channels() + "\nFrames: " + result.frames() + "\nCapture latency: "
						+ result.latencyMilliseconds() + " ms\nRecovered peak: " + result.peak()
						+ "\nAlgorithm: regularized FFT deconvolution\n");
	}

	private static void cleanupGeneratedIr(Path temporaryDirectory) {
		if (temporaryDirectory == null)
			return;
		try (var files = Files.list(temporaryDirectory)) {
			for (Path file : files.toList())
				Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// Temporary cleanup failure does not invalidate a saved or library IR.
		}
		try {
			Files.deleteIfExists(temporaryDirectory);
		} catch (IOException ignored) {
			// Temporary cleanup failure does not invalidate a saved or library IR.
		}
	}

	private void addProfile() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Add an impulse response to the library");
		chooser.setFileFilter(new FileNameExtensionFilter("WAV impulse responses (.wav)", "wav"));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		try {
			IrProfileLibrary.Profile added = profileLibrary.add(chooser.getSelectedFile().toPath());
			reloadProfiles(added.path());
			status.setText("Added IR profile — " + added.name());
		} catch (IOException failure) {
			showError(failure.getMessage());
		}
	}

	private void manageProfiles() {
		JDialog dialog = new JDialog(this, "Manage Impulse Responses", true);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLayout(new BorderLayout(10, 10));
		DefaultListModel<IrProfileLibrary.Profile> profileModel = new DefaultListModel<>();
		JList<IrProfileLibrary.Profile> profiles = new JList<>(profileModel);
		profiles.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		profiles.setVisibleRowCount(9);
		IrProfileLibrary.Profile current = (IrProfileLibrary.Profile) profileSelector.getSelectedItem();
		reloadManagedProfiles(profileModel, profiles, current == null ? null : current.path());
		JPanel selection = new JPanel(new BorderLayout(8, 8));
		selection.setBorder(BorderFactory.createEmptyBorder(16, 16, 4, 16));
		selection.add(new JLabel("Impulse-response profiles:"), BorderLayout.NORTH);
		selection.add(new JScrollPane(profiles), BorderLayout.CENTER);
		JLabel kind = new JLabel();
		selection.add(kind, BorderLayout.SOUTH);
		dialog.add(selection, BorderLayout.CENTER);

		JButton rename = new JButton("Rename…");
		JButton delete = new JButton("Delete…");
		JButton export = new JButton("Export…");
		JButton reveal = new JButton("Show in Finder");
		JButton close = new JButton("Done");
		Runnable updateActions = () -> {
			IrProfileLibrary.Profile selected = profiles.getSelectedValue();
			boolean editable = selected != null && !selected.factory();
			rename.setEnabled(editable);
			delete.setEnabled(editable);
			export.setEnabled(selected != null);
			reveal.setEnabled(editable);
			kind.setText(selected == null
					? " "
					: selected.factory() ? "Factory profile — protected" : "Added profile — editable");
		};
		profiles.addListSelectionListener(event -> {
			if (!event.getValueIsAdjusting())
				updateActions.run();
		});
		rename.addActionListener(event -> {
			IrProfileLibrary.Profile selected = profiles.getSelectedValue();
			if (selected == null || selected.factory())
				return;
			try {
				IrProfileLibrary.Profile renamed = renameProfile(selected);
				if (renamed != null)
					reloadManagedProfiles(profileModel, profiles, renamed.path());
			} catch (IOException failure) {
				showError(failure.getMessage());
			}
		});
		delete.addActionListener(event -> {
			IrProfileLibrary.Profile selected = profiles.getSelectedValue();
			if (selected == null || selected.factory())
				return;
			try {
				if (deleteProfile(selected))
					reloadManagedProfiles(profileModel, profiles, path(irPath));
			} catch (IOException failure) {
				showError(failure.getMessage());
			}
		});
		export.addActionListener(event -> exportProfile(profiles.getSelectedValue()));
		reveal.addActionListener(event -> {
			IrProfileLibrary.Profile selected = profiles.getSelectedValue();
			if (selected != null)
				showInFinder(selected.path());
		});
		close.addActionListener(event -> dialog.dispose());
		JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 8));
		actions.add(rename);
		actions.add(delete);
		actions.add(export);
		actions.add(reveal);
		actions.add(close);
		dialog.add(actions, BorderLayout.SOUTH);
		updateActions.run();
		dialog.getRootPane().setDefaultButton(close);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(680, 380));
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private void reloadManagedProfiles(DefaultListModel<IrProfileLibrary.Profile> model,
			JList<IrProfileLibrary.Profile> profiles, Path selection) {
		try {
			model.clear();
			int selectedIndex = -1;
			for (IrProfileLibrary.Profile profile : profileLibrary.profiles()) {
				model.addElement(profile);
				if (selection != null && profile.path().equals(selection.toAbsolutePath().normalize()))
					selectedIndex = model.size() - 1;
			}
			if (selectedIndex >= 0)
				profiles.setSelectedIndex(selectedIndex);
			else if (!model.isEmpty())
				profiles.setSelectedIndex(0);
			if (profiles.getSelectedIndex() >= 0)
				profiles.ensureIndexIsVisible(profiles.getSelectedIndex());
		} catch (IOException failure) {
			showError("Could not load the IR profile library: " + failure.getMessage());
		}
	}

	private IrProfileLibrary.Profile renameProfile(IrProfileLibrary.Profile profile) throws IOException {
		String filename = Objects.requireNonNull(profile.path().getFileName(), "IR profile filename").toString();
		String suggestedName = filename.replaceFirst("(?i)\\.wav$", "");
		String requestedName = (String) JOptionPane.showInputDialog(this, "Enter a new name for this IR profile:",
				"Rename IR Profile", JOptionPane.PLAIN_MESSAGE, null, null, suggestedName);
		if (requestedName == null)
			return null;
		if (requestedName.isBlank()) {
			JOptionPane.showMessageDialog(this, "Enter a name for the IR profile.", "Rename IR Profile",
					JOptionPane.WARNING_MESSAGE);
			return null;
		}
		IrProfileLibrary.Profile renamed = profileLibrary.rename(profile, requestedName.strip());
		reloadProfiles(renamed.path());
		status.setText("Renamed IR profile — " + renamed.name());
		return renamed;
	}

	private boolean deleteProfile(IrProfileLibrary.Profile profile) throws IOException {
		int confirmed = JOptionPane.showConfirmDialog(this,
				"Delete the added IR profile ‘" + profile.name() + "’?\nThis cannot be undone.", "Delete IR Profile",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirmed != JOptionPane.YES_OPTION)
			return false;
		profileLibrary.remove(profile);
		reloadProfiles(null);
		status.setText("Deleted IR profile — " + profile.name());
		return true;
	}

	private void exportProfile(IrProfileLibrary.Profile profile) {
		if (profile == null)
			return;
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Export impulse-response profile");
		chooser.setFileFilter(new FileNameExtensionFilter("WAV impulse response (.wav)", "wav"));
		chooser.setSelectedFile(
				new File(Objects.requireNonNull(profile.path().getFileName(), "IR filename").toString()));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		Path destination = chooser.getSelectedFile().toPath();
		if (!destination.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".wav"))
			destination = Path.of(destination + ".wav");
		if (Files.exists(destination) && JOptionPane.showConfirmDialog(this, "Replace the existing file?",
				"Export IR Profile", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION)
			return;
		try {
			Files.copy(profile.path(), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			status.setText("Exported IR profile — " + destination.getFileName());
		} catch (IOException failure) {
			showError(failure.getMessage());
		}
	}

	private void showInFinder(Path file) {
		try {
			if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac"))
				new ProcessBuilder("/usr/bin/open", "-R", file.toString()).start();
			else
				Desktop.getDesktop().open(Objects.requireNonNull(file.getParent(), "IR profile folder").toFile());
		} catch (IOException | UnsupportedOperationException failure) {
			showError(failure.getMessage());
		}
	}

	private void reloadProfiles(Path selection) {
		try {
			profileSelector.removeAllItems();
			IrProfileLibrary.Profile selected = null;
			Path selectionFileName = selection == null ? null : selection.getFileName();
			for (IrProfileLibrary.Profile profile : profileLibrary.profiles()) {
				profileSelector.addItem(profile);
				if (selection != null && (profile.path().equals(selection.toAbsolutePath().normalize())
						|| selectionFileName != null && selectionFileName.equals(profile.path().getFileName())))
					selected = profile;
			}
			if (selected != null)
				profileSelector.setSelectedItem(selected);
			else if (profileSelector.getItemCount() > 0)
				profileSelector.setSelectedIndex(0);
			selectProfile();
		} catch (IOException failure) {
			showError("Could not load the IR profile library: " + failure.getMessage());
		}
	}

	private void selectProfile() {
		IrProfileLibrary.Profile selected = (IrProfileLibrary.Profile) profileSelector.getSelectedItem();
		if (selected != null && !selected.path().toString().equals(irPath.getText()))
			irPath.setText(selected.path().toString());
	}

	private void resetCapturedResponse() {
		earlyLevel.setText("1");
		lateLevel.setText("1");
		attack.setText("0");
		decayLength.setText(compact(irDurationSeconds()));
	}

	private void resetMixAndTiming() {
		wet.setText("0");
		dry.setText("1");
		preDelay.setText("0");
	}

	private void resetEqualizer() {
		lowCut.setText("0");
		highCut.setText("0");
	}

	private void resetLeslie() {
		LeslieSettings defaults = LeslieSettings.defaults();
		leslieSpeed.setSelectedItem(defaults.speed());
		leslieDrive.setText(Double.toString(defaults.drive()));
		leslieHornLevel.setText(Double.toString(defaults.hornLevel()));
		leslieMicDistance.setText(Double.toString(defaults.micDistance()));
		leslieStereoWidth.setText(Double.toString(defaults.stereoWidth()));
		leslieCrossover.setText(Double.toString(defaults.crossoverHertz()));
		leslieWet.setText(Double.toString(defaults.wet()));
		leslieDry.setText(Double.toString(defaults.dry()));
	}

	private void generationFinished() {
		generateIr.setEnabled(true);
		run.setEnabled(true);
	}

	private void configureSuggestedName() {
		outputOverridden = !"reverberated.wav".equals(outputName.getText());
		outputName.getDocument().addDocumentListener(listener(() -> outputOverridden = true));
		for (JTextField field : List.of(dryPath, irPath))
			field.getDocument().addDocumentListener(listener(() -> {
				outputOverridden = false;
				updateSuggestedName();
			}));
		for (JTextField field : List.of(wet, dry, preDelay))
			field.getDocument().addDocumentListener(listener(this::updateSuggestedName));
		for (JTextField field : List.of(echoDelay, echoFeedback, echoWet, echoDry))
			field.getDocument().addDocumentListener(listener(this::updateSuggestedName));
		for (JTextField field : List.of(leslieWet, leslieDry))
			field.getDocument().addDocumentListener(listener(this::updateSuggestedName));
		updateSuggestedName();
	}

	private void updateSuggestedName() {
		if (outputOverridden || dryPath.getText().isBlank())
			return;
		String suggested;
		if (isLeslieSelected())
			suggested = stem(dryPath.getText(), "audio") + "-leslie-" + leslieSpeedToken() + "-wet"
					+ token(leslieWet.getText()) + "-dry" + token(leslieDry.getText()) + ".wav";
		else if (isEchoSelected())
			suggested = stem(dryPath.getText(), "audio") + "-echo-" + echoModelToken() + "-delay"
					+ token(echoDelay.getText()) + "ms-fb" + token(echoFeedback.getText()) + "-wet"
					+ token(echoWet.getText()) + "-dry" + token(echoDry.getText()) + ".wav";
		else {
			if (irPath.getText().isBlank())
				return;
			suggested = suggestedOutputName(dryPath.getText(), irPath.getText(), wet.getText(), dry.getText(),
					preDelay.getText());
		}
		outputName.setText(suggested);
		outputOverridden = false;
	}

	private String echoModelToken() {
		return echoModel.getSelectedItem() == EchoSettings.Model.ANALOG ? "analog-memory" : "vintage-tape";
	}

	private String leslieSpeedToken() {
		LeslieSettings.Speed selected = (LeslieSettings.Speed) leslieSpeed.getSelectedItem();
		if (selected == null)
			return "slow";
		return switch (selected) {
			case STOPPED -> "stopped";
			case FAST -> "fast";
			case SLOW -> "slow";
		};
	}

	private void submit() {
		stopPreview();
		if (isLeslieSelected()) {
			submitLeslie();
			return;
		}
		if (isEchoSelected()) {
			submitEcho();
			return;
		}
		try {
			ReverbRequest request = new ReverbRequest(path(dryPath), path(irPath), path(artifactRoot),
					outputName.getText().strip(), decimal(wet, "Wet level"), decimal(dry, "Dry level"),
					decimal(preDelay, "Pre-delay"), decimal(lowCut, "Wet low-cut"), decimal(highCut, "Wet high-cut"),
					decimal(earlyLevel, "Early reflections level"), decimal(lateLevel, "Late tail level"),
					decimal(attack, "Attack"), decayLengthPercent(), AUTOMATIC_IR_PEAK_SAFETY,
					AUTOMATIC_PEAK_PROTECTION, AUTOMATIC_HEADROOM_DECIBELS, calibrationGain(path(irPath)));
			engine.submit(request, job -> SwingUtilities.invokeLater(() -> update(job)));
			run.setEnabled(false);
		} catch (IOException | RuntimeException failure) {
			showError(failure.getMessage());
		}
	}

	private void submitEcho() {
		try {
			Path source = path(dryPath);
			if (source == null || !Files.isRegularFile(source))
				throw new IllegalArgumentException("Choose a readable dry audio file.");
			echoEngine.submit(source, path(artifactRoot), outputName.getText().strip(), echoSettings(),
					job -> SwingUtilities.invokeLater(() -> update(job)));
			run.setEnabled(false);
		} catch (IOException | RuntimeException failure) {
			showError(failure.getMessage());
		}
	}

	private void submitLeslie() {
		try {
			Path source = path(dryPath);
			if (source == null || !Files.isRegularFile(source))
				throw new IllegalArgumentException("Choose a readable dry audio file.");
			leslieEngine.submit(source, path(artifactRoot), outputName.getText().strip(), leslieSettings(),
					job -> SwingUtilities.invokeLater(() -> update(job)));
			run.setEnabled(false);
		} catch (IOException | RuntimeException failure) {
			showError(failure.getMessage());
		}
	}

	private void startPreview() {
		startPreview(0);
	}

	private void startPreview(double startFraction) {
		if (isLeslieSelected()) {
			startLesliePreview(startFraction);
			return;
		}
		if (isEchoSelected()) {
			startEchoPreview(startFraction);
			return;
		}
		try {
			Path selectedDry = path(dryPath);
			Path selectedIr = path(irPath);
			if (selectedDry == null || !Files.isRegularFile(selectedDry))
				throw new IllegalArgumentException("Choose a readable dry audio file.");
			if (selectedIr == null || !Files.isRegularFile(selectedIr))
				throw new IllegalArgumentException("Choose a readable impulse-response WAV.");
			var settings = new ReverbPreviewPlayer.Settings(selectedDry, selectedIr, decimal(wet, "Wet level"),
					decimal(dry, "Dry level"), decimal(preDelay, "Pre-delay"), decimal(lowCut, "Wet low-cut"),
					decimal(highCut, "Wet high-cut"), decimal(earlyLevel, "Early reflections level"),
					decimal(lateLevel, "Late tail level"), decimal(attack, "Attack"), decayLengthPercent(),
					AUTOMATIC_IR_PEAK_SAFETY, AUTOMATIC_PEAK_PROTECTION, AUTOMATIC_HEADROOM_DECIBELS,
					calibrationGain(selectedIr));
			previewPlayer.setBypassed(bypassPreview.isSelected());
			previewPlayer.setLooping(loopPreview.isSelected());
			previewPlayer.play(settings, startFraction,
					state -> SwingUtilities.invokeLater(() -> updatePreviewState(state)),
					message -> SwingUtilities.invokeLater(() -> {
						showError(message);
						status.setText("Preview failed");
						previewFinished();
					}));
		} catch (RuntimeException failure) {
			showError(failure.getMessage());
		}
	}

	private void startEchoPreview(double startFraction) {
		Path source = path(dryPath);
		if (source == null || !Files.isRegularFile(source)) {
			showError("Choose a readable dry audio file.");
			return;
		}
		EchoSettings selected;
		try {
			selected = bypassPreview.isSelected() ? bypassedEchoSettings() : echoSettings();
		} catch (IllegalArgumentException failure) {
			showError(failure.getMessage());
			return;
		}
		echoPreviewPlayer.setBypassed(bypassPreview.isSelected());
		echoPreviewPlayer.setLooping(loopPreview.isSelected());
		echoPreviewPlayer.play(source, selected, startFraction,
				state -> SwingUtilities.invokeLater(() -> updatePreviewState(state)),
				message -> SwingUtilities.invokeLater(() -> {
					showError(message);
					previewFinished();
				}));
	}

	private void startLesliePreview(double startFraction) {
		Path source = path(dryPath);
		if (source == null || !Files.isRegularFile(source)) {
			showError("Choose a readable dry audio file first.");
			return;
		}
		LeslieSettings selected;
		try {
			selected = leslieSettings();
		} catch (IllegalArgumentException failure) {
			showError(failure.getMessage());
			return;
		}
		lesliePreviewPlayer.setBypassed(bypassPreview.isSelected());
		lesliePreviewPlayer.setLooping(loopPreview.isSelected());
		lesliePreviewPlayer.play(source, selected, startFraction,
				state -> SwingUtilities.invokeLater(() -> updatePreviewState(state)),
				message -> SwingUtilities.invokeLater(() -> {
					showError(message);
					status.setText("Preview failed");
					previewFinished();
				}));
	}

	private void restartPreviewAt(double fraction) {
		status.setText("Seeking preview…");
		startPreview(fraction);
	}

	private void updatePreviewPosition(ReverbPreviewPlayer.Position position) {
		if (position.totalFrames() < 1 || previewPosition.getValueIsAdjusting())
			return;
		updatingPreviewPosition = true;
		try {
			previewPosition.setValue((int) Math.min(1000, position.frame() * 1000 / position.totalFrames()));
			previewTime.setText(formatTime(position.frame(), position.sampleRate()) + " / "
					+ formatTime(position.totalFrames(), position.sampleRate()));
		} finally {
			updatingPreviewPosition = false;
		}
	}

	private static String formatTime(long frames, int sampleRate) {
		long totalSeconds = sampleRate < 1 ? 0 : Math.max(0, frames) / sampleRate;
		return "%d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
	}

	private void updatePreviewParameters() {
		if (isEchoSelected() || !previewPlayer.isActive())
			return;
		try {
			previewPlayer.update(decimal(wet, "Wet level"), decimal(dry, "Dry level"), decimal(preDelay, "Pre-delay"),
					decimal(lowCut, "Wet low-cut"), decimal(highCut, "Wet high-cut"),
					decimal(earlyLevel, "Early reflections level"), decimal(lateLevel, "Late tail level"),
					decimal(attack, "Attack"), decayLengthPercent(), AUTOMATIC_IR_PEAK_SAFETY,
					AUTOMATIC_PEAK_PROTECTION, AUTOMATIC_HEADROOM_DECIBELS);
		} catch (IllegalArgumentException ignored) {
			// A partially edited numeric field takes effect as soon as it becomes valid.
		}
	}

	private void updatePreviewImpulseResponse() {
		Path selectedIr = path(irPath);
		if (!previewPlayer.isActive() || selectedIr == null || !Files.isRegularFile(selectedIr))
			return;
		status.setText("Preparing new impulse response…");
		previewPlayer.changeImpulseResponse(selectedIr, calibrationGain(selectedIr),
				() -> SwingUtilities.invokeLater(() -> status.setText("Regenerating IR to match sample rate…")),
				loaded -> SwingUtilities.invokeLater(() -> status.setText("Playing with " + loaded.getFileName())),
				message -> SwingUtilities.invokeLater(() -> {
					showError(message);
					status.setText("Could not change impulse response");
				}));
	}

	private double calibrationGain(Path selectedIr) {
		IrProfileLibrary.Profile selected = (IrProfileLibrary.Profile) profileSelector.getSelectedItem();
		if (selected != null && selected.path().equals(selectedIr.toAbsolutePath().normalize()))
			return selected.calibrationGain();
		try {
			return dev.mechana.plugins.audio.ImpulseResponseCalibration.analyze(selectedIr).gain();
		} catch (IOException failure) {
			throw new IllegalArgumentException("Could not calibrate the impulse response: " + failure.getMessage(),
					failure);
		}
	}

	private double decayLengthPercent() {
		double seconds = decimal(decayLength, "Decay");
		if (seconds < 0.05 || seconds > 30)
			throw new IllegalArgumentException("Decay must be between 0.05 and 30 seconds");
		return Math.max(1, Math.min(100, seconds * 100 / irDurationSeconds()));
	}

	private double irDurationSeconds() {
		Path selectedIr = path(irPath);
		if (selectedIr != null && Files.isRegularFile(selectedIr))
			try (WavFile.Reader reader = WavFile.open(selectedIr)) {
				return Math.max(0.05, (double) reader.format().frames() / reader.format().sampleRate());
			} catch (IOException ignored) {
				// Keep the control usable while a profile is being replaced or edited.
			}
		return 2.0;
	}

	private static String compact(double value) {
		return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
				.toPlainString();
	}

	private void restartPreviewWithSelectedSource() {
		if (!previewActive())
			return;
		stopPreview();
		status.setText("Switching preview to the selected dry audio…");
		startPreview();
	}

	private void stopPreview() {
		if (previewPlayer.isActive()) {
			previewPlayer.stop();
			status.setText("Preview stopped");
		}
		if (echoPreviewPlayer.isActive()) {
			echoPreviewPlayer.stop();
			status.setText("Preview stopped");
		}
		if (lesliePreviewPlayer.isActive()) {
			lesliePreviewPlayer.stop();
			status.setText("Preview stopped");
		}
		previewFinished();
	}

	private void updatePreviewState(ReverbPreviewPlayer.State state) {
		switch (state) {
			case PREPARING -> {
				status.setText("Preparing real-time preview…");
				preview.setEnabled(false);
			}
			case REGENERATING_IR -> {
				status.setText("Regenerating IR to match sample rate…");
				preview.setEnabled(false);
			}
			case PLAYING -> {
				status.setText(bypassPreview.isSelected()
						? "Preview bypassed — playing original audio through " + selectedAudioOutputName()
						: "Playing " + selectedEffectName() + " preview through " + selectedAudioOutputName());
				setPreviewButton("⏸", "Pause preview", true);
			}
			case PAUSED -> {
				status.setText(selectedEffectName() + " preview paused");
				setPreviewButton("▶", "Resume preview", true);
			}
			case STOPPED -> status.setText("Preview stopped");
			case FINISHED -> {
				status.setText("Preview finished — full effect tail played");
				previewFinished();
			}
		}
		if (state == ReverbPreviewPlayer.State.PREPARING || state == ReverbPreviewPlayer.State.REGENERATING_IR
				|| state == ReverbPreviewPlayer.State.PLAYING || state == ReverbPreviewPlayer.State.PAUSED)
			stopPreview.setEnabled(true);
	}

	private boolean previewActive() {
		return previewPlayer.isActive() || echoPreviewPlayer.isActive() || lesliePreviewPlayer.isActive();
	}

	private boolean isEchoSelected() {
		return effectTabs.getSelectedIndex() == 1;
	}

	private boolean isLeslieSelected() {
		return effectTabs.getSelectedIndex() == 2;
	}

	private String selectedEffectName() {
		return isLeslieSelected() ? "Leslie" : isEchoSelected() ? "Echo" : "Reverb";
	}

	private EchoSettings echoSettings() {
		return new EchoSettings((EchoSettings.Model) echoModel.getSelectedItem(), decimal(echoDelay, "Delay"),
				decimal(echoFeedback, "Feedback"), decimal(echoWet, "Wet level"), decimal(echoDry, "Dry level"),
				decimal(echoLowCut, "Repeat low-cut"), decimal(echoHighCut, "Repeat high-cut"),
				decimal(echoSaturation, "Saturation"), decimal(echoRate, "Modulation rate"),
				decimal(echoDepth, "Modulation depth"), echoPingPong.isSelected());
	}

	private EchoSettings bypassedEchoSettings() {
		EchoSettings value = echoSettings();
		return new EchoSettings(value.model(), value.delayMilliseconds(), value.feedback(), 0, 1, value.lowCutHertz(),
				value.highCutHertz(), value.saturation(), value.modulationRateHertz(),
				value.modulationDepthMilliseconds(), value.pingPong());
	}

	private LeslieSettings leslieSettings() {
		return new LeslieSettings((LeslieSettings.Speed) leslieSpeed.getSelectedItem(), decimal(leslieDrive, "Drive"),
				decimal(leslieHornLevel, "Horn balance"), decimal(leslieMicDistance, "Mic distance"),
				decimal(leslieStereoWidth, "Stereo width"), decimal(leslieCrossover, "Crossover"),
				decimal(leslieWet, "Wet level"), decimal(leslieDry, "Dry level"));
	}

	private void echoParametersChanged() {
		updateSuggestedName();
		if (isEchoSelected() && echoPreviewPlayer.isActive())
			try {
				echoPreviewPlayer.update(echoSettings());
			} catch (IllegalArgumentException ignored) {
				// A partially edited numeric field takes effect as soon as it becomes valid.
			}
	}

	private void leslieParametersChanged() {
		updateSuggestedName();
		if (isLeslieSelected() && lesliePreviewPlayer.isActive())
			try {
				lesliePreviewPlayer.update(leslieSettings());
			} catch (IllegalArgumentException ignored) {
				// A partially edited numeric field takes effect as soon as it becomes valid.
			}
	}

	private void applyEchoModelDefaults() {
		EchoSettings defaults = EchoSettings.defaults((EchoSettings.Model) echoModel.getSelectedItem());
		echoDelay.setText(Double.toString(defaults.delayMilliseconds()));
		echoFeedback.setText(Double.toString(defaults.feedback()));
		echoWet.setText(Double.toString(defaults.wet()));
		echoDry.setText(Double.toString(defaults.dry()));
		echoLowCut.setText(Double.toString(defaults.lowCutHertz()));
		echoHighCut.setText(Double.toString(defaults.highCutHertz()));
		echoSaturation.setText(Double.toString(defaults.saturation()));
		echoRate.setText(Double.toString(defaults.modulationRateHertz()));
		echoDepth.setText(Double.toString(defaults.modulationDepthMilliseconds()));
	}

	private String selectedAudioOutputName() {
		MacAudioOutput.Device selected = (MacAudioOutput.Device) audioOutput.getSelectedItem();
		return selected == null ? "the default audio output" : selected.name();
	}

	private void previewFinished() {
		setPreviewButton("▶", "Play preview", true);
		stopPreview.setEnabled(false);
	}

	private void setPreviewButton(String symbol, String description, boolean enabled) {
		preview.setText(symbol);
		preview.setToolTipText(description);
		preview.getAccessibleContext().setAccessibleName(description);
		preview.setEnabled(enabled);
	}

	private void update(ReverbJob job) {
		jobs.upsert(job);
		progress.setValue(job.progress());
		status.setText(job.status() + " — " + job.id() + (job.error().isBlank() ? "" : " — " + job.error()));
		if (!"RUNNING".equals(job.status())) {
			run.setEnabled(true);
			if ("SUCCEEDED".equals(job.status())) {
				setLatestOutput(job.artifactDirectory().resolve(job.outputName()));
				reloadHistory();
			}
		}
	}

	private void selectHistoryOutput() {
		ReverbJob selected = selectedJob();
		setLatestOutput(selected == null ? null : selected.artifactDirectory().resolve(selected.outputName()));
		deleteJob.setEnabled(selected != null && Files.isDirectory(selected.artifactDirectory()));
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

	private void deleteSelectedJob() {
		ReverbJob selected = selectedJob();
		if (selected == null)
			return;
		int confirmed = JOptionPane.showConfirmDialog(this,
				"Delete this job and all of its output files?\n\n" + selected.outputName(), "Delete History Job",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirmed != JOptionPane.YES_OPTION)
			return;
		try {
			engine.deleteJob(path(artifactRoot), selected);
			jobTable.clearSelection();
			setLatestOutput(null);
			deleteJob.setEnabled(false);
			reloadHistory();
			status.setText("Deleted job — " + selected.outputName());
		} catch (IOException | RuntimeException failure) {
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
		c.gridy--;
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

	private static void addSection(JPanel panel, GridBagConstraints c, String title) {
		c.gridx = 0;
		c.gridwidth = 3;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		JLabel heading = new JLabel("<html><b>" + title + "</b></html>");
		heading.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
		panel.add(heading, c);
		c.gridy++;
		c.gridwidth = 1;
	}

	private void choose(JTextField target, boolean directory) {
		JFileChooser chooser = new JFileChooser();
		if (directory)
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		else if (target == dryPath)
			chooser.setFileFilter(new FileNameExtensionFilter("Audio (.wav, .m4a, .aac, .mp4, .aiff)", "wav", "wave",
					"m4a", "aac", "mp4", "aif", "aiff"));
		else
			chooser.setFileFilter(new FileNameExtensionFilter("WAV audio (.wav)", "wav"));
		if (!target.getText().isBlank())
			chooser.setSelectedFile(new File(target.getText()));
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
			target.setText(chooser.getSelectedFile().getAbsolutePath());
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
		JOptionPane.showMessageDialog(this, message, "Mechana Effects", JOptionPane.ERROR_MESSAGE);
	}

	static String suggestedOutputName(String dry, String ir, String wet, String dryLevel, String preDelay) {
		return stem(dry, "audio") + "-reverb-ir-" + stem(ir, "impulse") + "-wet" + token(wet) + "-dry" + token(dryLevel)
				+ "-pre" + token(preDelay) + "ms.wav";
	}

	private static String stem(String path, String fallback) {
		String fileName = new File(path).getName().replaceFirst("(?i)\\.(?:wav|wave|m4a|aac|mp4|aif|aiff)$", "");
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

	private static JButton transportButton(String symbol, String description, int width, int height, float fontSize) {
		JButton button = new JButton(symbol);
		button.setToolTipText(description);
		button.getAccessibleContext().setAccessibleName(description);
		button.setFont(button.getFont().deriveFont(Font.BOLD, fontSize));
		button.setPreferredSize(new Dimension(width, height));
		button.setMargin(new Insets(2, 8, 2, 8));
		return button;
	}

	@SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "Swing table models are never serialized")
	private static final class JobTableModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		private final List<ReverbJob> items = new ArrayList<>();
		private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a")
				.withZone(ZoneId.systemDefault());
		private final String[] columns = {"Date", "Effect", "Output file", "Settings"};

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
				case 0 -> DATE_TIME.format(job.submittedAt());
				case 1 -> job.parameterSummary().startsWith("Echoplex") || job.parameterSummary().startsWith("Deluxe")
						? "Echo"
						: "Reverb";
				case 2 -> job.outputName();
				default -> job.parameterSummary();
			};
		}
	}
}
