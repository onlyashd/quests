# 0012. Version and release each target independently, as reviewed drafts

- **Status:** Accepted
- **Date:** 2026-11-09
- **Specs:** [app §10](../specs/app.md#10-build-distribution--updates-decision-d4); see [releasing.md](../releasing.md)

## Context

The repository builds three deliverables with different lifecycles: the Android app, the iOS
app, and the server (shipped together with the redeemer). A server fix shouldn't force app
releases, and the apps go through different channels ([0005](0005-distribute-outside-app-stores.md)).
Signing keys are kept offline, so CI must never be able to publish a signed build on its own.

## Options considered

1. **One version for the whole repository.** Simple, but every release bumps everything.
2. **One version per target, in a single file, released through one workflow.**

## Decision

- `versions.properties` holds one SemVer version per target (`android`, `ios`, `server`),
  optionally with a pre-release suffix `-(canary|alpha|beta|rc).N`. The Gradle builds read it, so
  it's the single source of truth.
- `.cicd/bump-version.sh` is the only thing that changes it.
- Tags are `<version>-<target>`, e.g. `0.2.0-android` or `0.3.0-rc.1-server`.
- The manually dispatched **Release** workflow:
  - bumps the chosen targets and builds and tests each one;
  - only if everything passes, commits the bump and the `CHANGELOG.md` section (final releases
    only), tags it, and pushes the commit and tags together;
  - opens one **draft** GitHub release per tag.
- Artifacts are **unsigned**. A maintainer reviews the draft, signs offline, and publishes.

## Consequences

- Targets release on their own schedules, and a failed build never leaves a tag behind.
- Each changelog section lists only the commits that touch that target's paths.
- The Android `versionCode` is derived from the version, which caps every part (minor, patch and
  pre-release number below 100, major at most 209).
- iOS `CFBundleShortVersionString` can't hold the pre-release suffix. The app shows the full
  version from `AppInfo`, and the build number is the workflow run number.
- Publishing has a manual step (offline signing), so releases depend on a key holder being
  available.
