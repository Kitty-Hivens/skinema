#!/usr/bin/env bash
# Two static checks on a built Windows bundle, both about DLLs that load on
# the maintainer's machine and fail on a user's.
#
#   tools/check-windows-bundle.sh <bundle-dir> [repo-root]
#
# 1. IMPORT-CLOSED. Every non-system DLL any bundled DLL imports must itself
#    be in the bundle. A missing MinGW runtime (liblzma-5 once) makes a clean
#    Windows box fail where CI passes on the toolchain's copy.
#
# 2. PRELOADED. Import-closure is necessary but not sufficient: a full-path
#    LoadLibrary does not search the bundle directory for an importer's own
#    dependencies, so a runtime DLL that rides in the bundle but is not mapped
#    BEFORE the library importing it still fails to resolve. So every runtime
#    import of the av* set and libass must appear in the loader's preload
#    lists, which are read out of the Kotlin sources here rather than
#    duplicated -- a list that drifts is exactly the bug this catches
#    (libc++ on windows-arm64).
#
# Run it on any host with llvm-objdump/objdump; it reads the PE headers and
# needs no Windows. In CI it runs on the runner that built the bundle.
set -uo pipefail

BUNDLE="${1:?usage: check-windows-bundle.sh <bundle-dir> [repo-root]}"
ROOT="${2:-$(cd "$(dirname "$0")/.." && pwd)}"
LIBAV_KT="$ROOT/skinema-core/src/main/kotlin/dev/hivens/skinema/libav/Libav.kt"
ASS_KT="$ROOT/skinema-core/src/main/kotlin/dev/hivens/skinema/ass/Ass.kt"

lower() { tr 'A-Z' 'a-z'; }

DUMPER="$(command -v llvm-objdump || command -v objdump || true)"
[ -n "$DUMPER" ] || { echo "no objdump/llvm-objdump on PATH"; exit 1; }

shopt -s nullglob
dlls=("$BUNDLE"/*.dll)
shopt -u nullglob
[ ${#dlls[@]} -gt 0 ] || { echo "no DLLs under $BUNDLE"; exit 1; }

have="$(for f in "${dlls[@]}"; do basename "$f"; done | lower | sort -u)"

# A DLL's imports, minus itself and the Windows apisets.
imports_of() {
    "$DUMPER" -p "$1" 2>/dev/null | sed -n 's/.*DLL Name:[[:space:]]*//p' | lower | sort -u \
        | grep -v "^$(basename "$1" | lower)$" | grep -v '^api-ms-win-'
}

# Every file must actually parse as a PE before either check below means
# anything. Silence used to read as "nothing to check": a file objdump cannot
# read has no imports, so a directory of junk passed both halves and printed
# two success lines. No library in this bundle has an empty import table --
# they all call into the C runtime -- so an empty read is a broken one.
unreadable=0
for f in "${dlls[@]}"; do
    [ -n "$(imports_of "$f")" ] && continue
    echo "UNREADABLE: $(basename "$f") yielded no imports -- $DUMPER cannot read it as a PE"
    unreadable=$((unreadable + 1))
done
if [ "$unreadable" -gt 0 ]; then
    echo "Refusing to verify a bundle with $unreadable unreadable file(s); neither check below would mean anything."
    exit 1
fi

# Windows' own DLLs. Named explicitly rather than probed, so the script gives
# the same answer off Windows (a maintainer's box, a Linux runner) as on it;
# the System32 probe below still accepts anything this list has not met yet,
# but only when running somewhere that has a System32 to look in.
SYSTEM_DLLS='^(kernel32|kernelbase|user32|gdi32|gdi32full|advapi32|msvcrt|ucrtbase|ntdll|ole32|oleaut32|combase|shell32|shlwapi|bcrypt|ncrypt|crypt32|secur32|ws2_32|iphlpapi|version|winmm|psapi|setupapi|cfgmgr32|dwrite|d3d11|d3d9|dxgi|dxva2|mf|mfplat|mfuuid|evr|strmiids|opengl32|imm32|comdlg32|rpcrt4|userenv|powrprof|uxtheme|dbghelp|vcruntime140|vcruntime140_1|msvcp140|concrt140)\.dll$'

is_system() {
    printf '%s\n' "$1" | grep -qE "$SYSTEM_DLLS" && return 0
    [ -f "/c/Windows/System32/$1" ]
}

# -- 1. import-closed --------------------------------------------------------

gaps=0
for f in "${dlls[@]}"; do
    for i in $(imports_of "$f"); do
        printf '%s\n' "$have" | grep -qx "$i" && continue
        is_system "$i" && continue
        echo "MISSING: $(basename "$f") imports $i (not bundled, not a system DLL)"
        gaps=$((gaps + 1))
    done
done
if [ "$gaps" -gt 0 ]; then
    echo "Bundle is NOT self-contained: $gaps unsatisfied import(s) -- a clean Windows box would fail to load it."
    exit 1
fi
echo "Import-closed: every non-system import is present."

# -- 2. preloaded ------------------------------------------------------------

# Libav preloads by exact file name; Ass and Webp name a base and a soname
# major (the Windows spelling is lib<base>-<major>.dll) plus the odd literal.
LOADER_SOURCES=("$LIBAV_KT" "$ASS_KT")
preload="$(
    {
        grep -hoE '"[A-Za-z0-9_+.-]+\.dll"' "${LOADER_SOURCES[@]}" 2>/dev/null | tr -d '"'
        grep -hoE 'lookup\("[a-z0-9]+", *[0-9]+\)' "${LOADER_SOURCES[@]}" 2>/dev/null \
            | sed -E 's/lookup\("([a-z0-9]+)", *([0-9]+)\)/lib\1-\2.dll/'
    } | lower | sort -u
)"
[ -n "$preload" ] || { echo "could not read a preload list from the Kotlin sources"; exit 1; }

# The libraries the loader opens by full path. Everything else in the bundle is
# a dependency that must already be mapped when one of these is loaded.
shopt -s nullglob
loaded=("$BUNDLE"/avutil-*.dll "$BUNDLE"/avcodec-*.dll "$BUNDLE"/avformat-*.dll \
        "$BUNDLE"/avfilter-*.dll "$BUNDLE"/swscale-*.dll "$BUNDLE"/swresample-*.dll \
        "$BUNDLE"/libass-*.dll)
shopt -u nullglob
# A renamed library would empty this array and the loop below would verify
# nothing while printing that the load order is sound.
[ ${#loaded[@]} -gt 0 ] || {
    echo "no av*/libass/libwebp libraries under $BUNDLE -- the loaded set is empty, so load order is unverifiable"
    exit 1
}

unmapped=0
# Built once, and matched with a case rather than a pipeline. `grep -q` closes
# the pipe on its first match, which raises SIGPIPE in the xargs still forking
# behind it; under `pipefail` that pipeline then returns 141, the `&& continue`
# does not run, and a sound bundle is reported as having a broken load order.
# It only bites where xargs is slow enough to still be running -- busybox and
# BSD, i.e. exactly the Alpine and macOS hosts this script advertises support
# for -- so CI never saw it.
loaded_names=""
for f in "${loaded[@]}"; do
    loaded_names="$loaded_names $(basename "$f" | lower)"
done

for f in "${loaded[@]}"; do
    self="$(basename "$f" | lower)"
    for i in $(imports_of "$f"); do
        # Another library the loader opens itself, in dependency order.
        case " $loaded_names " in *" $i "*) continue ;; esac
        printf '%s\n' "$preload" | grep -qx "$i" && continue
        is_system "$i" && continue
        echo "NOT PRELOADED: $self imports $i, which is in the bundle but never mapped before it"
        unmapped=$((unmapped + 1))
    done
done
if [ "$unmapped" -gt 0 ]; then
    echo "Load order is broken: $unmapped import(s) resolve only from a host PATH."
    echo "Add them to the preload list in ${LIBAV_KT#"$ROOT"/} (or Ass.kt for the libass stack)."
    exit 1
fi
echo "Preloaded: every runtime import of the loaded set is mapped first."
