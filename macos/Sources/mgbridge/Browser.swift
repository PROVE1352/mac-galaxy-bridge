import Foundation
import Network

/// Continuous Bonjour browse for peers (the send path's phonebook).
final class Browser {

    struct Found: Equatable {
        let name: String
        let endpoint: NWEndpoint
        let fpPrefix: String?
    }

    private var browser: NWBrowser?

    func start(onUpdate: @escaping ([Found]) -> Void) {
        let descriptor = NWBrowser.Descriptor.bonjourWithTXTRecord(
            type: Server.serviceType,
            domain: nil
        )
        let browser = NWBrowser(for: descriptor, using: NWParameters())
        browser.browseResultsChangedHandler = { results, _ in
            let mine = deviceName()
            var found: [Found] = []
            for result in results {
                guard case NWEndpoint.service(let name, _, _, _) = result.endpoint,
                      name != mine else { continue }
                var fp: String?
                if case NWBrowser.Result.Metadata.bonjour(let txt) = result.metadata {
                    fp = txt.dictionary["fp"]
                }
                found.append(Found(name: name, endpoint: result.endpoint, fpPrefix: fp))
            }
            onUpdate(found)
        }
        browser.stateUpdateHandler = { st in
            if case .failed(let error) = st {
                NSLog("browser failed: \(error)")
            }
        }
        browser.start(queue: Bridge.queue)
        self.browser = browser
    }

    func stop() {
        browser?.cancel()
        browser = nil
    }
}
