// Copyright (c) 2026 Mark Vita
// Licensed under the Apache License, Version 2.0. See the repository LICENSE.

import AppKit

@main
final class BenchmarkLauncher: NSObject, NSApplicationDelegate {
    private var process: Process?
    private let outputView = NSTextView()

    func applicationDidFinishLaunching(_ notification: Notification) {
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
