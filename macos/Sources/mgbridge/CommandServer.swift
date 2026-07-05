import Foundation

/// Local command channel: the Finder Quick Action runs `mgbridge send <files…>`,
/// which connects to this Unix socket in the running menu-bar app. Wire format is
/// newline-separated absolute paths, then EOF; the app answers "ok\n".
final class CommandServer {

    static var socketPath: String {
        AppSupport.dir.appendingPathComponent("cmd.sock").path
    }

    private var fd: Int32 = -1
    private var source: DispatchSourceRead?

    func start(onSend: @escaping ([String]) -> Void) {
        let path = CommandServer.socketPath
        unlink(path)

        fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else {
            NSLog("command socket() failed: \(errno)")
            return
        }
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let ok = withUnsafeMutablePointer(to: &addr.sun_path) { ptr -> Bool in
            let capacity = MemoryLayout.size(ofValue: ptr.pointee)
            return path.withCString { cstr -> Bool in
                let len = strlen(cstr)
                guard len < capacity else { return false }
                return ptr.withMemoryRebound(to: CChar.self, capacity: capacity) { dst -> Bool in
                    strlcpy(dst, cstr, capacity)
                    return true
                }
            }
        }
        guard ok else {
            NSLog("command socket path too long: \(path)")
            close(fd)
            fd = -1
            return
        }

        let bindResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard bindResult == 0, listen(fd, 4) == 0 else {
            NSLog("command socket bind/listen failed: \(errno)")
            close(fd)
            fd = -1
            return
        }

        let src = DispatchSource.makeReadSource(fileDescriptor: fd, queue: Bridge.queue)
        src.setEventHandler { [weak self] in
            guard let self, self.fd >= 0 else { return }
            let client = accept(self.fd, nil, nil)
            guard client >= 0 else { return }
            defer { close(client) }

            var data = Data()
            var buf = [UInt8](repeating: 0, count: 64 * 1024)
            while true {
                let n = read(client, &buf, buf.count)
                if n <= 0 { break }
                data.append(contentsOf: buf[0..<n])
                if data.count > 4 * 1024 * 1024 { break } // sanity cap
            }
            let paths = String(data: data, encoding: .utf8)?
                .split(separator: "\n")
                .map(String.init)
                .filter { !$0.isEmpty } ?? []
            if !paths.isEmpty {
                onSend(paths)
            }
            _ = "ok\n".withCString { write(client, $0, 3) }
        }
        src.setCancelHandler { [weak self] in
            if let self, self.fd >= 0 {
                close(self.fd)
                self.fd = -1
            }
            unlink(path)
        }
        src.resume()
        source = src
        NSLog("command server on \(path)")
    }

    func stop() {
        source?.cancel()
        source = nil
    }
}

/// The `mgbridge send <files…>` side, run by the Quick Action.
enum CommandClient {

    static func send(paths: [String]) -> Int32 {
        guard !paths.isEmpty else {
            FileHandle.standardError.write(Data("usage: mgbridge send <file>…\n".utf8))
            return 2
        }
        let absolute = paths.map { p -> String in
            (p as NSString).isAbsolutePath
                ? p
                : FileManager.default.currentDirectoryPath + "/" + p
        }

        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { return 1 }
        defer { close(fd) }

        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let path = CommandServer.socketPath
        let fits = withUnsafeMutablePointer(to: &addr.sun_path) { ptr -> Bool in
            let capacity = MemoryLayout.size(ofValue: ptr.pointee)
            return path.withCString { cstr -> Bool in
                guard strlen(cstr) < capacity else { return false }
                return ptr.withMemoryRebound(to: CChar.self, capacity: capacity) { dst -> Bool in
                    strlcpy(dst, cstr, capacity)
                    return true
                }
            }
        }
        guard fits else { return 1 }

        let connected = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard connected == 0 else {
            FileHandle.standardError.write(
                Data("MGBridge app is not running — open MGBridge.app first\n".utf8)
            )
            return 1
        }

        let payload = absolute.joined(separator: "\n") + "\n"
        _ = payload.withCString { write(fd, $0, strlen($0)) }
        shutdown(fd, SHUT_WR)

        var buf = [UInt8](repeating: 0, count: 16)
        let n = read(fd, &buf, buf.count)
        let reply = n > 0 ? String(bytes: buf[0..<n], encoding: .utf8) ?? "" : ""
        return reply.hasPrefix("ok") ? 0 : 1
    }
}
