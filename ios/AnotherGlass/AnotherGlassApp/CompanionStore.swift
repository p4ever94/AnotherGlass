import Foundation
import CoreLocation
import Contacts
#if canImport(UIKit)
import UIKit
#endif

@MainActor
final class CompanionStore: ObservableObject {
    @Published var serviceState: ServiceState = .stopped
    @Published var transport: CompanionTransport = .wifi {
        didSet {
            guard oldValue != transport, isServiceRunning else { return }
            stopService()
            startService()
        }
    }
    @Published var connectedDeviceName: String?
    @Published var batteryStatus: BatteryStatus?
    @Published var latestLocationText = "None"
    @Published var lastEvent = "Idle"
    @Published var lastMediaCommand: String?
    @Published var lastSiriRequest: String?
    @Published var localAddress = NetworkAddress.currentIPv4Address() ?? "Unavailable"
    @Published private(set) var dailyTelemetry = DailyTelemetry.empty(for: Date())
    @Published private(set) var selectedTelemetryDate = Calendar.current.startOfDay(for: Date())
    @Published var isGPSEnabled = true {
        didSet {
            locationController.isEnabled = isGPSEnabled
            if isGPSEnabled, serviceState == .connected {
                locationController.start()
            } else if !isGPSEnabled {
                locationController.stop()
            }
        }
    }

    private let wifiHost = WiFiHost()
    private let bluetoothHost = BluetoothLEHost()
    private let telemetryStore = TelemetryStore()
    private let contactStore = CNContactStore()
    private let maximumBluetoothContacts = 25
    private lazy var locationController = LocationController { [weak self] location in
        Task { @MainActor in
            self?.send(location: location)
        }
    }

    var isServiceRunning: Bool {
        serviceState != .stopped
    }

    var batteryText: String {
        guard let batteryStatus else { return "Unknown" }
        let suffix = batteryStatus.isCharging ? ", charging" : ""
        return "\(batteryStatus.level)%\(suffix)"
    }

    init() {
        configureHostCallbacks(for: wifiHost)
        configureHostCallbacks(for: bluetoothHost)
        locationController.isEnabled = isGPSEnabled
        reloadTelemetry()
    }

    func toggleService() {
        isServiceRunning ? stopService() : startService()
    }

    func startService() {
        do {
            localAddress = NetworkAddress.currentIPv4Address() ?? "Unavailable"
            try activeHost.start()
            serviceState = .waiting
            lastEvent = transport == .wifi ? "Waiting for Glass" : "Advertising BLE"
        } catch {
            serviceState = .stopped
            lastEvent = "Failed to start: \(error.localizedDescription)"
        }
    }

    func stopService() {
        locationController.stop()
        activeHost.stop()
        serviceState = .stopped
        connectedDeviceName = nil
        batteryStatus = nil
        lastEvent = "Stopped"
    }

    private var activeHost: CompanionHost {
        switch transport {
        case .wifi: wifiHost
        case .bluetoothLE: bluetoothHost
        }
    }

    private func configureHostCallbacks(for host: CompanionHost) {
        host.onWaiting = { [weak self] in
            Task { @MainActor in
                guard let self, host === self.activeHost, host.isListening else { return }
                self.serviceState = .waiting
                self.connectedDeviceName = nil
                self.batteryStatus = nil
                self.lastEvent = self.transport == .wifi ? "Waiting for Glass" : "Advertising BLE"
            }
        }

        host.onConnected = { [weak self] name in
            Task { @MainActor in
                guard let self, host === self.activeHost else { return }
                self.serviceState = .connected
                self.connectedDeviceName = name
                self.batteryStatus = nil
                self.lastEvent = "Connected"
                self.sendTimeSync()
                self.sendEmptyMediaState()
                if self.isGPSEnabled {
                    self.locationController.start()
                }
            }
        }

        host.onDisconnected = { [weak self] error in
            Task { @MainActor in
                guard let self, host === self.activeHost else { return }
                self.locationController.stop()
                self.serviceState = host.isListening ? .waiting : .stopped
                self.connectedDeviceName = nil
                self.batteryStatus = nil
                self.lastEvent = error ?? "Disconnected"
            }
        }

        host.onMessage = { [weak self] message in
            Task { @MainActor in
                guard let self, host === self.activeHost else { return }
                if self.serviceState != .connected {
                    self.serviceState = .connected
                    self.connectedDeviceName = self.transport == .bluetoothLE ? "Glass BLE" : "Glass"
                }
                self.recordIncomingMessage(message)
                self.handle(message)
            }
        }
    }

    private func handle(_ message: RPCMessage) {
        switch message.payload {
        case .battery(let battery):
            batteryStatus = battery
            lastEvent = "Battery updated"
        case .mediaCommand(let command):
            lastMediaCommand = command.command.rawValue
            lastEvent = "Media command received"
        case .siriRequest(let request):
            let date = Date(timeIntervalSince1970: TimeInterval(request.requestedAtMs) / 1000)
            lastSiriRequest = date.formatted(date: .omitted, time: .standard)
            lastEvent = "Siri requested from Glass"
        case .contactsRequest:
            lastEvent = "Contacts requested from Glass"
            sendContacts()
        case .callRequest(let request):
            placeCall(request)
        case .none:
            lastEvent = "Disconnect requested"
        default:
            lastEvent = "Received \(message.service ?? "unknown")"
        }
    }

    func showPreviousTelemetryDay() {
        selectedTelemetryDate = Calendar.current.date(byAdding: .day, value: -1, to: selectedTelemetryDate) ?? selectedTelemetryDate
        reloadTelemetry()
    }

    func showNextTelemetryDay() {
        let today = Calendar.current.startOfDay(for: Date())
        guard selectedTelemetryDate < today else { return }
        selectedTelemetryDate = Calendar.current.date(byAdding: .day, value: 1, to: selectedTelemetryDate) ?? selectedTelemetryDate
        if selectedTelemetryDate > today {
            selectedTelemetryDate = today
        }
        reloadTelemetry()
    }

    func showTodayTelemetry() {
        selectedTelemetryDate = Calendar.current.startOfDay(for: Date())
        reloadTelemetry()
    }

    private func send(location: CLLocation) {
        guard serviceState == .connected else { return }
        let payload = GlassLocation(location)
        latestLocationText = payload.displayText
        send(.location(payload))
    }

    private func sendEmptyMediaState() {
        send(.mediaState(MediaStateData.empty))
    }

    func forceTimeSync() {
        guard serviceState == .connected else {
            lastEvent = "Connect Glass first"
            return
        }
        sendTimeSync()
        lastEvent = "Time sync sent"
    }

    private func sendTimeSync() {
        send(.timeSync(.current))
    }

    func sendFakeNotification() {
        guard serviceState == .connected else {
            lastEvent = "Connect Glass first"
            return
        }
        send(.notification(.fake))
        lastEvent = "Fake notification sent"
    }

    private func sendContacts() {
        Task {
            do {
                guard try await requestContactsAccess() else {
                    lastEvent = "Contacts permission denied"
                    send(.contactList(ContactListData(contacts: [])))
                    return
                }

                lastEvent = "Loading contacts"
                var contacts = try await loadContacts()
                if transport == .bluetoothLE, contacts.count > maximumBluetoothContacts {
                    contacts = Array(contacts.prefix(maximumBluetoothContacts))
                }
                send(.contactList(ContactListData(contacts: contacts)))
                lastEvent = "Sent \(contacts.count) contacts"
            } catch {
                lastEvent = "Contacts unavailable: \(error.localizedDescription)"
                send(.contactList(ContactListData(contacts: [])))
            }
        }
    }

    private func send(_ message: RPCMessage) {
        activeHost.send(message)
        telemetryStore.recordMessage(.toGlasses, message: message, transport: transport)
        reloadTelemetryIfNeeded(for: Date())
    }

    private func recordIncomingMessage(_ message: RPCMessage) {
        telemetryStore.recordMessage(.fromGlasses, message: message, transport: transport)
        if case .battery(let battery) = message.payload {
            telemetryStore.recordBatteryStatus(battery)
        }
        reloadTelemetryIfNeeded(for: Date())
    }

    private func reloadTelemetryIfNeeded(for date: Date) {
        let calendar = Calendar.current
        guard calendar.isDate(date, inSameDayAs: selectedTelemetryDate) else { return }
        reloadTelemetry()
    }

    private func reloadTelemetry() {
        dailyTelemetry = telemetryStore.dailyTelemetry(for: selectedTelemetryDate)
    }

    private func requestContactsAccess() async throws -> Bool {
        switch CNContactStore.authorizationStatus(for: .contacts) {
        case .authorized:
            return true
        case .notDetermined:
            return try await withCheckedThrowingContinuation { continuation in
                contactStore.requestAccess(for: .contacts) { granted, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume(returning: granted)
                    }
                }
            }
        case .denied, .restricted:
            return false
        @unknown default:
            return false
        }
    }

    private func loadContacts() async throws -> [ContactData] {
        try await Task.detached(priority: .userInitiated) {
            let contactStore = CNContactStore()
            let keys: [CNKeyDescriptor] = [
                CNContactIdentifierKey as CNKeyDescriptor,
                CNContactGivenNameKey as CNKeyDescriptor,
                CNContactFamilyNameKey as CNKeyDescriptor,
                CNContactOrganizationNameKey as CNKeyDescriptor,
                CNContactPhoneNumbersKey as CNKeyDescriptor
            ]
            let request = CNContactFetchRequest(keysToFetch: keys)
            var results: [ContactData] = []

            try contactStore.enumerateContacts(with: request) { contact, _ in
                let fallbackName = contact.organizationName.isEmpty ? nil : contact.organizationName
                let displayName = CNContactFormatter.string(from: contact, style: .fullName) ?? fallbackName

                for (index, phone) in contact.phoneNumbers.enumerated() {
                    let phoneNumber = phone.value.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !phoneNumber.isEmpty else { continue }

                    let label = phone.label.map { CNLabeledValue<NSString>.localizedString(forLabel: $0) }
                    results.append(ContactData(
                        id: "\(contact.identifier):\(index)",
                        displayName: displayName ?? phoneNumber,
                        phoneNumber: phoneNumber,
                        label: label
                    ))
                }
            }

            return results.sorted {
                ($0.displayName ?? "").localizedCaseInsensitiveCompare($1.displayName ?? "") == .orderedAscending
            }
        }
        .value
    }

    private func placeCall(_ request: CallRequestData) {
        let allowed = CharacterSet(charactersIn: "+0123456789")
        let dialable = String(request.phoneNumber.unicodeScalars.filter { allowed.contains($0) })
        guard !dialable.isEmpty else {
            lastEvent = "Invalid phone number"
            return
        }

        #if canImport(UIKit)
        guard let url = URL(string: "tel://\(dialable)") else {
            lastEvent = "Invalid phone URL"
            return
        }

        UIApplication.shared.open(url) { [weak self] success in
            Task { @MainActor in
                self?.lastEvent = success ? "Calling \(request.displayName ?? dialable)" : "Call request failed"
            }
        }
        #else
        lastEvent = "Calls require iPhone"
        #endif
    }
}

enum ServiceState {
    case stopped
    case waiting
    case connected

    var title: String {
        switch self {
        case .stopped: "Stopped"
        case .waiting: "Waiting"
        case .connected: "Connected"
        }
    }

    var symbolName: String {
        switch self {
        case .stopped: "wifi.slash"
        case .waiting: "wifi"
        case .connected: "checkmark.circle"
        }
    }
}
