import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var store: CompanionStore

    var body: some View {
        NavigationStack {
            List {
                transportSection
                serviceSection
                deviceSection
                extensionsSection
                testSection
                notesSection
            }
            .navigationTitle("AnotherGlass")
            .toolbar {
                ToolbarItem(placement: .automatic) {
                    Button {
                        store.toggleService()
                    } label: {
                        Image(systemName: store.isServiceRunning ? "stop.fill" : "play.fill")
                    }
                    .accessibilityLabel(store.isServiceRunning ? "Stop service" : "Start service")
                }
            }
        }
    }

    private var serviceSection: some View {
        Section("Service") {
            HStack {
                Label(store.serviceState.title, systemImage: store.serviceState.symbolName)
                Spacer()
                if store.isServiceRunning {
                    ProgressView()
                }
            }

            Button {
                store.toggleService()
            } label: {
                Label(store.isServiceRunning ? "Stop \(store.transport.title)" : "Start \(store.transport.title)",
                      systemImage: store.isServiceRunning ? "stop.circle" : "play.circle")
            }

            if store.transport == .wifi {
                LabeledContent("Port", value: "\(ServiceID.defaultPort)")
                LabeledContent("Local IP", value: store.localAddress)
            } else {
                LabeledContent("Service", value: "AnotherGlass")
            }
            LabeledContent("Last event", value: store.lastEvent)
        }
    }

    private var transportSection: some View {
        Section("Connection") {
            Picker("Mode", selection: $store.transport) {
                ForEach(CompanionTransport.allCases) { transport in
                    Text(transport.title).tag(transport)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    private var deviceSection: some View {
        Section("Glass") {
            LabeledContent("Device", value: store.connectedDeviceName ?? "Not connected")
            LabeledContent("Battery", value: store.batteryText)
        }
    }

    private var extensionsSection: some View {
        Section("Extensions") {
            Toggle(isOn: $store.isGPSEnabled) {
                Label("GPS Passthrough", systemImage: "location")
            }

            LabeledContent("Last location", value: store.latestLocationText)
        }
    }

    private var testSection: some View {
        Section("Test") {
            Button {
                store.sendFakeNotification()
            } label: {
                Label("Send Fake Notification", systemImage: "bell.badge")
            }
            .disabled(store.serviceState != .connected)
        }
    }

    private var notesSection: some View {
        Section("iOS Notes") {
            Label("Notifications are forwarded to Glass directly over ANCS in Bluetooth LE mode.", systemImage: "bell.badge")
                .foregroundStyle(.secondary)
            Label("The iOS app cannot inspect notification contents itself.", systemImage: "lock")
                .foregroundStyle(.secondary)
            Label("Third-party media sessions cannot be controlled globally.", systemImage: "music.note")
                .foregroundStyle(.secondary)
            if let command = store.lastMediaCommand {
                LabeledContent("Last Glass media command", value: command)
            }
            if let request = store.lastSiriRequest {
                LabeledContent("Last Siri request", value: request)
            }
        }
    }
}
