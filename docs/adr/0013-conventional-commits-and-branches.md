# 0013. Enforce Conventional Commits and Conventional Branch names

- **Status:** Accepted
- **Date:** 2026-11-09
- **Specs:** none; see [ci.md](../ci.md) and [CONTRIBUTING.md](../../CONTRIBUTING.md)

## Context

The changelog is generated from commit messages ([0012](0012-independent-versions-per-target.md)),
so messages need a predictable structure. Without one, the changelog is noise, and breaking
changes go unannounced. Consistent branch names make the purpose of open work visible at a glance.

## Decision

- Commit messages follow **Conventional Commits 1.0.0**:
  - types `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`;
  - an optional lowercase scope, `!` for breaking changes, and a header of at most 100 characters.
- Branches follow **Conventional Branch**:
  - `feature|feat|bugfix|fix|hotfix|chore/<lowercase-hyphenated>`, or `release/<version>`;
  - plus `main`, `master` and `develop`;
  - bot branches (`dependabot/`, `renovate/`) are exempt.
- Enforcement happens in three places, all using the same scripts in `.cicd/`:
  1. a `commit-msg` hook and a `pre-push` hook (`.githooks/`, enabled by `.cicd/install-hooks.sh`);
  2. the **Pull request** workflow, which checks the branch, the PR title and every commit, using
     the scripts from the *base* branch so a PR can't relax its own rules;
  3. squash-merging, so the checked PR title becomes the commit on `main`.
- `pull.rebase = true` is set by the same install script, to avoid merge commits from `git pull`.

## Consequences

- Changelog sections are grouped automatically (breaking changes, features, fixes, performance,
  other).
- Hooks are opt-in per clone, and can be bypassed with `--no-verify`; the PR workflow is the
  real gate.
- Contributors have to learn the format. The hooks print the expected format and an example on
  every rejection.
