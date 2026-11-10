import SwiftUI

/// Main tabs (app.md §5.5). The Leader tab appears only in leader mode.
struct MainTabView: View {
    @EnvironmentObject private var model: AppModel

    enum Tab: Hashable { case quests, mine, wallet, leader, settings }
    @State private var selection: Tab = .quests

    var body: some View {
        TabView(selection: $selection) {
            NavigationStack { EmptyStateView(text: "quests.empty").navigationTitle("tab.quests") }
                .tabItem { Label("tab.quests", systemImage: "magnifyingglass") }
                .tag(Tab.quests)

            NavigationStack { EmptyStateView(text: "mine.empty").navigationTitle("tab.mine") }
                .tabItem { Label("tab.mine", systemImage: "list.bullet") }
                .tag(Tab.mine)

            NavigationStack { SecureContent { WalletPlaceholder() }.navigationTitle("tab.wallet") }
                .tabItem { Label("tab.wallet", systemImage: "star.fill") }
                .tag(Tab.wallet)

            if model.preferences.leaderMode {
                NavigationStack { EmptyStateView(text: "leader.empty").navigationTitle("tab.leader") }
                    .tabItem { Label("tab.leader", systemImage: "hammer.fill") }
                    .tag(Tab.leader)
            }

            NavigationStack { SettingsView() }
                .tabItem { Label("tab.settings", systemImage: "gearshape.fill") }
                .tag(Tab.settings)
        }
        .onChange(of: model.preferences.leaderMode) { enabled in
            if !enabled && selection == .leader { selection = .settings }
        }
    }
}

private struct EmptyStateView: View {
    let text: LocalizedStringKey

    var body: some View {
        Text(text)
            .multilineTextAlignment(.center)
            .foregroundStyle(.secondary)
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Wallet balance = count of unspent vouchers in the vault (app.md §5.2); the vault lands in A1/A6.
private struct WalletPlaceholder: View {
    var body: some View {
        VStack {
            Text(verbatim: "0").font(.system(size: 72, weight: .bold))
            Text("wallet.balance.label").font(.title3)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
