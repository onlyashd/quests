#!/usr/bin/env bash

# CI script to bump an artifact version in ../versions.properties.
#
# Flags:
# command        alias  accepted arguments            default value
# --target      -tgt    [android, ios, server, all]   "all"
# --bump-type   -bt     <major, minor, patch>
# --pre-release -pr     <canary, alpha, beta, rc>     "false" if not provided
#
# Example:
# minor from '2.0.1' -> `bash bump-version.sh --target android --bump-type minor` -> outputs '2.1.0'
# patch alpha from '2.1.0' -> `bash bump-version.sh --target android --bump-type patch --pre-release alpha` -> outputs '2.1.1-alpha.0'
#
# Versions are SemVer: MAJOR.MINOR.PATCH, optionally followed by -<tag>.<n>
# where tag is one of canary < alpha < beta < rc.
#
# Rules:
# - Release bump from a release:   2.0.1 --bt minor             -> 2.1.0
# - Release bump from a pre-release finalizes it when the bump does not
#   go past the pre-release's base:
#                                   2.1.1-rc.2 --bt patch        -> 2.1.1
#                                   2.1.0-rc.2 --bt minor        -> 2.1.0
#                                   2.1.1-rc.2 --bt minor        -> 2.2.0
# - Pre-release bump starts at .0:  2.1.0 --bt patch --pr alpha  -> 2.1.1-alpha.0
# - Pre-release bump on the same base iterates or promotes the tag:
#                                   2.1.1-alpha.0 --bt patch --pr alpha -> 2.1.1-alpha.1
#                                   2.1.1-alpha.1 --bt patch --pr beta  -> 2.1.1-beta.0
#   Going back to a lower tag (beta -> alpha) on the same base is an error.
#
# Output (stdout): for a single target, the new version (e.g. `2.1.0`). For
# `--target all`, one `target=version` line per target, ready to append to
# $GITHUB_OUTPUT. Everything else goes to stderr.
#
# Environment:
# VERSIONS_FILE  overrides the path of versions.properties (default: ../versions.properties
#                relative to this script).
#
# Exit codes: 0 ok · 1 invalid version data · 2 invalid usage.
#
# Compatible with bash 3.2 (macOS runners) and GNU/BSD awk. BINMODE=3 stops Windows
# builds of gawk (Git Bash) from translating CRLF; other awks ignore it.

set -euo pipefail

readonly TARGETS="android ios server"
readonly PRE_RELEASE_TAGS="canary alpha beta rc"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSIONS_FILE="${VERSIONS_FILE:-$SCRIPT_DIR/../versions.properties}"

usage() {
  sed -n '3,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
}

die() { # die <exit-code> <message>
  echo "bump-version: $2" >&2
  exit "$1"
}

contains() { # contains <word> <space-separated list>
  case " $2 " in *" $1 "*) return 0 ;; *) return 1 ;; esac
}

tag_rank() { # tag_rank <tag> -> 0-based index in PRE_RELEASE_TAGS
  local i=0 t
  for t in $PRE_RELEASE_TAGS; do
    if [ "$t" = "$1" ]; then echo "$i"; return 0; fi
    i=$((i + 1))
  done
  return 1
}

# ── Arguments ────────────────────────────────────────────────────────────────

target="all"
bump_type=""
pre_release="false"

need_value() { # need_value <flag> <remaining-arg-count>
  [ "$2" -ge 2 ] || die 2 "missing value for $1"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --target=*) target="${1#*=}" ;;
    --bump-type=*) bump_type="${1#*=}" ;;
    --pre-release=*) pre_release="${1#*=}" ;;
    --target | -tgt) need_value "$1" $#; target="$2"; shift ;;
    --bump-type | -bt) need_value "$1" $#; bump_type="$2"; shift ;;
    --pre-release | -pr) need_value "$1" $#; pre_release="$2"; shift ;;
    -h | --help) usage; exit 0 ;;
    *) usage; die 2 "unknown argument: $1" ;;
  esac
  shift
done

contains "$target" "$TARGETS all" || die 2 "invalid --target '$target' (expected: ${TARGETS// /, }, all)"
[ -n "$bump_type" ] || { usage; die 2 "--bump-type is required"; }
contains "$bump_type" "major minor patch" || die 2 "invalid --bump-type '$bump_type' (expected: major, minor, patch)"
contains "$pre_release" "$PRE_RELEASE_TAGS false" ||
  die 2 "invalid --pre-release '$pre_release' (expected: ${PRE_RELEASE_TAGS// /, })"
[ -f "$VERSIONS_FILE" ] || die 1 "versions file not found: $VERSIONS_FILE"

# ── Version logic ────────────────────────────────────────────────────────────

read_version() { # read_version <target> -> value of `<target>=` in the versions file
  local value
  value="$(awk -v BINMODE=3 -F= -v key="$1" '
    { sub(/\r$/, "") }
    $1 ~ "^[ \t]*" key "[ \t]*$" { sub(/^[^=]*=[ \t]*/, ""); sub(/[ \t]+$/, ""); print; found = 1; exit }
    END { if (!found) exit 1 }
  ' "$VERSIONS_FILE")" || die 1 "no '$1' entry in $VERSIONS_FILE"
  echo "$value"
}

bump() { # bump <current-version> -> next version, using $bump_type and $pre_release
  local current="$1" major minor patch tag="" num=""
  local re='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-([a-z]+)\.(0|[1-9][0-9]*))?$'
  [[ "$current" =~ $re ]] || die 1 "malformed version '$current' (expected MAJOR.MINOR.PATCH[-tag.N])"
  major="${BASH_REMATCH[1]}"
  minor="${BASH_REMATCH[2]}"
  patch="${BASH_REMATCH[3]}"
  tag="${BASH_REMATCH[5]}"
  num="${BASH_REMATCH[6]}"
  if [ -n "$tag" ]; then
    contains "$tag" "$PRE_RELEASE_TAGS" || die 1 "unknown pre-release tag '$tag' in '$current'"
  fi

  # Base version a release bump lands on. A pre-release already "contains" the
  # bump when its base is at or past it (2.1.0-rc.1 + minor -> 2.1.0).
  local b_major="$major" b_minor="$minor" b_patch="$patch"
  case "$bump_type" in
    major)
      if [ -z "$tag" ] || [ "$minor" -ne 0 ] || [ "$patch" -ne 0 ]; then
        b_major=$((major + 1)); b_minor=0; b_patch=0
      fi ;;
    minor)
      if [ -z "$tag" ] || [ "$patch" -ne 0 ]; then
        b_minor=$((minor + 1)); b_patch=0
      fi ;;
    patch)
      if [ -z "$tag" ]; then
        b_patch=$((patch + 1))
      fi ;;
  esac
  local base="$b_major.$b_minor.$b_patch"
  local same_base=false
  [ -n "$tag" ] && [ "$base" = "$major.$minor.$patch" ] && same_base=true

  if [ "$pre_release" = "false" ]; then
    echo "$base"
  elif [ "$same_base" = true ]; then
    local cur_rank new_rank
    cur_rank="$(tag_rank "$tag")"
    new_rank="$(tag_rank "$pre_release")"
    if [ "$new_rank" -eq "$cur_rank" ]; then
      echo "$base-$tag.$((num + 1))"
    elif [ "$new_rank" -gt "$cur_rank" ]; then
      echo "$base-$pre_release.0"
    else
      die 1 "cannot go from '$tag' back to '$pre_release' on $base (bump the version or finalize it first)"
    fi
  else
    echo "$base-$pre_release.0"
  fi
}

write_versions() { # write_versions "<target>=<version>" ... -> rewrites the file in place
  local tmp updates
  updates="$(printf '%s\n' "$@")"
  tmp="$(mktemp "${VERSIONS_FILE}.XXXXXX")"
  # Rewrites only the matched keys; keeps order, comments, other keys and line endings.
  awk -v BINMODE=3 -v updates="$updates" '
    BEGIN {
      n = split(updates, lines, "\n")
      for (i = 1; i <= n; i++) { split(lines[i], kv, "="); if (kv[1] != "") value[kv[1]] = kv[2] }
    }
    {
      cr = sub(/\r$/, "")
      key = $0; sub(/=.*/, "", key); gsub(/[ \t]/, "", key)
      if (index($0, "=") && (key in value)) $0 = key "=" value[key]
      printf "%s%s\n", $0, (cr ? "\r" : "")
    }
  ' "$VERSIONS_FILE" > "$tmp" || { rm -f "$tmp"; die 1 "could not rewrite $VERSIONS_FILE"; }
  # Copy contents back (instead of mv) to keep the file's permissions.
  cat "$tmp" > "$VERSIONS_FILE"
  rm -f "$tmp"
}

# ── Main ─────────────────────────────────────────────────────────────────────

if [ "$target" = "all" ]; then selected="$TARGETS"; else selected="$target"; fi

# Compute every new version first so a failure leaves the file untouched.
results=()
for t in $selected; do
  current="$(read_version "$t")"
  next="$(bump "$current")"
  echo "bump-version: $t $current -> $next" >&2
  results+=("$t=$next")
done

write_versions "${results[@]}"

if [ "$target" = "all" ]; then
  printf '%s\n' "${results[@]}"
else
  echo "${results[0]#*=}"
fi
