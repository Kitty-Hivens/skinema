#!/usr/bin/env bash
# Assert that what README.md advertises is what a bundle actually carries.
#
# Two directions, both of which have failed before:
#   1. A claim names a component no bundle has -- the README advertised
#      mov_text for the whole life of the project while the whitelist asked
#      for a name FFmpeg does not use, so the decoder was never built.
#   2. A claim is edited or dropped in the README while docs/format-claims.txt
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

# The manifest is one line per component kind: "<kind> <name> <name> ...".
have() { # kind, name
    grep "^$1 " "$MANIFEST" | tr ' ' '\n' | tail -n +2 | grep -qx "$2"
}

missing=0
stale=0
claims=0
while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    claim="${line%%=*}"
    comps="${line#*=}"
    # Trim the padding the file uses for readability.
    claim="$(printf '%s' "$claim" | sed -e 's/[[:space:]]*$//' -e 's/^[[:space:]]*//')"
    claims=$((claims + 1))

    # grep -F: claim texts carry regex metacharacters (H.264, WMA (v1/v2/Pro)).
    if ! grep -qF -- "$claim" "$README"; then
        echo "STALE CLAIM: \"$claim\" is asserted here but no longer in $(basename "$README")" >&2
        stale=$((stale + 1))
    fi

    for spec in $comps; do
        kind="${spec%%:*}"
        name="${spec#*:}"
        have "$kind" "$name" || {
            echo "MISSING: \"$claim\" needs $kind $name, which the bundle does not carry" >&2
            missing=$((missing + 1))
        }
    done
done < "$CLAIMS"

if [ "$missing" != 0 ] || [ "$stale" != 0 ]; then
    echo "check-readme-formats: $missing missing component(s), $stale stale claim(s)" >&2
    echo "Either the bundle lost something it advertises, or $(basename "$CLAIMS") needs updating." >&2
    exit 1
fi
echo "README format claims verified: $claims claims, every component present"
