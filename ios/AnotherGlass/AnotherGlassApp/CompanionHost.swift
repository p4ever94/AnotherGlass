import Foundation

protocol CompanionHost: AnyObject {
    var onWaiting: (() -> Void)? { get set }
    var onConnected: ((String) -> Void)? { get set }
    var onDisconnected: ((String?) -> Void)? { get set }
    var onMessage: ((RPCMessage) -> Void)? { get set }
    var isListening: Bool { get }

    func start() throws
    func stop()
    func send(_ message: RPCMessage)
}

enum CompanionTransport: String, CaseIterable, Identifiable {
    case wifi = "Wi-Fi"
    case bluetoothLE = "Bluetooth LE"

    var id: String { rawValue }

    var title: String { rawValue }
}
