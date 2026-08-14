#!/usr/bin/env bash
# Assert that what README.md's "What it plays" table advertises is what a
# bundle actually carries.
#
# Two directions, both of which have failed before:
#   1. A claim names a component no bundle has -- the README advertised
#      mov_text for the whole life of the project while the whitelist asked
#      for a name FFmpeg does not use, so the decoder was never built.
#   2. A claim is edited or dropped from the table while docs/format-claims.txt
#      keeps asserting it, leaving the join stale and the check meaningless.
#
# Usage: check-readme-formats.sh <manifest.txt> [README.md] [format-claims.txt]
set -euo pipefail

MANIFEST="${1:?usage: check-readme-formats.sh <manifest.txt> [README.md] [claims.txt]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
README="${2:-$ROOT/README.md}"
CLAIMS="${3:-$ROOT/docs/format-claims.txt}"

for f in "$MANIFEST" "$README" "$CLAIMS"; do
    [ -f "$f" ] || { echo "check-readme-formats: no such file: $f" >&2; exit 1; }
done

# Only the table's own rows, not the whole README. Searching the whole file
# made the stale direction nearly toothless: prose elsewhere -- the tier
# paragraph, the subtitle section -- kept matching claim texts after the table
# was gone, so fifteen claims including every subtitle one survived deleting
# the table outright.
TABLE="$(awk '/^## What it plays/ {inside = 1; next}
              /^## / {inside = 0}
              inside && /^\|/ {print}' "$README")"
[ -n "$TABLE" ] || { echo "check-readme-formats: found no table under '## What it plays' in $README" >&2; exit 1; }

# The manifest is one line per component kind: "<kind> <name> <name> ...".
# Matched by word rather than by pipeline: a `grep -q` closing the pipe early
# raises SIGPIPE upstream, which under `set -o pipefail` reports a present
# component as missing.
have() { # kind, name
    local line
    line="$(grep "^$1 " "$MANIFEST" || true)"
    [ -n "$line" ] || return 1
    case " ${line#* } " in *" $2 "*) return 0 ;; esac
    return 1
}

missing=0
stale=0
claims=0
# `|| [ -n "$line" ]` so a file whose last line has no newline is still read.
while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *)
        echo "MALFORMED: no '=' in claim line: $line" >&2; missing=$((missing + 1)); continue ;;
    esac
    claim="${line%%=*}"
    comps="${line#*=}"
    # Trim the padding the file uses for readability.
    claim="$(printf '%s' "$claim" | sed -e 's/[[:space:]]*$//' -e 's/^[[:space:]]*//')"
    claims=$((claims + 1))

    if [ -z "$(printf '%s' "$comps" | tr -d '[:space:]')" ]; then
        echo "MALFORMED: \"$claim\" lists no components, so it asserts nothing" >&2
        missing=$((missing + 1))
        continue
    fi

    # grep -F: claim texts carry regex metacharacters (H.264, WMA (v1/v2/Pro)).
    if ! printf '%s\n' "$TABLE" | grep -qF -- "$claim"; then
        echo "STALE CLAIM: \"$claim\" is asserted here but not in the README table" >&2
        stale=$((stale + 1))
    fi

    # Quoted: an unquoted expansion would glob a component named with a star
    # against the working directory.
    for spec in ${comps}; do
        kind="${spec%%:*}"
        name="${spec#*:}"
        have "$kind" "$name" || {
            echo "MISSING: \"$claim\" needs $kind $name, which the bundle does not carry" >&2
            missing=$((missing + 1))
        }
    done
done < "$CLAIMS"

# A truncated or emptied claims file would otherwise report success over
# nothing, which is the failure mode this whole script exists to prevent.
MIN_CLAIMS=50
if [ "$claims" -lt "$MIN_CLAIMS" ]; then
    echo "check-readme-formats: only $claims claims parsed, expected at least $MIN_CLAIMS" >&2
    echo "$(basename "$CLAIMS") looks truncated; a near-empty file would pass every other check here." >&2
    exit 1
fi

if [ "$missing" != 0 ] || [ "$stale" != 0 ]; then
    echo "check-readme-formats: $missing missing component(s), $stale stale claim(s)" >&2
    echo "Either the bundle lost something it advertises, or $(basename "$CLAIMS") needs updating." >&2
    exit 1
fi
echo "README format claims verified: $claims claims, every component present"
