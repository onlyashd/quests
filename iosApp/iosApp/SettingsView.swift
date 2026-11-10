import SwiftUI
import Shared

/// Settings (app.md §5.4). Tokens, export, app lock, network status arrive with their milestones.
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var confirmingWipe = false
    @State private var typed = ""

    var body: some View {
        Form {
            Section {
                Picker("settings.theme", selection: themeBinding) {
                    Text("theme.system").tag(ThemeMode.system)
                    Text("theme.light").tag(ThemeMode.light)
                    Text("theme.dark").tag(ThemeMode.dark)
                }
                .pickerStyle(.segmented)
            } header: {
                Text("settings.theme")
            }

            Section {
                Toggle("settings.leader_mode", isOn: leaderBinding)
            } footer: {
                Text("settings.leader_mode.hint")
            }

            Section {
                Button("settings.wipe", role: .destructive) {
                    typed = ""
                    confirmingWipe = true
                }
                .disabled(model.busy)
            }

            Section {
                // From versions.properties via Shared, pre-release suffix included (unlike
                // CFBundleShortVersionString). Selectable so it can be copied into a bug report.
                LabeledContent("settings.version") {
                    Text(verbatim: AppInfo.shared.version)
                        .textSelection(.enabled)
                }
            }
        }
        .navigationTitle("tab.settings")
        // Wipe requires typing the confirmation word (app.md §5.4).
        .alert("wipe.title", isPresented: $confirmingWipe) {
            TextField(wipeWord, text: $typed)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            Button("wipe.confirm", role: .destructive) {
                if model.wipeWordMatches(typed, expected: wipeWord) { model.wipeIdentity() }
            }
            Button("cancel", role: .cancel) {}
        } message: {
            Text("wipe.body \(wipeWord)")
        }
    }

    private var wipeWord: String { String(localized: "wipe.word") }

    private var themeBinding: Binding<ThemeMode> {
        Binding(get: { model.preferences.theme }, set: { model.setTheme($0) })
    }

    private var leaderBinding: Binding<Bool> {
        Binding(get: { model.preferences.leaderMode }, set: { model.setLeaderMode($0) })
    }
}
