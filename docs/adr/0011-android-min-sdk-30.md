# 0011. Require Android 11 (API 30) or later

- **Status:** Accepted
- **Date:** 2026-11-09
- **Specs:** [app §10](../specs/app.md#10-build-distribution--updates-decision-d4), [app §4](../specs/app.md#4-platform-abstractions)

## Context

Several privacy and security mechanisms behave inconsistently or are missing on older Android
versions:

- reliable Android Keystore behaviour;
- `setUnlockedDeviceRequired` for the identity key;
- `ImageDecoder`, used to strip image metadata by re-encoding;
- scoped storage everywhere.

Working around them means extra code paths in the most sensitive parts of the app, each needing
its own tests.

## Decision

`minSdk` is **30** (Android 11) in the `protocol`, `shared` and `androidApp` build files.

## Consequences

- One implementation of `SecretStore` and `ImageSanitizer`, with no compatibility branches.
- Devices on Android 10 and older can't install the app. That's an accepted loss for a
  privacy-first app distributed by direct download.
