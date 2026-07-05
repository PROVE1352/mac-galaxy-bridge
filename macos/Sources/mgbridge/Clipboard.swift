import AppKit

/// Clipboard glue for the `clip` frame (Mac → phone is the useful direction, but
/// applying an inbound clip is symmetric and free).
enum Clipboard {

    /// Current pasteboard text, if any.
    static func read() -> String? {
        NSPasteboard.general.string(forType: .string)
    }

    static func apply(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        Notifier.post(title: "Clipboard received", body: String(text.prefix(120)))
    }
}
