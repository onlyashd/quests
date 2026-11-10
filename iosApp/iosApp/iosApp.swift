import SwiftUI
import Shared

// iOS UI: SwiftUI. Business logic comes from the Kotlin `Shared` framework (app.md §3).
@main
struct QuestsApp: App {
    @StateObject private var model = AppModel(
        graph: AppGraphKt.createAppGraph(secretStore: KeychainSecretStore())
    )

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
        }
    }
}
