#!/usr/bin/env bash

# CI script to add a release's section to ../CHANGELOG.md from the commits it contains.
#
# Flags:
# command      alias  accepted arguments          default value
# --tag        -t     <version>-<target>          (required) e.g. 0.2.0-android
# --ref        -r     any git revision            the tag if it exists, else HEAD
# --date       -d     YYYY-MM-DD                  the tag's date if it exists, else today (UTC)
#
# Example:
# after tagging   -> `bash update-changelog.sh --tag 0.2.0-android`
# before tagging  -> `bash update-changelog.sh --tag 0.2.0-android --ref HEAD`
#                    (what release.yml does, so CHANGELOG.md lands in the release commit)
#
# Rules:
# - Pre-releases (0.2.0-rc.1-android) are skipped: the script exits 0 without touching the
#   file. Their commits are picked up by the next final release.
# - The section lists the commits in `<previous>..<ref>`, where <previous> is the highest
#   final release tag of the same target below this version (pre-release tags are ignored,
#   so a final release covers everything since the last final release). Without one, the
#   whole history up to <ref> is used.
# - Only commits touching the target's source paths are listed (see paths_of). Merge commits
#   and `chore(release):` commits are left out.
# - Conventional Commit subjects are grouped: breaking changes (`type!:`), features (feat),
#   fixes (fix), performance (perf), everything else under "Other changes".
# - Sections are inserted newest-first, above the first existing `## ` section. Re-running
#   for the same tag replaces its section, so the script is idempotent.
#
# Output (stdout): the path of the updated file, or nothing when skipped. Logs go to stderr.
#
# Environment:
# CHANGELOG_FILE     overrides the path of CHANGELOG.md (default: ../CHANGELOG.md relative to
#                    this script).
# GITHUB_SERVER_URL  with GITHUB_REPOSITORY, turns commit hashes and the section heading into
# GITHUB_REPOSITORY  links (set automatically in GitHub Actions).
#
# Exit codes: 0 ok or skipped · 1 git / data error · 2 invalid usage.
#
# Compatible with bash 3.2 (macOS) and later.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHANGELOG_FILE="${CHANGELOG_FILE:-$SCRIPT_DIR/../CHANGELOG.md}"

log() { echo "update-changelog: $*" >&2; }
die() { log "error: $1"; exit "${2:-1}"; }
usage_error() { log "error: $1"; log "run with --help for usage"; exit 2; }

usage() {
  sed -n '3,/^# Compatible/{s/^# \{0,1\}//;p;}' "${BASH_SOURCE[0]}"
}

# Source paths whose history belongs to each target. Keep in sync with release.yml.
paths_of() {
  case "$1" in
    android) echo "androidApp shared protocol gradle build.gradle.kts settings.gradle.kts" ;;
    ios) echo "iosApp shared protocol gradle build.gradle.kts settings.gradle.kts" ;;
    server) echo "server redeemer protocol deploy gradle build.gradle.kts settings.gradle.kts" ;;
  esac
}

label_of() {
  case "$1" in android) echo Android ;; ios) echo iOS ;; server) echo Server ;; esac
}

# --- arguments ---------------------------------------------------------------

TAG=""
REF=""
DATE=""

while [ $# -gt 0 ]; do
  arg="$1"
  value=""
  has_inline=false
  case "$arg" in
    -*=*) value="${arg#*=}"; arg="${arg%%=*}"; has_inline=true ;;
  esac
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --tag|-t|--ref|-r|--date|-d)
      if ! $has_inline; then
        [ $# -ge 2 ] || usage_error "$arg needs a value"
        value="$2"
        shift
      fi
      case "$arg" in
        --tag|-t) TAG="$value" ;;
        --ref|-r) REF="$value" ;;
        --date|-d) DATE="$value" ;;
      esac
      ;;
    *) usage_error "unknown argument '$1'" ;;
  esac
  shift
done

[ -n "$TAG" ] || usage_error "--tag is required"

NUM='(0|[1-9][0-9]*)'
if ! echo "$TAG" | grep -Eq "^$NUM\.$NUM\.$NUM(-(canary|alpha|beta|rc)\.$NUM)?-(android|ios|server)\$"; then
  usage_error "'$TAG' is not <version>-<target> (e.g. 0.2.0-android, 0.2.0-rc.1-ios)"
fi
TARGET="${TAG##*-}"
VERSION="${TAG%-*}"

case "$VERSION" in
  *-*) log "$TAG is a pre-release, CHANGELOG.md left untouched"; exit 0 ;;
esac

if [ -n "$DATE" ] && ! echo "$DATE" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'; then
  usage_error "--date must be YYYY-MM-DD (got '$DATE')"
fi

# --- git ---------------------------------------------------------------------

git rev-parse --git-dir > /dev/null 2>&1 || die "not inside a git repository"

tag_exists=false
git rev-parse -q --verify "refs/tags/$TAG" > /dev/null && tag_exists=true

if [ -z "$REF" ]; then
  if $tag_exists; then REF="$TAG"; else REF=HEAD; fi
fi
git rev-parse -q --verify "$REF^{commit}" > /dev/null || die "'$REF' is not a commit"

if [ -z "$DATE" ]; then
  if $tag_exists; then
    DATE="$(git log -1 --format=%cs "$TAG^{commit}")"
    # Annotated tags carry their own date: prefer it over the commit's.
    if [ "$(git cat-file -t "$TAG")" = tag ]; then
      DATE="$(git for-each-ref --format='%(taggerdate:short)' "refs/tags/$TAG")"
    fi
  else
    DATE="$(date -u +%Y-%m-%d)"
  fi
fi

# Highest final tag of this target strictly below VERSION.
PREV="$(git tag --list "*-$TARGET" \
  | grep -E "^$NUM\.$NUM\.$NUM-$TARGET\$" \
  | awk -F'[.-]' -v cur="$VERSION" '
      BEGIN { split(cur, c, "."); best = "" }
      {
        below = ($1 < c[1]) || ($1 == c[1] && ($2 < c[2] || ($2 == c[2] && $3 < c[3])))
        if (!below) next
        if (best == "" || $1 > b1 || ($1 == b1 && ($2 > b2 || ($2 == b2 && $3 > b3)))) {
          best = $0; b1 = $1; b2 = $2; b3 = $3
        }
      }
      END { print best }')"

if [ -n "$PREV" ]; then RANGE="$PREV..$REF"; else RANGE="$REF"; fi
log "$TAG: commits in ${PREV:+$PREV..}$REF"

REPO_URL=""
if [ -n "${GITHUB_SERVER_URL:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
  REPO_URL="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY"
fi

# --- section -----------------------------------------------------------------

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
SECTION="$TMP_DIR/section.md"

# shellcheck disable=SC2046 # paths are intentionally split
git log --no-merges --format='%h%x1f%H%x1f%s' "$RANGE" -- $(paths_of "$TARGET") \
  > "$TMP_DIR/commits"

awk -F'\037' -v BINMODE=3 -v repo="$REPO_URL" '
  function item(short, full, text,    ref) {
    ref = (repo != "") ? "[" short "](" repo "/commit/" full ")" : short
    return "- " text " (" ref ")"
  }
  {
    short = $1; full = $2; subject = $3
    if (subject ~ /^chore\(release\):/) next

    type = ""; scope = ""; breaking = 0; text = subject
    # type(scope)!: description
    if (match(subject, /^[A-Za-z]+(\([^)]*\))?!?: /)) {
      head = substr(subject, 1, RLENGTH - 2)
      text = substr(subject, RLENGTH + 1)
      if (head ~ /!$/) { breaking = 1; head = substr(head, 1, length(head) - 1) }
      if (match(head, /\(.*\)$/)) {
        scope = substr(head, RSTART + 1, RLENGTH - 2)
        head = substr(head, 1, RSTART - 1)
      }
      type = tolower(head)
    }
    if (scope != "") text = "**" scope ":** " text
    line = item(short, full, text)

    if (breaking) group = "breaking"
    else if (type == "feat") group = "feat"
    else if (type == "fix") group = "fix"
    else if (type == "perf") group = "perf"
    else group = "other"
    lines[group] = lines[group] line "\n"
    count++
  }
  END {
    n = split("breaking feat fix perf other", order, " ")
    title["breaking"] = "Breaking changes"
    title["feat"] = "Features"
    title["fix"] = "Fixes"
    title["perf"] = "Performance"
    title["other"] = "Other changes"
    if (count == 0) { print "_No changes affecting this target._"; exit }
    first = 1
    for (i = 1; i <= n; i++) {
      g = order[i]
      if (!(g in lines)) continue
      if (!first) print ""
      first = 0
      print "### " title[g]
      print ""
      printf "%s", lines[g]
    }
  }
' "$TMP_DIR/commits" > "$TMP_DIR/body"

HEADING="## [$TAG] - $DATE"
if [ -n "$REPO_URL" ]; then
  if [ -n "$PREV" ]; then
    HEADING="## [$TAG]($REPO_URL/compare/$PREV...$TAG) - $DATE"
  else
    HEADING="## [$TAG]($REPO_URL/tree/$TAG) - $DATE"
  fi
fi

{
  echo "$HEADING"
  echo
  echo "$(label_of "$TARGET") $VERSION${PREV:+ (since $PREV)}."
  echo
  cat "$TMP_DIR/body"
} > "$SECTION"

# --- file --------------------------------------------------------------------

if [ ! -s "$CHANGELOG_FILE" ]; then
  cat > "$CHANGELOG_FILE" << 'EOF'
# Changelog

Final releases of each FERA Quests target (`android`, `ios`, `server`), newest first.
Sections are generated from the commits of each release tag by `.cicd/update-changelog.sh`;
pre-releases are not listed.
EOF
  log "created $CHANGELOG_FILE"
fi

# Keep the file's line endings (a CRLF checkout on Windows stays CRLF).
EOL='\n'
if head -n 1 "$CHANGELOG_FILE" | od -c | grep -q '\\r'; then EOL='\r\n'; fi

# Drop any existing section for this tag, then insert the new one above the first section.
awk -v BINMODE=3 -v tag="$TAG" -v section="$SECTION" '
  function emit(   l) {
    if (done) return
    if (prev_blank == 0 && NR > 1) print ""
    while ((getline l < section) > 0) print l
    close(section)
    print ""
    done = 1
  }
  /^## / {
    skipping = (index($0, "## [" tag "]") == 1)
    if (skipping) next
    emit()
  }
  skipping { next }
  { print; prev_blank = ($0 ~ /^[[:space:]]*$/) }
  END {
    if (!done) {
      if (!prev_blank) print ""
      while ((getline l < section) > 0) print l
    }
  }
' "$CHANGELOG_FILE" > "$TMP_DIR/changelog"

# Collapse runs of blank lines left by a replaced section; keep the file permissions.
awk -v BINMODE=3 -v eol="$EOL" '
  { sub(/\r$/, "") }
  /^[[:space:]]*$/ { if (blank++) next; printf "%s", eol; next }
  { blank = 0; printf "%s%s", $0, eol }' \
  "$TMP_DIR/changelog" > "$TMP_DIR/changelog.clean"
cat "$TMP_DIR/changelog.clean" > "$CHANGELOG_FILE"

log "added $TAG ($(grep -c '^- ' "$SECTION" || true) commit(s)) to $CHANGELOG_FILE"
echo "$CHANGELOG_FILE"
