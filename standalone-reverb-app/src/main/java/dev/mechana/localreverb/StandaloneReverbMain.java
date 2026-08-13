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

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Starts the self-contained, server-free Mechana Reverb application. */
public final class StandaloneReverbMain {
	private StandaloneReverbMain() {
	}

	public static void main(String[] args) {
		System.setProperty("apple.awt.application.name", "Mechana Reverb");
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ignored) {
			// Swing's cross-platform appearance remains usable.
		}
		SwingUtilities.invokeLater(() -> new StandaloneReverbFrame().setVisible(true));
	}
}
