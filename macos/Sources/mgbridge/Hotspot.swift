import Foundation

/// One-click hotspot: blueutil pokes the phone over Bluetooth (the companion app
/// catches ACL_CONNECTED and flips the hotspot), then networksetup nudges Wi-Fi onto
/// the hotspot SSID. All shell-outs, isolated here on purpose — swap for IOBluetooth /
/// CoreWLAN later without touching callers.
enum Hotspot {

    static func connect(status: @escaping (String) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let cfg = Config.load()
            guard !cfg.phoneBtAddr.isEmpty else {
                status("Set phoneBtAddr in \(Config.url.path)")
                return
            }
            guard let blueutil = blueutilPath() else {
                status("blueutil not found — brew install blueutil")
                return
            }
            status("Poking phone over Bluetooth…")
            let (code, out) = Shell.run(blueutil, ["--connect", cfg.phoneBtAddr])
            if code != 0 {
                // Often already connected; keep going and let the join decide.
                NSLog("blueutil connect: \(out)")
            }
            guard !cfg.hotspotSSID.isEmpty else {
                status("Bluetooth connected — set hotspotSSID to auto-join")
                return
            }
            for attempt in 1...8 {
                if currentSSID(cfg).contains(cfg.hotspotSSID) {
                    status("Connected to \(cfg.hotspotSSID)")
                    return
                }
                status("Joining \(cfg.hotspotSSID)… (\(attempt)/8)")
                var args = ["-setairportnetwork", cfg.wifiInterface, cfg.hotspotSSID]
                if !cfg.hotspotPassword.isEmpty {
                    args.append(cfg.hotspotPassword)
                }
                Shell.run("/usr/sbin/networksetup", args)
                Thread.sleep(forTimeInterval: 2.5)
            }
            status(
                currentSSID(cfg).contains(cfg.hotspotSSID)
                    ? "Connected to \(cfg.hotspotSSID)"
                    : "Gave up joining \(cfg.hotspotSSID) — is the hotspot on?"
            )
        }
    }

    static func disconnect(status: @escaping (String) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let cfg = Config.load()
            guard !cfg.phoneBtAddr.isEmpty, let blueutil = blueutilPath() else {
                status("Nothing to disconnect")
                return
            }
            Shell.run(blueutil, ["--disconnect", cfg.phoneBtAddr])
            status("Bluetooth dropped — phone will turn its hotspot off")
        }
    }

    private static func blueutilPath() -> String? {
        Shell.firstExisting([
            "/opt/homebrew/bin/blueutil",
            "/usr/local/bin/blueutil",
        ])
    }

    private static func currentSSID(_ cfg: Config) -> String {
        let (_, out) = Shell.run(
            "/usr/sbin/networksetup",
            ["-getairportnetwork", cfg.wifiInterface]
        )
        return out
    }
}
