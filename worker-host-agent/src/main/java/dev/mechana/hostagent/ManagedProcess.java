package dev.mechana.hostagent;

import java.time.Duration;

interface ManagedProcess {
	long pid();
	boolean isAlive();
	void destroy();
	void destroyForcibly();
	boolean waitFor(Duration timeout) throws InterruptedException;
}
