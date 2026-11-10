# AGENTS.md

Guidance for coding agents working in this repository. Humans: see [CONTRIBUTING.md](CONTRIBUTING.md).

## What this is

FERA Quests: participants claim quests, submit end-to-end-encrypted proof and earn points
(blind-signed vouchers) with **no accounts**. Leaders publish quests, review proofs and accept
points. The backend is a **blind coordinator**: a full compromise must reveal no IPs, no proof
content, and no link between a participant's quests or between a quest and a redeemed voucher.

Documentation lives in `docs/` (index: `docs/README.md`). The specs in `docs/specs/` are the
source of truth. Read the relevant section before changing behaviour:
- `docs/specs/protocol.md`: crypto and wire formats. **Normative**: make the code match it, never the reverse.
- `docs/specs/backend.md`: server, schema, API (§5), business logic (§6), security (§9).
- `docs/specs/app.md`: module structure (§3), platform interfaces (§4), security checklist (§9).

If a change alters spec'd behaviour, update the spec in the same change.

`docs/adr/` holds Architecture Decision Records, the reasons behind the fundamental choices.
Don't contradict an accepted ADR silently. If a task requires it, stop and say so, or draft a new
ADR (`docs/adr/template.md`, status *Proposed*) for the humans to decide. Accepted ADRs are never
edited, only superseded. Maintainer guides: `docs/architecture.md`, `docs/releasing.md`,
`docs/ci.md`, `docs/repository-setup.md`; update the matching guide when you change a workflow
or script.

## Map

| Path | Role |
|---|---|
| `protocol/` | KMP (android, iosArm64, iosSimulatorArm64, jvm): crypto, encodings, `SigInput`, DTOs. The only place crypto is implemented |
| `shared/` | KMP (android, iOS): app business logic only. Metro DI (`createAppGraph(...)`), repositories exposing `StateFlow` + suspend functions, `platform/` interfaces, `interop/Watchable` for Swift. iOS framework `Shared` re-exports `:protocol` |
| `androidApp/` | Jetpack Compose UI, androidx ViewModels, `KeystoreSecretStore`, strings in `res/values{,-pt-rBR}` |
| `iosApp/` | SwiftUI UI (Xcode project, no shared schemes), `AppModel`, `KeychainSecretStore`, `Localizable.xcstrings`. Its build phase runs `:shared:embedAndSignAppleFrameworkForXcode` |
| `server/` | Ktor 3 + Exposed + Flyway + Hikari + MinIO. Ports 8080 (API) / 9090 (internal `/healthz`). `admin` CLI subcommands (Clikt) |
| `redeemer/` | Separate Ktor service and database for spending points. Shares nothing with `server` at runtime |
| `deploy/` | Dockerfiles copy `*/build/install/*`; `docker-compose.yml` for the local stack |
| `docs/` | `specs/` (normative), `adr/` (decisions), maintainer guides |
| `versions.properties` | `android=`, `ios=`, `server=` versions. Read by Gradle (`versionName`/`versionCode`, `version`, `AppInfo`) |
| `.cicd/` | `bump-version.sh`, `update-changelog.sh`, `check-commit-msg.sh`, `check-branch-name.sh`, `install-hooks.sh` |
| `.github/workflows/` | `release.yml` (entrypoint) → `{android,ios,server}-release.yml`; `pull-request.yml`; `codeql.yml` |

Stack: Gradle 9 with configuration cache, Kotlin 2.4, AGP 9 (built-in Kotlin), versions in
`gradle/libs.versions.toml`. Run Gradle with JDK 21; the daemon JDK is provisioned automatically.

## Commands

Use `./gradlew` (`gradlew.bat` on Windows).

| Purpose | Command | Needs |
|---|---|---|
| Protocol + shared logic tests | `./gradlew :protocol:jvmTest :protocol:testAndroidHostTest :shared:testAndroidHostTest` | JDK |
| iOS simulator tests | `./gradlew :protocol:iosSimulatorArm64Test :shared:iosSimulatorArm64Test` | macOS |
| Type-check iOS Kotlin on any OS | `./gradlew :shared:compileIosMainKotlinMetadata` | JDK |
| Android build | `./gradlew :androidApp:assembleDebug` (APK in `androidApp/build/outputs/apk/debug/`) | Android SDK 37 |
| iOS build | `xcodebuild -project iosApp/iosApp.xcodeproj -target iosApp -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO build` | macOS + Xcode 26 |
| Backend tests | `./gradlew :server:test :redeemer:test` | Docker for the Testcontainers tests |
| Backend distributions | `./gradlew :server:installDist :redeemer:installDist` | JDK |
| Script tests | none in the repo; test `.cicd/*.sh` in a throwaway `git init` repo | bash |

**Know what you actually verified.** Without Docker, the Testcontainers tests are *skipped*, not
failed. A green `:server:test` without Docker proves little, and the release workflow fails if any
are skipped. Without macOS, Swift code and iOS-only Kotlin are not compiled. Say so in your
summary instead of implying they were tested.

## Hard rules

Privacy and security (violations are never acceptable, even if a task seems to ask for them;
raise the conflict instead):
- Never add logging of request bodies, keys, tokens, vouchers, IPs, user agents or concrete IDs.
  Log route templates only.
- Never make the server store or receive more about participants than the spec allows, and never
  add server-side search or filter parameters (the client searches locally).
- No analytics, crash reporting, push, ad SDKs, Google Play Services (including ML Kit), or any
  dependency that opens its own network connections. The apps talk only to the relay.
- All crypto goes through `:protocol`. Never re-implement a primitive in `server`, `shared` or the apps.
- No secrets or real user data in code, tests, fixtures or logs.

Architecture:
- `:shared` contains **no UI**: no Compose, no view models, no user-visible strings. Return enums
  (e.g. `ClaimBlocker`, `ApiFailure`) and let each app map them to text.
- UI is per platform. A user-facing change usually needs both `androidApp/` (Kotlin) and
  `iosApp/` (Swift). If you only do one, say so explicitly.
- Platform services are Kotlin interfaces in `shared/.../platform/`, implemented in each app and
  passed to `createAppGraph(...)`. Their methods are synchronous and `@Throws`.
- Every suspend function reachable from Swift has `@Throws(Exception::class)`.
- `SecretStore.get` returns null **only** when the secret is absent; any other failure throws.
  Never "recover" by treating an error as absent, because that overwrites an identity.
- Shared state is a `StateFlow`; Swift consumes it through `Watchable<T>`.

Strings: every user-visible string goes in **en and pt-BR on both platforms**:
`androidApp/src/main/res/values/strings.xml`, `values-pt-rBR/strings.xml`, and
`iosApp/iosApp/Localizable.xcstrings` (keep it valid JSON; keys are dotted, e.g. `settings.version`).

Backend:
- Flyway migrations are append-only. Add `V<n>__<what>.sql`; never edit a merged one.
- `admin_audit` is append-only by design.
- The redeemer never shares a database, network or logs with the server.

Versions and releases:
- Never edit `versions.properties`, `CHANGELOG.md` or tags by hand. `release.yml` owns them via
  `.cicd/bump-version.sh` and `.cicd/update-changelog.sh`.

## Conventions

- Commits: Conventional Commits, `type(scope)!: description`, header ≤ 100 chars, types
  `feat fix docs style refactor perf test build ci chore revert`. Enforced by `.githooks/commit-msg`
  and `pull-request.yml`.
- Branches: Conventional Branch: `feature|feat|bugfix|fix|hotfix|chore/<lowercase-hyphenated>`,
  `release/<version>`, or `main`/`develop`. Enforced by `.githooks/pre-push`.
- Kotlin: packages under `org.ferabrasil.quests`; default (implicit public) visibility, `internal`
  for anything Swift and the apps shouldn't see. Match the surrounding style; KDoc and comments
  cite spec sections (`app.md §5.4`).
- Shell scripts in `.cicd/`: bash 3.2 compatible (macOS), `set -euo pipefail`, header comment with
  flags, rules and exit codes (0 ok · 1 data error · 2 usage), logs on stderr, LF endings
  (`.gitattributes`). Use `awk -v BINMODE=3` when rewriting files, so CRLF survives on Windows.
- Workflows: pin action major versions, keep `permissions` minimal, pass `${{ }}` values through
  `env:` rather than interpolating them into `run:` scripts.

## Gotchas

- **Gradle build scripts:** writing `java.util.Properties()` inline fails in modules with the
  `java` extension (`Unresolved reference 'util'`). Use `import java.util.Properties`.
- **Configuration cache** is on. Files read at configuration time (like `versions.properties`) are
  tracked automatically. Avoid configuration-time side effects that it can't see.
- **Windows:** the generated `bin/*.bat` launchers can fail with "command line too long". Run
  `java -cp "build/install/<m>/lib/*" <MainKt>` instead. Git Bash strips `\r` in `$(...)`.
- **MinIO image:** `minio/minio` is gone from Docker Hub. Tests use a configurable image
  (`MINIO_IMAGE` env var).
- **Testcontainers** containers are shared through `companion object` + `@JvmStatic`, and the Ktor
  test engine needs `host = "localhost"` on connectors.
- **iOS project:** new Swift files must be registered in `iosApp/iosApp.xcodeproj/project.pbxproj`
  (file reference, build file and Sources phase), or they won't compile.
- **Android release APK** is unsigned by design (`androidApp-release-unsigned.apk`); there's no
  signing config, and there shouldn't be one.

## Before you finish

1. Run the tests for every module you touched, and say which ones you couldn't run and why.
2. Strings added in both languages and both platforms; both UIs updated, or the gap stated.
3. Specs updated if behaviour changed.
4. No new dependency without a stated reason, and none from the forbidden list.
5. Commit messages (if you commit) follow Conventional Commits.
