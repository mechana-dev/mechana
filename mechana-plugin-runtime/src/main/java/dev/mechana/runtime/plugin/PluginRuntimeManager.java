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
package dev.mechana.runtime.plugin;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Worker-owned policy and lifecycle boundary for plugin processes. */
public final class PluginRuntimeManager {
	private final PluginSandbox managed;
	private final PluginSandbox sandboxed;

	public PluginRuntimeManager(PluginSandbox managed, PluginSandbox sandboxed) {
		this.managed = Objects.requireNonNull(managed);
		this.sandboxed = Objects.requireNonNull(sandboxed);
	}

	public SandboxResult execute(SandboxRequest request, AtomicBoolean cancellation)
			throws IOException, InterruptedException {
		if (request.policy().trustMode() == TrustMode.TRUSTED)
			throw new IllegalArgumentException("Trusted in-process execution does not use a process sandbox");
		PluginSandbox selected = request.policy().trustMode() == TrustMode.MANAGED ? managed : sandboxed;
		SandboxCapabilities capabilities = selected.capabilities(request.policy());
		if (request.policy().trustMode() == TrustMode.SANDBOXED) {
			boolean filesystemBoundary = capabilities.enforces(SandboxControl.FILESYSTEM_RESTRICTION)
					|| (capabilities.enforces(SandboxControl.FILESYSTEM_WRITE_RESTRICTION)
							&& capabilities.enforces(SandboxControl.HOME_DIRECTORY_DENIAL));
			if (!filesystemBoundary
					|| (!request.policy().networkAllowed() && !capabilities.enforces(SandboxControl.NETWORK_DENIAL)))
				throw new IllegalStateException("Host cannot enforce the requested sandbox policy");
		}
		return selected.execute(request, cancellation);
	}
}
