import Foundation

/// App files live in ~/Library/Application Support/MGBridge/.
enum AppSupport {
    static var dir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("MGBridge", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}

/// config.json — hotspot settings and send preferences. Hand-editable.
struct Config: Codable {
    /// The phone's Bluetooth MAC (blueutil --paired shows it).
    var phoneBtAddr: String = ""
    /// The phone's hotspot SSID the Mac should join.
    var hotspotSSID: String = ""
    /// Hotspot password; empty if the network is already known to macOS.
    var hotspotPassword: String = ""
    /// Wi-Fi interface, usually en0 (networksetup -listallhardwareports).
    var wifiInterface: String = "en0"
    /// Preferred peer name for sends when several devices are paired.
    var lastPeer: String = ""

    static var url: URL { AppSupport.dir.appendingPathComponent("config.json") }

    static func load() -> Config {
        guard let data = try? Data(contentsOf: url),
              let cfg = try? JSONDecoder().decode(Config.self, from: data) else {
            let fresh = Config()
            fresh.save()
            return fresh
        }
        return cfg
    }

    func save() {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let data = try? enc.encode(self) {
            try? data.write(to: Config.url, options: .atomic)
        }
    }
}

func deviceName() -> String {
    Host.current().localizedName ?? "Mac"
}
