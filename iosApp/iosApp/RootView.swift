import SwiftUI
import Shared

/// Root switch (app.md §5.5): onboarding until an identity exists, then the main tabs.
struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch model.identity {
            case .absent:
                WelcomeView()
            case .present:
                MainTabView()
            default:
                ProgressView()
            }
        }
        .preferredColorScheme(model.colorScheme)
        // ScreenPrivacy (app.md §4): hide content in the app switcher snapshot.
        .overlay {
            if scenePhase != .active {
                Rectangle().fill(.ultraThinMaterial).ignoresSafeArea()
            }
        }
        .alert("error.generic", isPresented: $model.showError) {
            Button("ok", role: .cancel) {}
        }
    }
}

/// ScreenPrivacy for sensitive screens (C5): hidden while the screen is being recorded or mirrored.
struct SecureContent<Content: View>: View {
    @ViewBuilder let content: () -> Content
    @State private var captured = UIScreen.main.isCaptured

    var body: some View {
        ZStack {
            if captured {
                Image(systemName: "eye.slash").font(.largeTitle).foregroundStyle(.secondary)
            } else {
                content()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            captured = UIScreen.main.isCaptured
        }
    }
}
