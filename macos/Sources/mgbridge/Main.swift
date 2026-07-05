import AppKit

@main
enum MGBridgeMain {
    static func main() {
        let args = CommandLine.arguments
        if args.count >= 2, args[1] == "send" {
            // Quick Action / CLI path: hand the paths to the running menu-bar app.
            exit(CommandClient.send(paths: Array(args.dropFirst(2))))
        }

        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)
        app.run()
    }
}
