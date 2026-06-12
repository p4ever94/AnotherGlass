import CoreBluetooth
import Foundation

final class BluetoothLEHost: NSObject, CompanionHost {
    var onWaiting: (() -> Void)?
    var onConnected: ((String) -> Void)?
    var onDisconnected: ((String?) -> Void)?
    var onMessage: ((RPCMessage) -> Void)?

    private var manager: CBPeripheralManager?
    private var phoneToGlass: CBMutableCharacteristic?
    private var glassToPhone: CBMutableCharacteristic?
    private var subscribedCentral: CBCentral?
    private var receiveBuffer = Data()
    private var pendingChunks = [Data]()
    private var isActive = false
    private var isPublishingService = false
    private var shouldStartWhenPoweredOn = false
    private var hasReceivedWriteConnection = false

    var isListening: Bool {
        isActive && (manager?.isAdvertising == true || subscribedCentral != nil || shouldStartWhenPoweredOn || isPublishingService)
    }

    func start() throws {
        isActive = true
        if manager == nil {
            manager = CBPeripheralManager(delegate: self, queue: nil)
        }

        if manager?.state == .poweredOn {
            publishService()
        } else {
            shouldStartWhenPoweredOn = true
            onWaiting?()
        }
    }

    func stop() {
        send(.disconnect)
        isActive = false
        isPublishingService = false
        shouldStartWhenPoweredOn = false
        manager?.stopAdvertising()
        manager?.removeAllServices()
        phoneToGlass = nil
        glassToPhone = nil
        subscribedCentral = nil
        hasReceivedWriteConnection = false
        receiveBuffer.removeAll()
        pendingChunks.removeAll()
    }

    func send(_ message: RPCMessage) {
        guard subscribedCentral != nil else { return }

        do {
            enqueue(try JSONLineSerializer.encode(message))
            drainSendQueue()
        } catch {
            onDisconnected?(error.localizedDescription)
        }
    }

    private func publishService() {
        guard isActive else { return }
        guard let manager else { return }

        manager.stopAdvertising()
        manager.removeAllServices()

        let phoneToGlass = CBMutableCharacteristic(
            type: BluetoothLEID.phoneToGlass,
            properties: [.notify],
            value: nil,
            permissions: []
        )
        let glassToPhone = CBMutableCharacteristic(
            type: BluetoothLEID.glassToPhone,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let service = CBMutableService(type: BluetoothLEID.service, primary: true)
        service.characteristics = [phoneToGlass, glassToPhone]

        self.phoneToGlass = phoneToGlass
        self.glassToPhone = glassToPhone
        hasReceivedWriteConnection = false

        isPublishingService = true
        manager.add(service)
        shouldStartWhenPoweredOn = false
        onWaiting?()
    }

    private func startAdvertising() {
        guard isActive else { return }
        guard let manager else { return }

        manager.startAdvertising([
            CBAdvertisementDataLocalNameKey: "AGlass",
            CBAdvertisementDataServiceUUIDsKey: [BluetoothLEID.service]
        ])
        onWaiting?()
    }

    private func enqueue(_ data: Data) {
        let maxLength = max(subscribedCentral?.maximumUpdateValueLength ?? 20, 20)
        var offset = data.startIndex
        while offset < data.endIndex {
            let end = min(offset + maxLength, data.endIndex)
            pendingChunks.append(data[offset..<end])
            offset = end
        }
    }

    private func drainSendQueue() {
        guard let manager, let subscribedCentral, let phoneToGlass else { return }

        while let chunk = pendingChunks.first {
            let didSend = manager.updateValue(chunk, for: phoneToGlass, onSubscribedCentrals: [subscribedCentral])
            if !didSend {
                return
            }
            pendingChunks.removeFirst()
        }
    }

    private func appendReceived(_ data: Data) {
        receiveBuffer.append(data)

        while let newline = receiveBuffer.firstIndex(of: UInt8(ascii: "\n")) {
            let line = receiveBuffer[..<newline]
            receiveBuffer.removeSubrange(...newline)
            guard !line.isEmpty else { continue }

            do {
                let message = try JSONLineSerializer.decode(Data(line))
                onMessage?(message)
            } catch {
                let preview = String(data: Data(line.prefix(120)), encoding: .utf8) ?? "<binary>"
                print("Skipping malformed BLE message: \(error.localizedDescription) \(preview)")
            }
        }
    }
}

extension BluetoothLEHost: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            if isActive && shouldStartWhenPoweredOn {
                publishService()
            }
        case .poweredOff:
            onDisconnected?("Bluetooth is off")
        case .unauthorized:
            onDisconnected?("Bluetooth permission is not granted")
        case .unsupported:
            onDisconnected?("Bluetooth LE is not supported")
        default:
            break
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard service.uuid == BluetoothLEID.service else { return }
        isPublishingService = false
        if let error {
            onDisconnected?("Bluetooth LE service failed: \(error.localizedDescription)")
            return
        }
        startAdvertising()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == BluetoothLEID.phoneToGlass else { return }
        subscribedCentral = central
        peripheral.stopAdvertising()
        onConnected?("Glass BLE")
        drainSendQueue()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == BluetoothLEID.phoneToGlass else { return }
        subscribedCentral = nil
        pendingChunks.removeAll()
        if !hasReceivedWriteConnection {
            onDisconnected?(nil)
        }
        if isActive && (shouldStartWhenPoweredOn || peripheral.state == .poweredOn) {
            publishService()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if request.characteristic.uuid == BluetoothLEID.glassToPhone, let value = request.value {
                if !hasReceivedWriteConnection {
                    hasReceivedWriteConnection = true
                    peripheral.stopAdvertising()
                    onConnected?("Glass BLE")
                }
                appendReceived(value)
            }
            peripheral.respond(to: request, withResult: .success)
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        drainSendQueue()
    }
}

enum BluetoothLEID {
    static let service = CBUUID(string: "05f2934c-1e81-4554-bb08-44aa761afbfb")
    static let glassToPhone = CBUUID(string: "05f2934c-1e81-4554-bb08-44aa761afbfc")
    static let phoneToGlass = CBUUID(string: "05f2934c-1e81-4554-bb08-44aa761afbfd")
}
