import SwiftUI

@main
struct AnotherGlassApp: App {
    @StateObject private var store = CompanionStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
        }
    }
}
