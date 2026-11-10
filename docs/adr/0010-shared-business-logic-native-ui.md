# 0010. Share business logic in Kotlin Multiplatform; keep UI native per platform

- **Status:** Accepted
- **Date:** 2026-11-09
- **Specs:** [app §3](../specs/app.md#3-module--package-structure), [app §4](../specs/app.md#4-platform-abstractions)

## Context

The apps must behave identically where it matters (claim rules, sync, crypto, state machines),
and that logic is security-sensitive, so it should be written and tested once. The first plan put
the UI in a shared Compose Multiplatform module (`:sharedUI`) as well. On iOS that UI looks and
behaves unlike the platform, makes accessibility and system integration harder, and adds a
rendering layer between the app and privacy features such as screen-capture protection.

## Options considered

1. **Two fully native apps.** The best UI, but business rules written twice and drifting apart.
2. **Kotlin Multiplatform with Compose Multiplatform UI.** One codebase, but non-native iOS UI.
3. **Kotlin Multiplatform for business logic only; Jetpack Compose and SwiftUI for UI.**

## Decision

- `:shared` (KMP: Android, iOS) holds domain logic, repositories and DI, and exposes `StateFlow`s
  and suspend functions. It contains **no UI**: no Compose, no view models, no user-visible
  strings. It returns enums that each UI maps to text.
- `:androidApp` is Jetpack Compose with androidx ViewModels. `iosApp/` is SwiftUI with
  `ObservableObject`s.
- Platform services (`SecretStore`, `ImageSanitizer`, ...) are Kotlin interfaces in `:shared`,
  implemented in each app's language and passed to `createAppGraph(...)`.
- Swift interop is plain Kotlin/Native export:
  - `StateFlow` reaches Swift through `Watchable<T>`.
  - Every suspend function called from Swift has `@Throws(Exception::class)`.
- Compose Multiplatform and the `:sharedUI` module are dropped.

## Consequences

- Business rules are tested once in `commonTest`. UI, view models and strings are written twice,
  and a user-facing feature normally ships in both apps in the same change.
- Strings are localized per platform (Android resources, iOS string catalog), in en and pt-BR.
- iOS work needs macOS and Xcode. Contributors on other systems can only type-check the iOS
  Kotlin code (`compileIosMainKotlinMetadata`).
- Interop rules (`@Throws`, `Watchable`) must be followed by hand; a missing `@Throws` turns a
  Kotlin exception into an iOS crash.
