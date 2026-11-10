<!--
The PR title becomes the squash-merge commit, so it must be a Conventional Commit:
  feat(sync): retry voucher redemption on 503
  fix(android)!: drop support for v0 identities
The Pull request workflow checks the title, the branch name and every commit.
-->

## Summary

<!-- What does this change and why? Link the spec section it implements, if any. -->

Closes #

## Type of change

- [ ] `feat`: new feature
- [ ] `fix`: bug fix
- [ ] `perf` / `refactor`: no behaviour change
- [ ] `docs` / `test` / `build` / `ci` / `chore`
- [ ] **Breaking**: protocol, API, database schema or stored-data change (explain the migration below)

## Targets affected

- [ ] Android app (`androidApp`)
- [ ] iOS app (`iosApp`)
- [ ] Shared logic (`shared`, `protocol`)
- [ ] Server (`server`) / Redeemer (`redeemer`)
- [ ] CI / tooling

<!-- UI is native per platform: a user-facing feature in `shared` usually needs both apps. Say so if one is left for later. -->

## How was it tested?

<!-- Commands run, devices/simulators used, manual steps. -->

- [ ] `./gradlew :protocol:jvmTest :shared:testAndroidHostTest`
- [ ] `./gradlew :server:test :redeemer:test` (with Docker, so Testcontainers tests run)
- [ ] Android app checked on a device or emulator
- [ ] iOS app checked on a device or simulator
- [ ] New or changed behaviour is covered by tests

## Screenshots

<!-- For UI changes: before/after, Android and iOS, light and dark, en and pt-BR where relevant. Delete if not applicable. -->

## Privacy & security

The backend is a blind coordinator (backend spec §1 and §9). Tick each box, or explain below why it doesn't hold.

- [ ] The server learns nothing new about participants: no IPs, user agents, device identifiers, proof plaintext or links between a participant's quests
- [ ] No identity-bearing logs: no request bodies, keys, tokens, vouchers or concrete IDs (route templates only)
- [ ] No secrets, keys or real user data in code, tests, fixtures or screenshots
- [ ] No new third-party dependency, or the new one is justified below (what it does, who maintains it, what it can see)

## Migration / rollout notes

<!-- Database migrations, protocol versioning, config or secrets to add, release order between server and apps. Delete if none. -->
