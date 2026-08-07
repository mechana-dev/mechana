/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS.
 */
package dev.mechana.workercontrol;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Maintains a local SSH forward to the remote agent's loopback API. */
final class SshAgentTunnel implements AutoCloseable {
	private Process process;
	private String key;
	private URI uri;

	URI connect(SshProvisioner.Request request) throws IOException, InterruptedException {
		String requestedKey = request.sshUser() + "@" + request.host() + ":" + request.sshPort() + "/"
				+ request.agentPort() + "/" + request.identityFile();
		if (requestedKey.equals(key) && process != null && process.isAlive())
			return uri;
		close();
		int localPort = availablePort();
		process = new ProcessBuilder(command(request, localPort)).redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
		key = requestedKey;
		uri = URI.create("http://127.0.0.1:" + localPort);
		long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
		while (System.nanoTime() < deadline) {
			if (!process.isAlive()) {
				close();
				throw new IOException("SSH agent tunnel exited before becoming ready");
			}
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort), 250);
				return uri;
			} catch (IOException unavailable) {
				Thread.sleep(100);
			}
		}
		close();
		throw new IOException("SSH agent tunnel did not become ready within 8 seconds");
	}

	static List<String> command(SshProvisioner.Request request, int localPort) {
		List<String> command = new ArrayList<>();
		command.addAll(List.of("ssh", "-p", Integer.toString(request.sshPort()), "-o", "BatchMode=yes", "-o",
				"ConnectTimeout=10", "-o", "ExitOnForwardFailure=yes", "-o",
				"StrictHostKeyChecking=" + (request.acceptNewHostKey() ? "accept-new" : "yes")));
		if (request.identityFile() != null)
			command.addAll(List.of("-i", request.identityFile().toAbsolutePath().normalize().toString()));
		command.addAll(List.of("-N", "-L", "127.0.0.1:" + localPort + ":127.0.0.1:" + request.agentPort(),
				request.sshUser() + "@" + request.host()));
		return List.copyOf(command);
	}

	private static int availablePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	@Override
	public synchronized void close() {
		if (process != null)
			process.destroyForcibly();
		process = null;
		key = null;
		uri = null;
	}
}
