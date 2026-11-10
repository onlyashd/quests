# Releasing

How to cut a release of the Android app, the iOS app or the server. Why it works this way:
[ADR 0012](adr/0012-independent-versions-per-target.md).

## Concepts

- **Targets:** `android`, `ios`, `server` (the server target also ships the redeemer).
- **Versions** live in `versions.properties`, one per target:
  `MAJOR.MINOR.PATCH`, optionally `-<channel>.<n>` with channels `canary < alpha < beta < rc`.
- **Tags** are `<version>-<target>`: `0.2.0-android`, `0.3.0-rc.1-server`.
- **Never** edit `versions.properties`, `CHANGELOG.md` or release tags by hand. The Release workflow
  owns them.

## Cutting a release

1. Make sure `main` is green, and nobody is about to merge. The release fails safely if the branch
   moves while it builds, but you'd have to run it again.
2. Go to **Actions → Release → Run workflow**, on `main`, and choose:

    | Input       | Values                                                         |
    |-------------|----------------------------------------------------------------|
    | target      | `all`, `android`, `ios` or `server`                            |
    | bump-type   | `patch`, `minor` or `major`                                    |
    | pre-release | `none` for a final release, or `canary`, `alpha`, `beta`, `rc` |

3. The workflow:
    1. **Bump:** computes the new versions (`.cicd/bump-version.sh`). It stops if a tag already
       exists. The run summary shows the plan.
    2. **Build:** runs each selected target's workflow; see [ci.md](ci.md#release-builds).
    3. **Publish:** only if every selected build passed:
        - commits `versions.properties` and, for final releases, the `CHANGELOG.md` sections;
        - creates the annotated tags;
        - pushes the commit and the tags together;
        - opens one **draft** release per tag, with the changes, the artifacts and their SHA-256.
4. **Review each draft:** the changelog, the pull requests, and the artifacts. Edit the notes where
   needed.
5. **Sign and publish** (below), then click **Publish release**.

## How versions move

`bump-version.sh` applies these rules (run it with `--help` for the full list):

| From           | Bump  | Pre-release | To                                      |
|----------------|-------|-------------|-----------------------------------------|
| `0.1.0`        | minor | none        | `0.2.0`                                 |
| `0.1.0`        | minor | rc          | `0.2.0-rc.0`                            |
| `0.2.0-rc.0`   | minor | rc          | `0.2.0-rc.1` (same base: iterate)       |
| `0.2.0-rc.1`   | minor | none        | `0.2.0` (finalize)                      |
| `0.2.0-beta.3` | minor | rc          | `0.2.0-rc.0` (promote)                  |
| `0.2.0-rc.1`   | minor | beta        | error: can't go back to a lower channel |

A typical cycle: `minor` + `rc` → test the draft → `minor` + `rc` again for fixes → `minor` +
`none` to finalize.

## Changelog

Final releases add a section to `CHANGELOG.md` (`.cicd/update-changelog.sh`):

- It lists the commits since the target's previous **final** tag, so a final release includes
  everything that went out in its release candidates.
- Only commits that touch the target's paths are listed.
- They're grouped by Conventional Commit type: breaking changes, features, fixes, performance,
  other.
- Pre-releases don't get a section.

Commit messages *are* the changelog. Good PR titles make good release notes
([ADR 0013](adr/0013-conventional-commits-and-branches.md)).

## Signing and publishing

CI builds are **unsigned** on purpose: signing keys never touch CI.

**Android** (`fera-quests-android-<version>-unsigned.apk`):

1. Download the APK from the draft and check its SHA-256 against the release notes.
2. Sign it with the offline release key:
   `apksigner sign --ks <keystore> --out fera-quests-android-<version>.apk <unsigned.apk>`.
3. Upload the signed APK to the draft, then publish it to the F-Droid repository and the download
   page.

**iOS** (`fera-quests-ios-<version>-unsigned.ipa`): AltStore / SideStore re-sign on install. Check
the SHA-256, then update the AltStore source JSON to point at the published asset.

**Server** (`fera-quests-{server,redeemer}-<version>.tar`): build the images from the tagged commit
with `deploy/Dockerfile.*` and deploy them
per [backend §10](specs/backend.md#10-local-development--deployment). Deploy the server before apps
that depend on new endpoints.

## When something goes wrong

| Symptom                              | What happened                                                                       | Fix                                                                                                                         |
|--------------------------------------|-------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| Bump fails: "Tag ... already exists" | That version was already released                                                   | Choose another bump; never move or delete a published tag                                                                   |
| Bump fails on channel order          | e.g. `rc` → `beta` on the same base                                                 | Use the same or a higher channel, or finalize                                                                               |
| A build fails                        | Tests or build broke; nothing was committed or tagged                               | Fix on `main` and rerun the whole workflow                                                                                  |
| Push rejected (non-fast-forward)     | `main` moved during the build                                                       | Rerun from the new `main`                                                                                                   |
| Push rejected (protected branch)     | The bot can't bypass branch protection                                              | Configure `RELEASE_TOKEN` ([repository-setup.md](repository-setup.md#release-token))                                        |
| Pushed, but draft creation failed    | Tags exist without releases. Don't rerun the workflow: the version is already taken | Download the `release-<target>` artifacts from the run, then `gh release create <tag> <files> --draft --verify-tag` by hand |
| A published release is broken        |                                                                                     | Release a new patch. Don't delete tags: F-Droid and users may already have the build                                        |
