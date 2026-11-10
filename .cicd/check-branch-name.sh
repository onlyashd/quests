#!/usr/bin/env bash

# Checks a branch name against Conventional Branch 1.0.0 (https://conventional-branch.github.io).
# Used by the pre-push hook (.githooks/pre-push) and the pull-request workflow.
#
# Usage:
# bash check-branch-name.sh [<branch>]     default: the current branch (detached HEAD passes)
#
# Example:
# `bash check-branch-name.sh feature/voucher-retry` -> ok
# `bash check-branch-name.sh my_branch`             -> error, exit 1
#
# Rules:
# - Long-lived branches: main, master, develop.
# - Everything else is <prefix>/<description>:
#     prefix       feature (or feat), bugfix (or fix), hotfix, release, chore
#     description  lowercase letters, digits and single hyphens, no leading/trailing hyphen
#                  (feature/issue-42-voucher-retry); release branches may also use dots
#                  (release/v1.2.0)
# - Bot branches (dependabot/..., renovate/...) are accepted as they are.
#
# Exit codes: 0 valid · 1 invalid name · 2 invalid usage.
#
# Compatible with bash 3.2 (macOS) and later.

set -euo pipefail

log() { echo "check-branch-name: $*" >&2; }

case "${1:-}" in
  -h|--help) sed -n '3,/^# Compatible/{s/^# \{0,1\}//;p;}' "${BASH_SOURCE[0]}"; exit 0 ;;
  -*) log "error: unknown argument '$1'"; exit 2 ;;
esac
[ $# -le 1 ] || { log "error: expected at most one branch name"; exit 2; }

BRANCH="${1:-}"
if [ -z "$BRANCH" ]; then
  BRANCH="$(git symbolic-ref --quiet --short HEAD 2> /dev/null || true)"
  [ -n "$BRANCH" ] || { log "detached HEAD, nothing to check"; exit 0; }
fi
BRANCH="${BRANCH#refs/heads/}"

case "$BRANCH" in
  main|master|develop) exit 0 ;;
  dependabot/*|renovate/*) exit 0 ;;
esac

WORD='[a-z0-9]+(-[a-z0-9]+)*'
RELEASE_WORD='[a-z0-9]+([.-][a-z0-9]+)*'
re="^(feature|feat|bugfix|fix|hotfix|chore)/$WORD\$"
release_re="^release/$RELEASE_WORD\$"

if [[ "$BRANCH" =~ $re ]] || [[ "$BRANCH" =~ $release_re ]]; then
  exit 0
fi

log "'$BRANCH' is not a Conventional Branch name"
case "$BRANCH" in
  */*/*) log "  use a single '/' after the prefix" ;;
  *[A-Z]*) log "  use lowercase only" ;;
  *_*|*" "*) log "  separate words with hyphens" ;;
  *--*|*/-*|*-) log "  no repeated, leading or trailing hyphens" ;;
esac
cat >&2 << 'EOF'
  expected: main | master | develop | <prefix>/<description>
  prefixes: feature (feat), bugfix (fix), hotfix, release, chore
  examples: feature/voucher-retry, fix/issue-42-wipe-crash, release/v1.2.0
  rename:   git branch -m <new-name>
EOF
exit 1
