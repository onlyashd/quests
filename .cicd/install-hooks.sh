#!/usr/bin/env bash

# One-time setup of a clone (git does not let a repository ship its own config):
#   core.hooksPath = .githooks   commit-msg: Conventional Commits   (.cicd/check-commit-msg.sh)
#                                pre-push:   Conventional Branches  (.cicd/check-branch-name.sh)
#   pull.rebase = true           `git pull` rebases local commits instead of creating merge commits
#
# Usage: bash .cicd/install-hooks.sh
# Undo:  git config --unset core.hooksPath && git config --unset pull.rebase

set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"
git config core.hooksPath .githooks
git config pull.rebase true
chmod +x .githooks/* .cicd/*.sh 2> /dev/null || true
echo "install-hooks: core.hooksPath = .githooks (commit-msg, pre-push), pull.rebase = true"
