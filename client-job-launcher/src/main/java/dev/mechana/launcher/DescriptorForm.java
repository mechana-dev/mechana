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

import dev.mechana.protocol.Messages.JobLauncherDescriptor;
import dev.mechana.protocol.Messages.SubmissionField;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

final class DescriptorForm extends JPanel {
	private static final long serialVersionUID = 1L;
	private final transient JobLauncherDescriptor descriptor;
	private final JLabel outputSummary = new JLabel();
	@SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "Swing panels are not deserialized")
	private final transient Map<SubmissionField, JComponent> editors = new LinkedHashMap<>();

	DescriptorForm(JobLauncherDescriptor descriptor, Preferences settings) {
		super(new BorderLayout(8, 8));
		this.descriptor = descriptor;
		JPanel fields = new JPanel(new GridBagLayout());
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 4, 4, 4);
		constraints.gridy = 0;
		constraints.anchor = GridBagConstraints.WEST;
		for (SubmissionField field : descriptor.fields()) {
			constraints.gridx = 0;
			constraints.weightx = 0;
			constraints.fill = GridBagConstraints.NONE;
			fields.add(new JLabel(field.label()), constraints);
			JComponent editor = createEditor(field, settings);
			editor.setToolTipText(field.help());
			constraints.gridx = 1;
			constraints.weightx = 1;
			constraints.fill = GridBagConstraints.HORIZONTAL;
			fields.add(editor, constraints);
			if ("file".equals(field.type()) || "directory".equals(field.type())) {
				JButton browse = new JButton("Choose…");
				browse.addActionListener(event -> chooseFile(field, (JTextField) editor));
				constraints.gridx = 2;
				constraints.weightx = 0;
				constraints.fill = GridBagConstraints.NONE;
				fields.add(browse, constraints);
			}
			editors.put(field, editor);
			constraints.gridy++;
		}
		JScrollPane fieldScroller = new JScrollPane(fields, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		fieldScroller.setBorder(BorderFactory.createEmptyBorder());
		fieldScroller.getVerticalScrollBar().setUnitIncrement(16);
		add(fieldScroller, BorderLayout.CENTER);
		JComponent storage = editors.entrySet().stream()
				.filter(entry -> "storageProvider".equals(entry.getKey().name())).map(Map.Entry::getValue).findFirst()
				.orElse(null);
		if (storage instanceof JComboBox<?> choice)
			choice.addActionListener(event -> updateOutputSummary());
		updateOutputSummary();
		add(outputSummary, BorderLayout.SOUTH);
	}

	String outputSummary() {
		return outputSummary.getText();
	}

	private void updateOutputSummary() {
		String provider = editors.entrySet().stream().filter(entry -> "storageProvider".equals(entry.getKey().name()))
				.map(entry -> editorValue(entry.getValue())).findFirst().orElse(descriptor.output().provider());
		String label = "client-local".equals(provider)
				? "Client-selected output directory"
				: descriptor.output().label();
		outputSummary.setText("Output: " + label + " (" + provider + ") — " + descriptor.resourceEstimate());
	}

	JobLauncherDescriptor descriptor() {
		return descriptor;
	}

	Map<String, Object> values() {
		Map<String, Object> values = new LinkedHashMap<>();
		editors.forEach((field, editor) -> values.put(field.name(), parse(field, editorValue(editor).strip())));
		return values;
	}

	private static JComponent createEditor(SubmissionField field, Preferences settings) {
		String saved = settings.get(field.name(), field.defaultValue());
		if ("choice".equals(field.type())) {
			JComboBox<String> choice = new JComboBox<>(field.choices().toArray(String[]::new));
			choice.setSelectedItem(saved);
			choice.addActionListener(event -> settings.put(field.name(), editorValue(choice)));
			return choice;
		}
		JTextField text = new JTextField(saved, 28);
		text.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent event) {
				remember();
			}
			@Override
			public void removeUpdate(DocumentEvent event) {
				remember();
			}
			@Override
			public void changedUpdate(DocumentEvent event) {
				remember();
			}
			private void remember() {
				settings.put(field.name(), text.getText());
			}
		});
		return text;
	}

	private static String editorValue(JComponent editor) {
		if (editor instanceof JTextField text)
			return text.getText();
		if (editor instanceof JComboBox<?> choice)
			return String.valueOf(choice.getSelectedItem());
		throw new IllegalArgumentException("Unsupported form editor");
	}

	private static Object parse(SubmissionField field, String text) {
		if (field.required() && text.isBlank())
			throw new IllegalArgumentException(field.label() + " is required");
		try {
			if ("file".equals(field.type()) && !text.isBlank() && !field.acceptedExtensions().isEmpty()) {
				String lower = text.toLowerCase(Locale.ROOT);
				if (field.acceptedExtensions().stream().noneMatch(extension -> lower.endsWith("." + extension)))
					throw new IllegalArgumentException(field.label() + " must be a " + acceptedTypes(field));
			}
			Object value = switch (field.type()) {
				case "integer" -> Long.parseLong(text);
				case "decimal" -> Double.parseDouble(text);
				default -> text;
			};
			if (value instanceof Number number) {
				double numeric = number.doubleValue();
				if (field.minimum() != null && numeric < field.minimum()
						|| field.maximum() != null && numeric > field.maximum())
					throw new IllegalArgumentException(field.label() + " is outside the allowed range");
			}
			return value;
		} catch (NumberFormatException invalid) {
			throw new IllegalArgumentException(field.label() + " must be a number", invalid);
		}
	}

	private static String acceptedTypes(SubmissionField field) {
		return field.acceptedExtensions().stream().map(extension -> "." + extension)
				.collect(java.util.stream.Collectors.joining(" or ")) + " file";
	}

	private void chooseFile(SubmissionField field, JTextField editor) {
		JFileChooser chooser = new JFileChooser();
		if ("directory".equals(field.type()))
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (!field.acceptedExtensions().isEmpty())
			chooser.setFileFilter(new FileNameExtensionFilter(field.label() + " (" + acceptedTypes(field) + ")",
					field.acceptedExtensions().toArray(String[]::new)));
		if (!editor.getText().isBlank())
			chooser.setSelectedFile(new File(editor.getText()));
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
			editor.setText(chooser.getSelectedFile().getAbsolutePath());
	}
}
