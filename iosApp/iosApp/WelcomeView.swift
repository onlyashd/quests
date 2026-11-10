import SwiftUI

/// Onboarding → Welcome (app.md §5.1). Create identity / Verify backup / Restore get their
/// own screens with BIP-39 in milestone A1.
struct WelcomeView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showRestoreNote = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Spacer()
            Text("welcome.title").font(.largeTitle.bold())
            Text("welcome.body").font(.body)
            Spacer().frame(height: 16)
            Button {
                model.createIdentity()
            } label: {
                Text("welcome.create").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(model.busy)

            Button {
                showRestoreNote = true
            } label: {
                Text("welcome.restore").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(model.busy)

            if showRestoreNote {
                Text("welcome.restore.soon")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
            }
            Spacer()
        }
        .padding(24)
    }
}
