import AppKit

@main
enum MGBridgeMain {
    static func main() {
        let args = CommandLine.arguments
        if args.count >= 2, args[1] == "send" {
            // Quick Action / CLI path — lands with the command server in M3.
            FileHandle.standardError.write(Data("mgbridge send: not wired up yet (M3)\n".utf8))
            exit(1)
        }

        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)
        app.run()
    }
}
