import AppKit

/// The whole UI: an NSStatusItem menu. Networking state lives on Bridge.queue;
/// everything here hops to main.
final class AppDelegate: NSObject, NSApplicationDelegate {

    private var statusItem: NSStatusItem!
    private let server = Server()
    private let browser = Browser()
    private var found: [Browser.Found] = []
    private var activeSends: [ObjectIdentifier: SendSession] = [:]
    private let statusLine = NSMenuItem(title: "Status: starting…", action: nil, keyEquivalent: "")

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            try TrustStore.shared.loadOrCreateIdentity()
        } catch {
            let alert = NSAlert()
            alert.messageText = "MGBridge cannot start"
            alert.informativeText = error.localizedDescription
            alert.runModal()
            NSApp.terminate(nil)
            return
        }
        _ = Config.load() // materialize config.json for hand-editing

        server.onPaired = { [weak self] name in
            DispatchQueue.main.async {
                self?.setStatus("Paired with \(name)")
                Notifier.post(title: "Paired", body: name)
                self?.rebuildMenu()
            }
        }
        server.start()
        browser.start { [weak self] found in
            DispatchQueue.main.async {
                self?.found = found
            }
        }
        Notifier.requestAuthorization()

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = statusItem.button {
            button.image = NSImage(
                systemSymbolName: "iphone.and.arrow.forward",
                accessibilityDescription: "MGBridge"
            )
            if button.image == nil {
                button.title = "MG"
            }
        }
        rebuildMenu()
        setStatus("Idle")
    }

    // MARK: menu

    private func rebuildMenu() {
        let menu = NSMenu()
        menu.autoenablesItems = false

        statusLine.isEnabled = false
        menu.addItem(statusLine)
        menu.addItem(.separator())

        menu.addItem(makeItem("Connect Phone Hotspot", #selector(hotspotConnect), "h"))
        menu.addItem(makeItem("Disconnect Hotspot", #selector(hotspotDisconnect), ""))
        menu.addItem(.separator())

        menu.addItem(makeItem("Send Files…", #selector(sendFilesAction), "s"))
        menu.addItem(makeItem("Send Clipboard to Phone", #selector(sendClipboardAction), "b"))
        menu.addItem(.separator())

        menu.addItem(makeItem("Pair New Device…", #selector(pairAction), "p"))
        let peers = TrustStore.shared.peerList()
        if !peers.isEmpty {
            let holder = NSMenuItem(title: "Paired Devices", action: nil, keyEquivalent: "")
            holder.isEnabled = false
            let sub = NSMenu()
            for peer in peers {
                let item = NSMenuItem(title: peer.name, action: nil, keyEquivalent: "")
                item.isEnabled = false
                sub.addItem(item)
            }
            menu.addItem(holder)
            menu.setSubmenu(sub, for: holder)
        }
        menu.addItem(.separator())
        menu.addItem(makeItem("Quit MGBridge", #selector(quitAction), "q"))

        statusItem.menu = menu
    }

    private func makeItem(_ title: String, _ action: Selector, _ key: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
        item.target = self
        item.isEnabled = true
        return item
    }

    private func setStatus(_ text: String) {
        statusLine.title = "Status: \(text)"
    }

    // MARK: actions

    @objc private func hotspotConnect() {
        Hotspot.connect { [weak self] msg in
            DispatchQueue.main.async { self?.setStatus(msg) }
        }
    }

    @objc private func hotspotDisconnect() {
        Hotspot.disconnect { [weak self] msg in
            DispatchQueue.main.async { self?.setStatus(msg) }
        }
    }

    @objc private func sendFilesAction() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.title = "Send to your Galaxy"
        NSApp.activate(ignoringOtherApps: true)
        panel.begin { [weak self] response in
            guard response == .OK, let self, !panel.urls.isEmpty else { return }
            self.sendFiles(panel.urls)
        }
    }

    @objc private func sendClipboardAction() {
        guard let text = Clipboard.read(), !text.isEmpty else {
            setStatus("Clipboard has no text")
            return
        }
        guard let target = pickTarget() else {
            setStatus("No paired device on the network")
            return
        }
        setStatus("Pushing clipboard to \(target.name)…")
        startSession(to: target, payload: .clip(text)) { [weak self] result in
            switch result {
            case .success:
                self?.setStatus("Clipboard sent to \(target.name)")
            case .failure(let error):
                self?.setStatus("Clipboard push failed: \(error.localizedDescription)")
            }
        }
    }

    @objc private func pairAction() {
        let token = TrustStore.shared.armPairing()
        setStatus("Pairing window open (2 min)")
        let alert = NSAlert()
        alert.messageText = "Pairing code"
        let pretty = "\(token.prefix(4)) \(token.suffix(4))"
        alert.informativeText =
            "On the phone, open Mac-Galaxy Bridge → \"Pair with a Mac\", pick this Mac, " +
            "and enter:\n\n\(pretty)\n\nThe window closes in 2 minutes."
        alert.addButton(withTitle: "Done")
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    @objc private func quitAction() {
        NSApp.terminate(nil)
    }

    // MARK: sending

    /// Also the entry point for CLI-driven sends (Quick Action, M3).
    func sendFiles(_ urls: [URL]) {
        guard let target = pickTarget() else {
            setStatus("No paired device on the network")
            Notifier.post(title: "Send failed", body: "No paired device found on the network")
            return
        }
        var cfg = Config.load()
        cfg.lastPeer = target.name
        cfg.save()
        setStatus("Sending \(urls.count) file(s) to \(target.name)…")

        startSession(to: target, payload: .files(urls), progressLabel: urls) { [weak self] result in
            switch result {
            case .success(let ok) where ok.allSatisfy({ $0 }):
                self?.setStatus("Sent to \(target.name) ✔")
                Notifier.post(
                    title: "Sent to \(target.name)",
                    body: urls.map(\.lastPathComponent).joined(separator: ", ")
                )
            case .success:
                self?.setStatus("Peer reported failed file(s)")
                Notifier.post(title: "Transfer problem", body: "Some files failed the hash check")
            case .failure(let error):
                self?.setStatus("Send failed: \(error.localizedDescription)")
                Notifier.post(title: "Send failed", body: error.localizedDescription)
            }
        }
    }

    private func startSession(
        to target: Browser.Found,
        payload: SendSession.Payload,
        progressLabel: [URL] = [],
        done: @escaping (Result<[Bool], Error>) -> Void
    ) {
        var session: SendSession!
        session = SendSession(endpoint: target.endpoint, payload: payload) { [weak self] result in
            DispatchQueue.main.async {
                self?.activeSends.removeValue(forKey: ObjectIdentifier(session))
                done(result)
            }
        }
        if !progressLabel.isEmpty {
            session.onProgress = { [weak self] index, sent, total in
                guard total > 0, index < progressLabel.count else { return }
                let pct = Int(sent * 100 / total)
                DispatchQueue.main.async {
                    self?.setStatus("Sending \(progressLabel[index].lastPathComponent) — \(pct)%")
                }
            }
        }
        activeSends[ObjectIdentifier(session)] = session
        session.start()
    }

    /// Prefer the last-used peer, else any advertised peer whose TXT prefix matches a
    /// pinned fingerprint (TLS is the real gate either way).
    private func pickTarget() -> Browser.Found? {
        let trustedPrefixes = Set(TrustStore.shared.trustedFingerprints().map { String($0.prefix(16)) })
        let candidates = found.filter { f in
            guard let prefix = f.fpPrefix else { return true }
            return trustedPrefixes.contains(prefix)
        }
        let cfg = Config.load()
        if !cfg.lastPeer.isEmpty, let match = candidates.first(where: { $0.name == cfg.lastPeer }) {
            return match
        }
        return candidates.first
    }
}
