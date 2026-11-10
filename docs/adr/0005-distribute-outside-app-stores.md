# 0005. Distribute the apps outside the app stores

- **Status:** Accepted
- **Date:** 2026-11-09 (source spec Decision D4)
- **Specs:** [app §10](../specs/app.md#10-build-distribution--updates-decision-d4)

## Context

Store distribution brings dependencies and identifiers that conflict with the privacy goals.
Google Play pushes Play Services (integrity checks, update APIs), and the App Store ties installs
to an Apple ID. Store accounts and review processes also put a single company between the project
and its users. Participants are a known community, and can be guided through sideloading.

## Options considered

1. **Google Play and the App Store.** Easiest installs; brings Play Services pressure, store
   identifiers and review gatekeeping.
2. **Outside the stores.** Android: direct APK plus an F-Droid repository. iOS: AltStore /
   SideStore and EU alternative marketplaces.

## Decision

- **Android:** direct APK download plus an **F-Droid** repository (self-hosted first, the main
  F-Droid repository later). No Play Services anywhere in the dependency graph. Release builds
  must be **reproducible** so F-Droid can verify them, and the signing key is kept offline.
- **iOS:** AltStore / SideStore (`.ipa` plus a source JSON), and EU alternative marketplaces
  where available. No App Attest, no StoreKit. Bundle ID `org.ferabrasil.quests`.
- **Updates:** the app learns of new versions from the signed `/v1/config` and shows a banner. It
  never updates itself silently.

## Consequences

- Device attestation (Play Integrity, App Attest) is unavailable, so another anti-abuse gate is
  needed: [0006](0006-invite-codes-as-sybil-gate.md).
- iOS installs from free developer accounts must be re-signed every 7 days; the install guide
  has to explain it.
- CI produces **unsigned** artifacts, and a maintainer signs them offline after review
  ([0012](0012-independent-versions-per-target.md)).
- Reproducible Android builds constrain the toolchain: pinned JDK, deterministic resources and
  `SOURCE_DATE_EPOCH`.
