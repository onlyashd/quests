# CI, scripts and hooks

## Workflows (`.github/workflows/`)

| Workflow              | Runs on                                  | Does                                                                                                                       |
|-----------------------|------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| `pull-request.yml`    | PR opened, edited, reopened, new commits | Branch name, PR title and every commit against the conventions ([ADR 0013](adr/0013-conventional-commits-and-branches.md)) |
| `codeql.yml`          | Push/PR to `main`, Mondays 04:27 UTC     | CodeQL for Kotlin/Java, Swift and the workflows themselves                                                                 |
| `release.yml`         | Manual (Actions → Release)               | Bump, build, tag, changelog, draft releases; see [releasing.md](releasing.md)                                              |
| `android-release.yml` | Called by `release.yml`                  | Shared tests + unsigned release APK                                                                                        |
| `ios-release.yml`     | Called by `release.yml`                  | iOS simulator tests + unsigned `.ipa`                                                                                      |
| `server-release.yml`  | Called by `release.yml`                  | Backend tests (Docker required; skipped tests fail the build) + distributions + image build check                          |

All workflows default to `permissions: contents: read`; only the release publish job gets
`contents: write`. Values from events and inputs reach scripts through `env:`, never by
interpolating `${{ }}` into `run:` blocks, which prevents script injection.

### Pull request checks

The checks run the scripts from the **base** branch, so a pull request can't loosen the rules it's
checked against. A change to the rules takes effect once merged. On the very first PR, when the base
has no scripts yet, the PR's own copy is used, with a warning.

### Release builds

Each platform workflow:

1. downloads the bumped `versions.properties` from the `prepare` job, and checks that it holds the
   expected version;
2. runs the tests for that target;
3. builds the artifact;
4. uploads it as `release-<target>` with a `.sha256` next to each file.

| Target  | Runner         | Notes                                                                                                                                                                             |
|---------|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| android | `ubuntu-24.04` | `:androidApp:assembleRelease`, unsigned                                                                                                                                           |
| ios     | `macos-26`     | `xcodebuild -sdk iphoneos CODE_SIGNING_ALLOWED=NO`, then zipped into `Payload/`. `MARKETING_VERSION` drops the pre-release suffix and `CURRENT_PROJECT_VERSION` is the run number |
| server  | `ubuntu-24.04` | Testcontainers needs Docker (available on the runner). The job fails if any backend test was skipped, then builds both Dockerfiles without pushing                                |

Action versions are pinned to major tags (`actions/checkout@v7`, ...). Update them together, and
check runner images when bumping the Android SDK or Xcode.

## Scripts (`.cicd/`)

All are bash 3.2 compatible (macOS), print usage with `--help`, log to stderr, and use exit code 0
for success, 1 for bad data and 2 for bad usage.

| Script                 | Used by                               | Purpose                                                                      |
|------------------------|---------------------------------------|------------------------------------------------------------------------------|
| `bump-version.sh`      | `release.yml`                         | Bump one or all targets in `versions.properties`                             |
| `update-changelog.sh`  | `release.yml`                         | Add a final release's section to `CHANGELOG.md`                              |
| `check-commit-msg.sh`  | `commit-msg` hook, `pull-request.yml` | Conventional Commits check, for a message file, a message, or a commit range |
| `check-branch-name.sh` | `pre-push` hook, `pull-request.yml`   | Conventional Branch check                                                    |
| `install-hooks.sh`     | contributors, once per clone          | `core.hooksPath = .githooks`, `pull.rebase = true`                           |

The scripts have no test suite in the repository. When changing one, test it in a throwaway
`git init` repository, covering both valid and invalid inputs, and the CRLF case on Windows.

**Path lists:** `update-changelog.sh` and the release notes in `release.yml` each list which source
paths belong to which target (`paths_of`). Keep the two lists in sync when adding a module.

## Git hooks (`.githooks/`)

| Hook         | Checks                                                          | Bypass                   |
|--------------|-----------------------------------------------------------------|--------------------------|
| `commit-msg` | The message being committed                                     | `git commit --no-verify` |
| `pre-push`   | Names of branches being pushed (tags and deletions are skipped) | `git push --no-verify`   |

Hooks are a convenience; the PR workflow is the gate. `.gitattributes` keeps hooks and scripts on LF
endings, because a CRLF shebang breaks bash.
