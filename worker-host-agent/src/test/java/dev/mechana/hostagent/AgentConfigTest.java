package dev.mechana.hostagent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class AgentConfigTest {
	@Test
	void requiresExplicitOptInForUnauthenticatedLanBinding() {
		Properties properties = new Properties();
		properties.setProperty("bind-address", "0.0.0.0");
		properties.setProperty("token", "");
		assertThrows(IllegalArgumentException.class, () -> AgentConfig.from(properties));

		properties.setProperty("allow-unauthenticated", "true");
		assertDoesNotThrow(() -> AgentConfig.from(properties));
	}
}
