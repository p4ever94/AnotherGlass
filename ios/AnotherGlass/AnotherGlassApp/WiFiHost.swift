import Foundation
import Network

final class WiFiHost: CompanionHost {
    var onWaiting: (() -> Void)?
    var onConnected: ((String) -> Void)?
    var onDisconnected: ((String?) -> Void)?
    var onMessage: ((RPCMessage) -> Void)?

    private let queue = DispatchQueue(label: "AnotherGlass.WiFiHost")
    private var listener: NWListener?
    private var connection: NWConnection?
    private var receiveBuffer = Data()

    var isListening: Bool {
        listener != nil
    }

    func start() throws {
        try start(port: ServiceID.defaultPort)
    }

    func start(port: UInt16) throws {
        guard listener == nil else { return }
        guard let endpointPort = NWEndpoint.Port(rawValue: port) else {
            throw WiFiHostError.invalidPort
        }

        let listener = try NWListener(using: .tcp, on: endpointPort)
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.onWaiting?()
            case .failed(let error):
                self?.onDisconnected?(error.localizedDescription)
                self?.stop()
            case .cancelled:
                self?.onDisconnected?(nil)
            default:
                break
            }
        }

        self.listener = listener
        listener.start(queue: queue)
    }

    func stop() {
        send(.disconnect)
        connection?.cancel()
        listener?.cancel()
        connection = nil
        listener = nil
        receiveBuffer.removeAll()
    }

    func send(_ message: RPCMessage) {
        guard let connection else { return }
        do {
            let data = try JSONLineSerializer.encode(message)
            connection.send(content: data, completion: .contentProcessed { [weak self] error in
                if let error {
                    self?.onDisconnected?(error.localizedDescription)
                }
            })
        } catch {
            onDisconnected?(error.localizedDescription)
        }
    }

    private func accept(_ nextConnection: NWConnection) {
        connection?.cancel()
        receiveBuffer.removeAll()
        connection = nextConnection

        nextConnection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.onConnected?(nextConnection.endpoint.displayName)
                self?.receive(on: nextConnection)
            case .failed(let error):
                self?.connection = nil
                self?.onDisconnected?(error.localizedDescription)
                if self?.listener != nil {
                    self?.onWaiting?()
                }
            case .cancelled:
                self?.connection = nil
                self?.onDisconnected?(nil)
                if self?.listener != nil {
                    self?.onWaiting?()
                }
            default:
                break
            }
        }

        nextConnection.start(queue: queue)
    }

    private func receive(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, isComplete, error in
            guard let self else { return }

            if let data, !data.isEmpty {
                self.receiveBuffer.append(data)
                self.drainReceivedLines()
            }

            if let error {
                self.onDisconnected?(error.localizedDescription)
                return
            }

            if isComplete {
                self.onDisconnected?(nil)
                return
            }

            self.receive(on: connection)
        }
    }

    private func drainReceivedLines() {
        while let newline = receiveBuffer.firstIndex(of: UInt8(ascii: "\n")) {
            let line = receiveBuffer[..<newline]
            receiveBuffer.removeSubrange(...newline)
            guard !line.isEmpty else { continue }

            do {
                let data = Data(line)
                JSONLineSerializer.logReceived(data, transport: "Wi-Fi")
                let message = try JSONLineSerializer.decode(data)
                onMessage?(message)
            } catch {
                onDisconnected?("Unable to parse message: \(error.localizedDescription)")
            }
        }
    }
}

enum WiFiHostError: LocalizedError {
    case invalidPort

    var errorDescription: String? {
        "Invalid port"
    }
}

private extension NWEndpoint {
    var displayName: String {
        switch self {
        case .hostPort(let host, _):
            return String(describing: host)
        default:
            return String(describing: self)
        }
    }
}
