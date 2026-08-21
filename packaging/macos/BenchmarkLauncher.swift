// Copyright (c) 2026 Mark Vita
// Licensed under the Apache License, Version 2.0. See the repository LICENSE.

import AppKit

@main
final class BenchmarkLauncher: NSObject, NSApplicationDelegate {
    private var process: Process?
    private let outputView = NSTextView()
    private var awaitingStableRelaunch = false

    static func main() {
        let application = NSApplication.shared
        let delegate = BenchmarkLauncher()
        delegate.awaitingStableRelaunch = delegate.relaunchFromStableLocationIfNeeded()
        application.delegate = delegate
        application.run()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        if awaitingStableRelaunch {
            return
        }

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 920, height: 680),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Mechana Effect Benchmarks"
        window.center()

        outputView.isEditable = false
        outputView.isSelectable = true
        outputView.font = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
        outputView.string = "Starting Mechana effect benchmarks…\n\n"

        let scrollView = NSScrollView(frame: window.contentView!.bounds)
        scrollView.autoresizingMask = [.width, .height]
        scrollView.hasVerticalScroller = true
        scrollView.documentView = outputView
        window.contentView?.addSubview(scrollView)
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        runBenchmarks()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    private func relaunchFromStableLocationIfNeeded() -> Bool {
        let executableURL = URL(fileURLWithPath: CommandLine.arguments[0]).standardized
        let enclosingBundle = executableURL.deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
        let source = enclosingBundle.pathExtension == "app" ? enclosingBundle : Bundle.main.bundleURL
        let temporaryRoot = URL(fileURLWithPath: NSTemporaryDirectory()).resolvingSymlinksInPath().path
        let sourcePath = source.resolvingSymlinksInPath().path
        let executablePath = executableURL.path
        let hostedTemporarily = [sourcePath, executablePath].contains {
            $0.hasPrefix(temporaryRoot + "/") || $0.contains("/var/folders/") || $0.contains("/AppTranslocation/")
        }
        guard hostedTemporarily else {
            return false
        }

        do {
            let applicationSupport = try FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
            let identifier = Bundle.main.bundleIdentifier ?? "dev.mechana.effect-benchmarks"
            let installDirectory = applicationSupport
                .appendingPathComponent("Mechana", isDirectory: true)
                .appendingPathComponent("Effect Benchmarks", isDirectory: true)
                .appendingPathComponent(identifier, isDirectory: true)
            try FileManager.default.createDirectory(at: installDirectory, withIntermediateDirectories: true)

            let destination = installDirectory.appendingPathComponent("Mechana Effect Benchmarks.app", isDirectory: true)
            let staging = installDirectory.appendingPathComponent(
                ".Mechana Effect Benchmarks.installing-\(UUID().uuidString).app",
                isDirectory: true
            )
            try FileManager.default.copyItem(at: source, to: staging)
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.moveItem(at: staging, to: destination)

            let configuration = NSWorkspace.OpenConfiguration()
            NSWorkspace.shared.openApplication(at: destination, configuration: configuration) { _, error in
                if let error {
                    let alert = NSAlert(error: error)
                    alert.messageText = "Unable to reopen Mechana Effect Benchmarks"
                    alert.runModal()
                }
                NSApp.terminate(nil)
            }
            return true
        } catch {
            let alert = NSAlert(error: error)
            alert.messageText = "Unable to install Mechana Effect Benchmarks in a stable location"
            alert.informativeText = "Extract the ZIP before opening the app, or move it to Applications and try again."
            alert.runModal()
            return false
        }
    }

    private func runBenchmarks() {
        guard let resources = Bundle.main.resourceURL else {
            append("Unable to locate packaged benchmark resources.\n")
            return
        }

        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/bin/zsh")
        task.arguments = [resources.appendingPathComponent("run-benchmarks.sh").path]

        let pipe = Pipe()
        task.standardOutput = pipe
        task.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            DispatchQueue.main.async { self?.append(text) }
        }
        task.terminationHandler = { [weak self] completed in
            pipe.fileHandleForReading.readabilityHandler = nil
            let remaining = pipe.fileHandleForReading.readDataToEndOfFile()
            DispatchQueue.main.async {
                if let text = String(data: remaining, encoding: .utf8), !text.isEmpty {
                    self?.append(text)
                }
                self?.append("\nBenchmarks finished with exit code \(completed.terminationStatus).\n")
            }
        }

        do {
            try task.run()
            process = task
        } catch {
            append("Unable to start benchmarks: \(error.localizedDescription)\n")
        }
    }

    private func append(_ text: String) {
        outputView.textStorage?.append(NSAttributedString(string: text))
        outputView.scrollToEndOfDocument(nil)
    }
}
