package dev.mechana.workercontrol;

import javax.swing.SwingUtilities;

public final class WorkerControlMain {
	private WorkerControlMain() {
	}
	public static void main(String[] args) {
		SwingUtilities.invokeLater(
				() -> new WorkerControlFrame(new AgentClient(), SettingsStore.userDefault()).setVisible(true));
	}
}
