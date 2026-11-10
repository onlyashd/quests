# Contributing to FERA Quests

Thanks for helping! FERA Quests lets people take part in quests and earn points **without an
account**, through a backend built so that even a full compromise reveals nothing about who did
what. Most of the rules below exist to protect that promise, so please read them before your
first pull request.

- **Found a security or privacy problem?** Don't open an issue. [Report it privately](https://github.com/onlyashd/quests/security/advisories/new).
- **Bug or idea?** Use the [issue forms](https://github.com/onlyashd/quests/issues/new/choose). For a larger feature, open an issue first so we can agree on the approach before you write code.

## Setup

| You'll work on | You need |
|---|---|
| Anything | Git, JDK 21 (Gradle downloads its own daemon JDK on first run) |
| Android app | Android SDK with platform 37 (Android Studio is easiest), a device or emulator on Android 11+ |
| iOS app | macOS with Xcode 26 |
| Backend | Docker, for the PostgreSQL/MinIO integration tests and the local stack |

```sh
git clone https://github.com/onlyashd/quests.git
cd quests
bash .cicd/install-hooks.sh   # once per clone: git hooks + pull.rebase
./gradlew build               # Windows: gradlew.bat
```

`install-hooks.sh` enables a `commit-msg` hook (Conventional Commits) and a `pre-push` hook
(Conventional Branch), and sets `pull.rebase = true`. See the [README](README.md) for how to run
each app and the backend.

## Read the docs

Everything lives in [`docs/`](docs). Start with [`docs/architecture.md`](docs/architecture.md) for
the big picture. The specs in [`docs/specs/`](docs/specs) are the source of truth:

- [`protocol.md`](docs/specs/protocol.md): cryptography and wire formats. **Normative**: the code follows it, never the reverse.
- [`backend.md`](docs/specs/backend.md): server, database, API, jobs, operations.
- [`app.md`](docs/specs/app.md): client architecture, screens, platform services, security checklist.

If your change alters behaviour described in a spec, update the spec **in the same pull request**.

The reasons behind the big choices are in [`docs/adr/`](docs/adr), the Architecture Decision
Records. To change one of those choices, or to make a new decision of that size (anything that
changes what the server can learn, adds crypto, a service or a third party, or is hard to
reverse), propose a new ADR in your pull request. See [`docs/adr/README.md`](docs/adr/README.md).

## Project layout

| Path | What | Language / stack |
|---|---|---|
| `protocol/` | Crypto, encodings and DTOs shared by every component | Kotlin Multiplatform (Android, iOS, JVM) |
| `shared/` | App **business logic only**: domain, repositories, DI | Kotlin Multiplatform (Android, iOS) |
| `androidApp/` | Android UI | Kotlin, Jetpack Compose |
| `iosApp/` | iOS UI | Swift, SwiftUI (Xcode project) |
| `server/` | Blind coordinator | Kotlin, Ktor, PostgreSQL, MinIO |
| `redeemer/` | Points-spending counter, deployed separately | Kotlin, Ktor, PostgreSQL |
| `deploy/` | Dockerfiles and the local compose stack | |
| `.cicd/`, `.githooks/`, `.github/` | Release, changelog and convention scripts, hooks, workflows | Bash, GitHub Actions |

## Ground rules

### Privacy comes first

A change that weakens any of these will not be merged, however useful it is:

- The server must not learn more about participants: no IP addresses, user agents, device
  identifiers, proof content, or anything that links one participant's quests together.
- No identity-bearing logs: no request bodies, keys, tokens, vouchers or concrete IDs. Log route
  templates (`/v1/claims/{id}`), not real paths.
- No analytics, crash reporters, ad SDKs, Google Play Services, or any library that opens its own
  network connections. The apps talk to the relay and nothing else.
- All cryptography comes from `:protocol`. Don't re-implement or swap a primitive.
- No secrets, keys or real user data in code, tests, fixtures, screenshots or issues.

The full checklists are in `app.md` §9 and `backend.md` §9. If you're unsure whether something
leaks information, ask in the issue or pull request.

### Shared logic, native UI

- Business rules live in `:shared` and are tested there once, in `commonTest`.
- UI is written separately for each platform: Compose on Android, SwiftUI on iOS. `:shared`
  contains no UI code, view models or user-visible strings. It returns enums that each app maps
  to its own text.
- A user-facing feature normally ships on **both** apps. If one platform has to wait, say so in
  the pull request and open an issue for it.
- Suspend functions called from Swift must be annotated `@Throws(Exception::class)`. Without it, a
  Kotlin exception crashes the iOS app instead of becoming a Swift error.

### Strings

Every user-visible string exists in **English and Brazilian Portuguese**, on both platforms:
`androidApp/src/main/res/values{,-pt-rBR}/strings.xml` and `iosApp/iosApp/Localizable.xcstrings`.

### Dependencies

Keep them few. A new dependency needs a reason in the pull request: what it does, who maintains
it, and what it can see. Versions live in `gradle/libs.versions.toml`.

### Database migrations

Flyway migrations (`*/src/main/resources/db/migration`) are append-only. Never edit a migration
that has been merged; add a new `V<n>__<what>.sql` instead. Every column must justify itself
(`backend.md` §1).

## Tests

Run the tests for what you touched before opening a pull request:

| Area | Command |
|---|---|
| Protocol and shared logic | `./gradlew :protocol:jvmTest :protocol:testAndroidHostTest :shared:testAndroidHostTest` |
| Shared logic on iOS (macOS) | `./gradlew :protocol:iosSimulatorArm64Test :shared:iosSimulatorArm64Test` |
| Android app | `./gradlew :androidApp:assembleDebug`, then check the change on a device or emulator |
| iOS app (macOS) | Build and run `iosApp/iosApp.xcodeproj` in Xcode |
| Backend | `./gradlew :server:test :redeemer:test` with Docker running. Without Docker the integration tests are **skipped**, not failed, and the release build requires them |

New behaviour needs tests. Bug fixes should come with a test that failed before the fix.

## Branches, commits and pull requests

**Branches** follow [Conventional Branch](https://conventional-branch.github.io):
`<prefix>/<description>` in lowercase with hyphens.

| Prefix | For |
|---|---|
| `feature/` (or `feat/`) | New features |
| `bugfix/` (or `fix/`) | Bug fixes |
| `hotfix/` | Urgent fixes to a release |
| `release/` | Release preparation (dots allowed: `release/v1.2.0`) |
| `chore/` | Everything else: docs, tooling, dependencies |

Examples: `feature/voucher-retry`, `fix/issue-42-wipe-crash`.

**Commits** follow [Conventional Commits](https://www.conventionalcommits.org):

```
<type>(<optional scope>)!: <description>

<optional body>

<optional footers, e.g. BREAKING CHANGE: ...>
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.
The scope is optional and lowercase, e.g. `sync`, `android`, `server`, `protocol`. Mark breaking
changes (protocol, API, schema or stored data) with `!`. The header is at most 100 characters.

```
feat(sync): retry voucher redemption on 503
fix(android): keep the wipe button disabled while busy
feat(protocol)!: switch submission envelopes to HPKE mode auth
```

The commit types feed `CHANGELOG.md`, so pick them with care: `feat` and `fix` are what users read.

**Pull requests**

1. Rebase on `main` instead of merging it in (`git pull` already rebases with the hook setup).
2. Write the title as a Conventional Commit. Pull requests are squash-merged and the title becomes
   the commit on `main`.
3. Fill in the template, including the privacy checklist.
4. Clean up `fixup!` / `squash!` commits before review is final (`git rebase -i --autosquash main`).

The **Pull request** workflow checks the branch name, the title and every commit, so commits made
with `--no-verify` are still caught. To fix a commit message, reword it with
`git rebase -i main` and force-push your branch.

## Releases

Maintainers release through the **Release** workflow (Actions → Release). It bumps
`versions.properties`, builds and tests the chosen platforms, updates `CHANGELOG.md` for final
releases, tags `<version>-<target>` (e.g. `0.2.0-android`), and opens a draft GitHub release for
review. Don't edit `versions.properties`, `CHANGELOG.md` or release tags by hand.

Release artifacts are unsigned. Signing keys are kept offline and applied after review.
