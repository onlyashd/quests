# FERA Quests

Docs: [`docs/`](docs) (specs, architecture decision records, maintainer guides) · Specs: [`docs/specs/app.md`](docs/specs/app.md) · [`docs/specs/backend.md`](docs/specs/backend.md) · [`docs/specs/protocol.md`](docs/specs/protocol.md)

### Apps
The apps share **business logic only**; each UI is native to its platform.

| Module | What |
|---|---|
| `:shared` | Kotlin Multiplatform business logic (domain, repositories, DI). iOS framework `Shared` |
| `:androidApp` | Android UI — Jetpack Compose, ViewModels, Keystore-backed `SecretStore` |
| `iosApp/` | iOS UI — SwiftUI, `ObservableObject`s, Keychain-backed `SecretStore` |

 - shared logic tests: `./gradlew :shared:testAndroidHostTest` (iOS simulator tests need macOS)

### Android
To run the application on an Android device or emulator:  
 - open the project in Android Studio and run the imported Android run configuration  

To build the application bundle:  
 - run `./gradlew :androidApp:assembleDebug`  
 - find `.apk` file in `androidApp/build/outputs/apk/debug/androidApp-debug.apk`  

### iOS
To run the application on an iPhone or simulator:  
 - Open `iosApp/iosApp.xcodeproj` in Xcode and run standard configuration  
 - or use the [Kotlin Multiplatform plugin](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform) for IDEA  


### Backend
Specs: [`docs/specs/backend.md`](docs/specs/backend.md) · [`docs/specs/protocol.md`](docs/specs/protocol.md). Requires JDK 21.

| Module | What |
|---|---|
| `:protocol` | Shared DTOs and wire encodings (KMP: android, iOS, jvm) |
| `:server` | Ktor blind coordinator — Postgres (Flyway) + MinIO |
| `:redeemer` | Points-spending counter — separate deployable and database |

 - tests: `./gradlew :protocol:jvmTest :server:test :redeemer:test` (Testcontainers tests are skipped when Docker is unavailable)
 - local stack: `./gradlew :server:installDist :redeemer:installDist` then `docker compose -f deploy/docker-compose.yml up --build`
 - health: `curl localhost:9090/healthz` (server) · `curl localhost:9091/healthz` (redeemer)
 - migrations run on startup; or `server/build/install/server/bin/server admin migrate`

### Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md): setup (`bash .cicd/install-hooks.sh`), privacy ground rules, tests, and the Conventional Commits / Conventional Branch conventions enforced by the hooks and the `Pull request` workflow. Coding agents: see [AGENTS.md](AGENTS.md).
