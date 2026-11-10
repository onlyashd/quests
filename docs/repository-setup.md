# Repository setup

GitHub settings the workflows and documents rely on. They live outside the repository, so
they're listed here; re-check them after transferring or forking the repository.

## General (Settings → General)

- **Pull requests:** allow **squash merging** only, with the default commit message set to
  "Pull request title". The checked PR title becomes the commit on `main`, and therefore the
  changelog entry ([ADR 0013](adr/0013-conventional-commits-and-branches.md)). Disable merge
  commits and rebase merging.
- Enable **Automatically delete head branches**.

## Branch protection (Settings → Rules → Rulesets)

A ruleset for `main`:

- Require a pull request before merging, with at least one approval.
- Require status checks: **Conventions** (from `pull-request.yml`) and the **CodeQL** analyses.
- Block force pushes and deletions.
- Require linear history.

<a id="release-token"></a>
**Release token.** The Release workflow pushes the version commit and tags to `main` as
`github-actions[bot]`, which a ruleset blocks unless something may bypass it. Either:

- add a `RELEASE_TOKEN` repository secret: a fine-grained personal access token or a GitHub App
  token with *Contents: read and write*, whose owner is on the ruleset's bypass list (preferred);
  or
- add *GitHub Actions* to the ruleset's bypass list.

`release.yml` uses `RELEASE_TOKEN` when it's set, and falls back to `GITHUB_TOKEN` otherwise.

A ruleset protecting tags `*-android`, `*-ios`, `*-server` from deletion and updates is also
recommended. Published tags must never move.

## Security (Settings → Code security)

- Enable **Private vulnerability reporting**. `SECURITY` links in the issue chooser and in
  `CONTRIBUTING.md` point to it.
- Enable **Dependabot alerts**. Security updates are optional, but every new or upgraded
  dependency still has to respect the forbidden list (no analytics, crash reporting, Play
  Services, ...).
- Code scanning is set up by `codeql.yml`; use "advanced" setup, not "default".

## Actions (Settings → Actions → General)

- **Workflow permissions:** "Read repository contents" (the default). Jobs ask for more
  explicitly.
- Allow GitHub Actions to create pull requests: not needed.
- For fork pull requests, require approval for first-time contributors.

## Issues

- Labels used by the issue forms: `bug`, `enhancement` (GitHub defaults; keep them).
- Issue forms and the PR template live in `.github/`. Blank issues are disabled.

## Checklist for a new maintainer

- [ ] Write access to the repository, and on the ruleset bypass list if you'll release.
- [ ] Read [architecture.md](architecture.md), the [ADRs](adr), and [releasing.md](releasing.md).
- [ ] Ran `bash .cicd/install-hooks.sh` in your clone.
- [ ] For releases: access to the offline Android signing key, and to the AltStore source.
