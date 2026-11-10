import SwiftUI
import Shared

/// iOS presentation state for the app shell, built on the shared repositories
/// (the Android counterpart is `RootViewModel`).
@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var identity: IdentityState = .loading
    @Published private(set) var preferences = AppPreferences(theme: .system, leaderMode: false)
    @Published private(set) var busy = false
    @Published var showError = false

    private let graph: AppGraph
    private var subscriptions: [Subscription] = []

    init(graph: AppGraph) {
        self.graph = graph
        subscriptions = [
            Subscription(Watchable<IdentityState>(flow: graph.identity.state)) { [weak self] in
                self?.identity = $0
            },
            Subscription(Watchable<AppPreferences>(flow: graph.preferences.state)) { [weak self] in
                self?.preferences = $0
            },
        ]
        run { try await graph.identity.load() }
    }

    var colorScheme: ColorScheme? {
        switch preferences.theme {
        case .light: return .light
        case .dark: return .dark
        default: return nil
        }
    }

    func createIdentity() { run { try await self.graph.identity.create() } }

    func wipeIdentity() { run { try await self.graph.wipeIdentity.invoke() } }

    func wipeWordMatches(_ typed: String, expected: String) -> Bool {
        graph.wipeIdentity.isConfirmed(typed: typed, expected: expected)
    }

    func setTheme(_ theme: ThemeMode) { graph.preferences.setTheme(theme: theme) }

    func setLeaderMode(_ enabled: Bool) { graph.preferences.setLeaderMode(enabled: enabled) }

    private func run(_ block: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do { try await block() } catch { showError = true }
        }
    }
}
