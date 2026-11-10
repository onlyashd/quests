#!/usr/bin/env bash

# Checks commit messages against Conventional Commits 1.0.0 (https://www.conventionalcommits.org).
# Used by the commit-msg hook (.githooks/commit-msg) and the pull-request workflow.
#
# Flags (exactly one):
# command     alias  argument      what is checked
# --file      -f     <path>        a commit message file, as git hands it to the commit-msg hook:
#                                  comment lines and everything below the scissors line are
#                                  ignored; fixup!/squash!/amend! commits are allowed (they are
#                                  squashed away before review)
# --message   -m     <text>        one message, e.g. a pull request title
# --range     -r     <a>..<b>      every commit in the range of the repository in the current
#                                  directory, e.g. a pull request's base..head
#
# Example:
# `bash check-commit-msg.sh --message "feat(sync): retry vouchers on 503"` -> ok
# `bash check-commit-msg.sh --message "Added stuff"`                       -> error, exit 1
#
# Rules:
# - Header: <type>[(<scope>)][!]: <description>
#     type        feat fix docs style refactor perf test build ci chore revert
#     scope       lowercase letters, digits and . _ / - (e.g. `sync`, `android/ui`)
#     !           marks a breaking change (a `BREAKING CHANGE:` footer works too)
#     description non-empty, after exactly one space
# - The header is at most 100 characters.
# - A body, if any, is separated from the header by a blank line.
# - Git's own merge (`Merge ...`) and revert (`Revert "..."`) headers are accepted.
# - fixup!/squash!/amend! headers are accepted only with --file.
#
# Exit codes: 0 valid · 1 invalid message(s) · 2 invalid usage.
#
# Compatible with bash 3.2 (macOS) and later.

set -euo pipefail

TYPES="feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert"
MAX_HEADER=100

log() { echo "check-commit-msg: $*" >&2; }
usage_error() { log "error: $1"; log "run with --help for usage"; exit 2; }
usage() { sed -n '3,/^# Compatible/{s/^# \{0,1\}//;p;}' "${BASH_SOURCE[0]}"; }

hint() {
  cat >&2 << EOF
  expected: <type>[(<scope>)][!]: <description>
  types:    ${TYPES//|/, }
  example:  feat(sync): retry voucher redemption on 503
            fix!: reject proofs signed with revoked keys
EOF
}

# validate <message file> <label> <allow autosquash: true|false>
# Prints problems to stderr; returns 1 if the message is invalid.
validate() {
  local file="$1" label="$2" allow_autosquash="$3"
  local header second
  # First non-blank line is the header; the line after it must be blank.
  header="$(awk '{ sub(/\r$/, "") } NF { print; exit }' "$file")"
  second="$(awk '{ sub(/\r$/, "") } NF && !seen { seen = 1; getline nxt; sub(/\r$/, "", nxt); print nxt; exit }' "$file")"

  if [ -z "$header" ]; then
    log "$label: empty commit message"
    return 1
  fi

  case "$header" in
    "Merge "*) return 0 ;;
    "Revert \""*) return 0 ;;
    "fixup! "*|"squash! "*|"amend! "*)
      if [ "$allow_autosquash" = true ]; then return 0; fi
      log "$label: '$header'"
      log "  autosquash commit: squash it (git rebase -i --autosquash) before merging"
      return 1
      ;;
  esac

  local ok=0
  local re="^($TYPES)(\\([a-z0-9][a-z0-9._/-]*\\))?!?: [^[:space:]]"
  if ! [[ "$header" =~ $re ]]; then
    log "$label: '$header'"
    log "  not a Conventional Commit header"
    case "$header" in
      [A-Z]*:*) log "  the type must be lowercase" ;;
      *"):"[^\ ]*|*[a-z]:[^\ ]*) log "  put exactly one space after the colon" ;;
    esac
    hint
    ok=1
  fi
  if [ "${#header}" -gt "$MAX_HEADER" ]; then
    log "$label: header is ${#header} characters, the limit is $MAX_HEADER"
    ok=1
  fi
  if [ -n "${second//[[:space:]]/}" ]; then
    log "$label: leave a blank line between the header and the body"
    ok=1
  fi
  return $ok
}

# --- arguments ---------------------------------------------------------------

MODE=""
VALUE=""
while [ $# -gt 0 ]; do
  arg="$1"
  value=""
  has_inline=false
  case "$arg" in
    -*=*) value="${arg#*=}"; arg="${arg%%=*}"; has_inline=true ;;
  esac
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --file|-f|--message|-m|--range|-r)
      [ -z "$MODE" ] || usage_error "use only one of --file, --message, --range"
      if ! $has_inline; then
        [ $# -ge 2 ] || usage_error "$arg needs a value"
        value="$2"
        shift
      fi
      case "$arg" in
        --file|-f) MODE=file ;;
        --message|-m) MODE=message ;;
        --range|-r) MODE=range ;;
      esac
      VALUE="$value"
      ;;
    *) usage_error "unknown argument '$1'" ;;
  esac
  shift
done
[ -n "$MODE" ] || usage_error "one of --file, --message, --range is required"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

case "$MODE" in
  file)
    [ -f "$VALUE" ] || usage_error "no such file '$VALUE'"
    # Drop what git strips itself: comment lines and the `git commit -v` diff below the scissors.
    comment="$(git config --get core.commentChar 2> /dev/null || true)"
    case "$comment" in ""|auto) comment="#" ;; esac
    awk -v c="$comment" '
      { line = $0; sub(/\r$/, "", line) }
      line ~ /^. -+ >8 -+$/ && substr(line, 1, 1) == c { exit }
      substr(line, 1, length(c)) == c { next }
      { print line }
    ' "$VALUE" > "$TMP_DIR/msg"
    if validate "$TMP_DIR/msg" "commit message" true; then exit 0; fi
    log "(the message was kept in $(git rev-parse --git-dir 2> /dev/null || echo .git)/COMMIT_EDITMSG — reuse it with: git commit -e -F <that file>)"
    exit 1
    ;;

  message)
    printf '%s\n' "$VALUE" > "$TMP_DIR/msg"
    validate "$TMP_DIR/msg" "message" false
    ;;

  range)
    case "$VALUE" in *..*) ;; *) usage_error "--range must look like <a>..<b> (got '$VALUE')" ;; esac
    git rev-parse --git-dir > /dev/null 2>&1 || { log "error: not inside a git repository"; exit 1; }
    git rev-list "$VALUE" > "$TMP_DIR/shas" 2> "$TMP_DIR/err" || {
      log "error: cannot list '$VALUE': $(cat "$TMP_DIR/err")"
      exit 1
    }
    total=0
    bad=0
    while read -r sha; do
      total=$((total + 1))
      git log -1 --format=%B "$sha" > "$TMP_DIR/msg"
      validate "$TMP_DIR/msg" "commit $(git rev-parse --short "$sha")" false || bad=$((bad + 1))
    done < "$TMP_DIR/shas"
    if [ "$bad" -gt 0 ]; then
      log "$bad of $total commit(s) in $VALUE are not Conventional Commits"
      log "fix them with: git rebase -i <base>  (reword), then force-push"
      exit 1
    fi
    log "$total commit(s) in $VALUE ok"
    ;;
esac
