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

import Cocoa
import WebKit

private final class AppDelegate: NSObject, NSApplicationDelegate, WKNavigationDelegate, WKUIDelegate {
    private var window: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            try startServer()
            showDashboard()
        } catch {
            let alert = NSAlert()
            alert.alertStyle = .critical
            alert.messageText = "Mechana Server could not be started"
            alert.informativeText = "\(error.localizedDescription)\n\nSee ~/.mechana/logs/server-error.log for details."
            alert.runModal()
            NSApplication.shared.terminate(nil)
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if let window {
            window.makeKeyAndOrderFront(nil)
        } else {
            showDashboard()
        }
        return true
    }

    private func startServer() throws {
        guard let executable = Bundle.main.executableURL else {
            throw AppError.missingBundleExecutable
        }
        let bootstrap = executable.deletingLastPathComponent().appendingPathComponent("Mechana Server Bootstrap")
        let process = Process()
        process.executableURL = bootstrap
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw AppError.bootstrapFailed(process.terminationStatus)
        }
    }

    private func showDashboard() {
        if let window {
            window.makeKeyAndOrderFront(nil)
            NSApplication.shared.activate(ignoringOtherApps: true)
            return
        }

        let frame = NSRect(x: 0, y: 0, width: 1180, height: 780)
        let dashboardWindow = NSWindow(contentRect: frame,
                                       styleMask: [.titled, .closable, .miniaturizable, .resizable],
                                       backing: .buffered,
                                       defer: false)
        dashboardWindow.title = "Mechana Server"
        dashboardWindow.setFrameAutosaveName("MechanaServerDashboardWindow")
        dashboardWindow.center()

        let webView = WKWebView(frame: frame)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        dashboardWindow.contentView = webView
        webView.load(URLRequest(url: URL(string: "http://127.0.0.1:8787/dashboard")!))

        window = dashboardWindow
        dashboardWindow.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }
        if url.scheme == "mechana" && url.host == "server-stopped" {
            decisionHandler(.cancel)
            NSApplication.shared.terminate(nil)
        } else if url.host == "127.0.0.1" && url.port == 8787 {
            decisionHandler(.allow)
        } else {
            NSWorkspace.shared.open(url)
            decisionHandler(.cancel)
        }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = message
        alert.addButton(withTitle: "Continue")
        alert.addButton(withTitle: "Cancel")
        completionHandler(alert.runModal() == .alertFirstButtonReturn)
    }
}

private enum AppError: LocalizedError {
    case missingBundleExecutable
    case bootstrapFailed(Int32)

    var errorDescription: String? {
        switch self {
        case .missingBundleExecutable:
            return "The application bundle does not contain its launcher."
        case .bootstrapFailed(let status):
            return "The background server bootstrap exited with status \(status)."
        }
    }
}

let application = NSApplication.shared
private let delegate = AppDelegate()
application.delegate = delegate
application.setActivationPolicy(.regular)
application.run()
