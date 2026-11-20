import SwiftUI

@main
@MainActor
struct FlowApp: App {
    @StateObject private var runCoordinator: RunCoordinator
    private let store: PlanStore

    init() {
        _runCoordinator = StateObject(wrappedValue: RunCoordinator(
            notifications: UserNotificationRunSink(),
            haptics: UIKitRunHaptics()
        ))
        let url = FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("plans.json")
        if ProcessInfo.processInfo.arguments.contains("-FlowUITestReset") {
            try? FileManager.default.removeItem(at: url)
        }
        store = PlanStore(fileURL: url)
    }

    var body: some Scene {
        WindowGroup {
            PlanHomeView(store: store)
                .environmentObject(runCoordinator)
        }
    }
}
