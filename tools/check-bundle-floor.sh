#!/usr/bin/env bash
# One static check on a built Linux or macOS bundle: how old a system it still
# loads on, against what tools/bundle-floors.txt (and the README behind it)
# promises.
#
#   tools/check-bundle-floor.sh <bundle-dir> <platform> <tier> [repo-root]
#
# The floor is measured, never chosen: on Linux it is the highest GLIBC_x.y
# symbol version any shipped library asks for, on macOS the minos every dylib
# was built for. Both move on their own the moment a builder image is bumped,
# and nothing was watching -- so a bundle would quietly stop loading on the
# distributions the README names, and the first report of it would come from
# a user who could not open a file.
#
# Not a load test, deliberately, and it is the half a load test cannot do: CI
# runs on machines newer than the floor, so an ELF that requires GLIBC_2.41
# loads there perfectly and fails only in the hands of someone on Debian 13.
# Reading the versions asked for is the only way to see it from here.
set -eu

bundle="${1:?usage: check-bundle-floor.sh <bundle-dir> <platform> <tier> [repo-root]}"
platform="${2:?platform, e.g. linux-x64}"
tier="${3:?tier: core|decode|full}"
root="${4:-$(cd "$(dirname "$0")/.." && pwd)}"
floors="$root/tools/bundle-floors.txt"

[ -d "$bundle" ] || { echo "check-bundle-floor: no such bundle directory: $bundle" >&2; exit 1; }
[ -f "$floors" ] || { echo "check-bundle-floor: no such declaration: $floors" >&2; exit 1; }

declared="$(sed -n "s/^$platform[[:space:]][[:space:]]*$tier[[:space:]]*=[[:space:]]*//p" "$floors" | head -1)"
[ -n "$declared" ] || {
    echo "check-bundle-floor: $floors declares no floor for $platform $tier" >&2
    exit 1
}
if [ "$declared" = "-" ]; then
    echo "check-bundle-floor: $platform $tier declares no floor (musl does not version its symbols)"
    exit 0
fi

kind="${declared%% *}"
want="${declared##* }"

# Returns 0 when $1 is strictly newer than $2, comparing dotted numbers
# field by field. Neither `sort -V` (a GNU extension the macOS branch cannot
# count on) nor a string compare (which reads 2.9 as newer than 2.10) does.
newer_than() {
    awk -v a="$1" -v b="$2" '
        BEGIN {
            n = split(a, x, "."); m = split(b, y, ".")
            for (i = 1; i <= (n > m ? n : m); i++) {
                p = (i <= n ? x[i] + 0 : 0); q = (i <= m ? y[i] + 0 : 0)
                if (p > q) exit 0
                if (p < q) exit 1
            }
            exit 1
        }'
}

case "$kind" in
glibc)
    command -v readelf >/dev/null 2>&1 || { echo "check-bundle-floor: readelf is required" >&2; exit 1; }
    got=""
    for f in "$bundle"/*.so*; do
        [ -e "$f" ] || continue
        for v in $(readelf -V "$f" 2>/dev/null | grep -oE 'GLIBC_[0-9]+(\.[0-9]+)+' | sed 's/^GLIBC_//' | sort -u); do
            # GLIBC_2.2.5 and friends: real versions, and older than 2.35, so
            # they must take part in the maximum rather than be filtered out.
            if [ -z "$got" ] || newer_than "$v" "$got"; then got="$v"; fi
        done
    done
    [ -n "$got" ] || {
        echo "check-bundle-floor: no versioned glibc symbol found in $bundle -- is it a glibc bundle?" >&2
        exit 1
    }
    ;;
macos)
    command -v otool >/dev/null 2>&1 || { echo "check-bundle-floor: otool is required" >&2; exit 1; }
    got=""
    for f in "$bundle"/*.dylib; do
        [ -e "$f" ] || continue
        # Read the field out of ITS OWN load command and no other. otool -l
        # prints a version in several: LC_SOURCE_VERSION carries the compiler's
        # (2503.1.0 on the runner that caught this), and every LC_LOAD_DYLIB
        # carries the current and compatibility versions of a dependency. A
        # pattern that scans the whole dump for "version" takes the largest of
        # those and calls it the deployment target -- which fails the build for
        # a floor no object declares.
        #
        # LC_BUILD_VERSION carries minos; objects built for older targets carry
        # LC_VERSION_MIN_MACOSX with a "version" field instead, and a bundle can
        # hold both, so both are read and the newest wins.
        for v in $(otool -l "$f" | awk '
                /^ *cmd LC_BUILD_VERSION$/     { in_cmd = "build"; next }
                /^ *cmd LC_VERSION_MIN_MACOSX$/ { in_cmd = "min"; next }
                /^ *cmd /                       { in_cmd = ""; next }
                in_cmd == "build" && $1 == "minos"   { print $2 }
                in_cmd == "min"   && $1 == "version" { print $2 }'); do
            if [ -z "$got" ] || newer_than "$v" "$got"; then got="$v"; fi
        done
    done
    [ -n "$got" ] || {
        echo "check-bundle-floor: no build version found in $bundle -- is it a macOS bundle?" >&2
        exit 1
    }
    ;;
*)
    echo "check-bundle-floor: $floors names an unknown floor kind '$kind' for $platform $tier" >&2
    exit 1
    ;;
esac

if newer_than "$got" "$want"; then
    echo "FLOOR RAISED: $platform $tier needs $kind $got, but the declaration (and the README) promises $want." >&2
    echo "A bundle that asks for more than it promises does not load on the systems named there." >&2
    echo "Either build it against an older base, or lower the promise in README.md and $floors together." >&2
    exit 1
fi
if newer_than "$want" "$got"; then
    echo "check-bundle-floor: $platform $tier needs only $kind $got where $want is promised -- the promise holds, and could be lowered deliberately."
else
    echo "check-bundle-floor: $platform $tier needs $kind $got, exactly what is promised."
fi
