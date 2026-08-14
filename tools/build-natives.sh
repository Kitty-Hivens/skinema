#!/usr/bin/env bash
# Trimmed FFmpeg build for skinema (ROADMAP.md section 4): shared, a
# FEATURES-selected whitelist, no network. Used both locally (against
# system libraries) and in CI (STATIC_DEPS=1 builds the dependencies from
# source as static PIC so the shipped libav* carry no extra runtime
# dependency). FEATURES drives the modular tiers (core/decode/full); a
# bundle stays LGPL until an encoder feature pulls in GPL x264/x265.
#
#   tools/build-natives.sh [prefix]
#
# Env:
#   FFMPEG_VERSION  release to build (default 9.0.1; must stay in the n9.0 pin)
#   STATIC_DEPS=1   shipping mode: libvpx + dav1d from source statically
#                   linked into ffmpeg, plus libwebp/libwebpdemux built as
#                   SHARED libraries for the bundle (the animated-WebP path
#                   binds them directly; FFmpeg cannot decode animations)
#   WEBP_VERSION    libwebp release for STATIC_DEPS (default 1.5.0)
#   ZLIB_VERSION    zlib release for STATIC_DEPS (default 1.3.1)
#   BZIP2_VERSION   bzip2 release for STATIC_DEPS (default 1.0.8)
#   XZ_VERSION      xz/liblzma release for STATIC_DEPS (default 5.6.4)
#   VPX_VERSION     libvpx tag for STATIC_DEPS (default v1.15.2)
#   VPX_TARGET      libvpx configure --target (needed under MSYS2: x86_64-win64-gcc)
#   DAV1D_VERSION   dav1d tag for STATIC_DEPS (default 1.5.1)
#   MAC_CROSS_X64=1 cross-compile x86_64 binaries on an arm64 mac (GitHub's
#                   Intel runners are scarce-to-dead; Apple's toolchain
#                   cross-builds natively via -arch)
#   TIER            core|decode|full -- names the row of tools/bundle-surface.txt
#                   the built bundle must match. Unset (a custom FEATURES set)
#                   reports the surface instead of asserting it.
#   EXTRA_FLAGS     appended to ffmpeg ./configure (cross builds etc.)
#   JOBS            parallel make (default nproc)
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-9.0.1}"
WEBP_VERSION="${WEBP_VERSION:-1.5.0}"
VPX_VERSION="${VPX_VERSION:-v1.15.2}"
DAV1D_VERSION="${DAV1D_VERSION:-1.5.1}"
X264_VERSION="${X264_VERSION:-stable}"
X265_VERSION="${X265_VERSION:-4.1}"
FREETYPE_VERSION="${FREETYPE_VERSION:-2.13.3}"
HARFBUZZ_VERSION="${HARFBUZZ_VERSION:-10.1.0}"
FRIBIDI_VERSION="${FRIBIDI_VERSION:-1.0.16}"
LIBASS_VERSION="${LIBASS_VERSION:-0.17.4}"
ZLIB_VERSION="${ZLIB_VERSION:-1.3.1}"
BZIP2_VERSION="${BZIP2_VERSION:-1.0.8}"
XZ_VERSION="${XZ_VERSION:-5.6.4}"
JOBS="${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu)}"

# Which optional capabilities this bundle carries (modular tiers, ROADMAP
# section 4). Comma- or space-separated; default is the complete LGPL decode
# set. Both the dependency builds above and the ffmpeg whitelist below gate
# on these, so an absent feature ships neither its library nor its codecs.
#   core    av1 vpx webp                                                  (LGPL, no subtitles, no hwaccel)
#   decode  av1 vpx webp hwaccel subs formats enc-vaapi                   (LGPL, decode + GPU encode on Linux)
#   full    av1 vpx webp hwaccel subs formats enc-vaapi enc-h264 enc-hevc (GPL, + software encode)
# enc-vaapi (M13) enables the LGPL hardware H.264/HEVC encoders on Linux; it
# adds no GPL surface, so it rides the decode tier as well as full.
# "formats" is the broad legacy/extended decode set -- avi/mpegts/mpeg/flv/asf/
# dv containers; mpeg2/vc1/wmv/mpeg4/h263/vvc/realvideo/prores/... video; dts/
# truehd/wma/mp2/realaudio/adpcm/... audio. All native (no external library),
# so it stays LGPL; it rides decode/full and is left out of the lean core tier.
# hwaccel is out of core deliberately: vaapi is what puts libva, libva-drm and
# libdrm on the consumer's machine, and core exists to be the tier that needs
# nothing but libc and libm -- the one that loads in a container and on a
# store-based distribution. The desktop tiers keep it.
# ${FEATURES-...}, not ${FEATURES:-...}: an explicitly EMPTY set is a
# legitimate request (the leanest tier), and the colon form silently
# replaced it with the full default.
# Resolved before the cd into $WORK below, or every later reference to a
# file next to this script resolves against the wrong directory.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TIER="${TIER:-}"
# What each tier contains, kept here rather than only in the workflow that
# builds it. TIER also selects the row of bundle-surface.txt the result is
# asserted against, so a TIER whose FEATURES came from somewhere else checks
# the bundle against a declaration describing a different bundle -- silently,
# and in the direction that passes.
tier_features() {
    case "$1" in
        core)   echo "av1 vpx webp" ;;
        decode) echo "av1 vpx webp hwaccel subs formats enc-vaapi" ;;
        full)   echo "av1 vpx webp hwaccel subs formats enc-vaapi enc-h264 enc-hevc" ;;
        *)      echo "build-natives: unknown TIER '$1' (core|decode|full)" >&2; exit 1 ;;
    esac
}
if [ -n "$TIER" ]; then
    TIER_FEATURES="$(tier_features "$TIER")"
    if [ -n "${FEATURES+set}" ] && [ "${FEATURES//,/ }" != "$TIER_FEATURES" ]; then
        echo "build-natives: FEATURES does not match TIER=$TIER" >&2
        echo "  given: ${FEATURES//,/ }" >&2
        echo "  $TIER: $TIER_FEATURES" >&2
        exit 1
    fi
    FEATURES="$TIER_FEATURES"
fi
FEATURES="${FEATURES-av1 vpx webp hwaccel subs formats}"
FEATURES="${FEATURES//,/ }"
has() { case " $FEATURES " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
for _f in $FEATURES; do
    case "$_f" in
        av1|vpx|webp|subs|formats|hwaccel|enc-h264|enc-hevc|enc-vaapi) ;;
        *) echo "build-natives: unknown FEATURE '$_f'" >&2; exit 1 ;;
    esac
done

# Host OS family and arch. MSYS2 ships several Windows environments -- MINGW64
# (x86_64, GCC) and CLANGARM64 (aarch64, clang) among them -- so collapse every
# one to "windows" here and have the per-OS branches below switch on HOST_OS
# rather than each repeating the MSYSTEM names ("$(uname -s)" alone is not a
# reliable discriminator across them). MSYSTEM is set by MSYS2 and names the
# environment; off MSYS2 it is empty and uname decides. HOST_ARCH splits the
# x86_64 Windows toolchain (GCC, static libstdc++ folded in) from the aarch64
# one (clang, libc++/libunwind shipped as DLLs).
case "${MSYSTEM:-$(uname -s)}" in
    MINGW*|CLANG*|UCRT*|MSYS*) HOST_OS=windows ;;
    Darwin)                    HOST_OS=mac ;;
    *)                         HOST_OS=linux ;;
esac
case "${MSYSTEM:-}|$(uname -m)" in
    CLANGARM64*|*aarch64*|*arm64*) HOST_ARCH=arm64 ;;
    *)                             HOST_ARCH=x64 ;;
esac
[ "${MAC_CROSS_X64:-}" = "1" ] && HOST_ARCH=x64

mkdir -p "${1:-natives-out}"
PREFIX="$(cd "${1:-natives-out}" && pwd)"
WORK="${WORK:-/tmp/skinema-natives}"

# The licence set is per tier, so it is rebuilt from empty every run. Kept,
# it accumulates: a core build into a prefix that last held full would carry
# COPYING.GPLv2 and the x264/x265 texts into an LGPL bundle, claiming terms
# that do not apply to it.
rm -rf "$PREFIX/licenses"
mkdir -p "$WORK" "$PREFIX/licenses"
# Absolute from here on: the script cds into subdirectories of it, so a
# relative WORK stops naming the same place partway through the build.
WORK="$(cd "$WORK" && pwd)"
cd "$WORK"

# Copy a licence text into the bundle's set. Called OUTSIDE each dependency's
# build guard on purpose: the guard keys off $WORK (warm across runs) while
# the texts land in $PREFIX (fresh per output directory), and gating the copy
# on a rebuild drops every text the moment those two lifetimes diverge. A warm
# $WORK always still holds the unpacked source, so the file is there to copy;
# if it is not, that is a broken tree and worth saying so rather than shipping
# a binary with no licence beside it.
license() {
    [ -f "$1" ] || { echo "build-natives: licence text missing: $1" >&2; exit 1; }
    cp "$1" "$PREFIX/licenses/$2"
}

MESON_CROSS=()
FFMPEG_CROSS=()
if [ "${MAC_CROSS_X64:-}" = "1" ]; then
    cat > "$WORK/mac-x64-cross.ini" <<'EOF'
[binaries]
c = ['clang', '-arch', 'x86_64']
cpp = ['clang++', '-arch', 'x86_64']
ar = 'ar'
strip = 'strip'
pkg-config = 'pkg-config'

[host_machine]
system = 'darwin'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
EOF
    MESON_CROSS=(--cross-file "$WORK/mac-x64-cross.ini")
    VPX_TARGET="${VPX_TARGET:-x86_64-darwin20-gcc}"
    export CC="clang -arch x86_64"
    FFMPEG_CROSS=(--enable-cross-compile --arch=x86_64 --target-os=darwin --cc="clang -arch x86_64")
fi

# The sha256 every archive must have. Without this a mirror is a liability
# rather than insurance: a second source that serves different bytes builds
# something else in silence, and a half-written or truncated file sits in the
# cache forever because the download is skipped once the file exists.
#
# Two values where the sources genuinely differ: ffmpeg.org publishes a .tar.xz
# and the GitHub mirror generates a .tar.gz of the same tree. Listing both
# keeps the check fail-closed -- if GitHub ever regenerates its archive the
# build stops and says so, rather than accepting whatever arrives.
#
# x264 has no entry: X264_VERSION names a branch, so upstream moves the
# tarball under it and there is nothing stable to pin. That also means x264
# builds are not reproducible; pinning it to a commit is a separate decision.
sha_for() { # dest-file -> accepted sha256 values, empty when unpinnable
    case "$1" in
        zlib.tar.gz)         echo 9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23 ;;
        bzip2.tar.gz)        echo ab5a03176ee106d3f0fa90e381da478ddae405918153cca248e682cd0c4a2269 ;;
        xz.tar.gz)           echo 269e3f2e512cbd3314849982014dc199a7b2148cf5c91cedc6db629acdf5e09b ;;
        dav1d.tar.gz)        echo fa635e2bdb25147b1384007c83e15de44c589582bb3b9a53fc1579cb9d74b695 ;;
        libwebp-dist.tar.gz) echo 7d6fab70cf844bf6769077bd5d7a74893f8ffd4dfb42861745750c63c2a5c92c ;;
        libvpx.tar.gz)       echo 26fcd3db88045dee380e581862a6ef106f49b74b6396ee95c2993a260b4636aa ;;
        x265.tar.gz)         echo a31699c6a89806b74b0151e5e6a7df65de4b49050482fe5ebf8a4379d7af8f29 ;;
        freetype.tar.xz)     echo 0550350666d427c74daeb85d5ac7bb353acba5f76956395995311a9c6f063289 ;;
        fribidi.tar.xz)      echo 1b1cde5b235d40479e91be2f0e88a309e3214c8ab470ec8a2744d82a5a9ea05c ;;
        harfbuzz.tar.xz)     echo 6ce3520f2d089a33cef0fc48321334b8e0b72141f6a763719aaaecd2779ecb82 ;;
        libass.tar.xz)       echo 78f1179b838d025e9c26e8fef33f8092f65611444ffa1bfc0cfac6a33511a05a ;;
        ffmpeg.tar)          echo cf38e0e28c7e5605942c4a77755349b0145804a397af37eb1fb4c77cb237f635 \
                                  195d54bebe1a27f84d77f4b989d193466f305b355da92292766a69f16880b18a ;;
        # "-" is the deliberate opt-out, spelled so it cannot be reached by
        # accident. Falling through to it silently would mean a new dependency,
        # or a typo in a dest name, quietly downloads unverified.
        x264.tar.gz)         echo "-" ;;
        # A sentinel, not an exit: every caller runs this inside a command
        # substitution, where exiting kills only that substitution and the
        # script carries on downloading unverified.
        *) echo "?" ;;
    esac
}

# macOS has shasum, everything else has sha256sum; neither is on both.
sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# Whether a file on disk matches its pin. Quiet -- callers say what they are
# doing with the answer, since a mismatch means something different for a
# cached file than for one that just came off a mirror.
sha_ok() {
    local want got
    want="$(sha_for "$(basename "$1")")"
    [ "$want" = "-" ] && return 0
    got="$(sha256_of "$1")"
    case " $want " in *" $got "*) return 0 ;; esac
    return 1
}

fetch() { # dest-file, url...
    # Retries AND alternates. One flaky mirror must not take down a matrix of
    # 24 builds, and it does: savannah went fully unreachable mid-run and took
    # every subtitle-carrying tier with it, having already answered 502 during
    # this script's development, and zlib.net dropped TLS mid-handshake before
    # that. --retry-all-errors extends retrying to HTTP 5xx, which curl does
    # not treat as retryable by default; the extra urls are tried in turn when
    # a host is down rather than merely slow.
    local dest="$1"; shift
    # Top level, where exiting actually stops the build: an unknown dest name
    # is a new dependency someone forgot to pin, or a typo, and either way it
    # must not download on trust.
    if [ "$(sha_for "$(basename "$dest")")" = "?" ]; then
        echo "build-natives: no sha256 entry for $(basename "$dest") -- add one to sha_for" >&2
        exit 1
    fi
    # A cached archive is verified too, not trusted for being present. $WORK is
    # warm across runs by design, so without this the pin protects only the
    # first download: anything already sitting there -- a truncated file from
    # an interrupted run, or something another user dropped in a shared /tmp --
    # would be used forever without ever being hashed.
    if [ -f "$dest" ]; then
        if sha_ok "$dest"; then
            return 0
        fi
        echo "build-natives: discarding cached $(basename "$dest"), it does not match its pin" >&2
        rm -f "$dest"
    fi
    local url
    for url in "$@"; do
        # Patient rather than quick: a run died with five resets inside
        # seventeen seconds, which the old budget could not outlast. Six
        # attempts five seconds apart rides out a reset that clears in half a
        # minute, and costs nothing when the first attempt works.
        if curl -fsSL --retry 6 --retry-delay 5 --retry-all-errors \
                --connect-timeout 20 -o "$dest" "$url"; then
            # A mirror that answers 200 with an error page or a captive-portal
            # redirect passes curl and then sits in the cache forever, because
            # the -f check above never downloads twice. Reject anything that is
            # not a readable archive so the next mirror gets its turn.
            if ! tar -tf "$dest" >/dev/null 2>&1; then
                echo "build-natives: $url answered with something that is not an archive" >&2
            else
                if [ "$(sha_for "$(basename "$dest")")" = "-" ]; then
                    echo "build-natives: $(basename "$dest") is not hash-pinned, taking $url on trust" >&2
                    return 0
                elif sha_ok "$dest"; then
                    return 0
                else
                    echo "build-natives: $url served unexpected bytes for $(basename "$dest")" >&2
                    echo "  got:      $(sha256_of "$dest")" >&2
                    echo "  expected: $(sha_for "$(basename "$dest")")" >&2
                    # The pins are per file name, not per version, so overriding
                    # a *_VERSION knob lands here too. That is not a compromised
                    # mirror, it is a pin that needs updating -- say so, because
                    # the two look identical from here.
                    echo "  (if you overrode a *_VERSION, update sha_for to match)" >&2
                fi
            fi
        else
            echo "build-natives: mirror failed, trying the next: $url" >&2
        fi
        rm -f "$dest"
    done
    echo "build-natives: every mirror failed for $dest" >&2
    return 1
}

if [ "${STATIC_DEPS:-}" = "1" ]; then
    DEPS="$WORK/deps"
    export PKG_CONFIG_PATH="$DEPS/lib/pkgconfig:$DEPS/lib64/pkgconfig:${PKG_CONFIG_PATH:-}"

    # zlib and bzip2 are the two host libraries ffmpeg picks up by autodetect,
    # and they are why the shipped Linux bundle needs a host it cannot name:
    # avcodec pulls libz (the png/apng decoders need it, so --disable-zlib is
    # not an option) and avformat pulls libbz2. On a store-based distribution
    # neither sits where the loader looks, and the whole bundle fails to open.
    # Built static here like dav1d and libvpx, they fold in and stop being the
    # consumer's problem. zlib ships a .pc; bzip2 does not, hence the explicit
    # -I/-L below.
    if [ ! -f "$DEPS/lib/libz.a" ]; then
        fetch zlib.tar.gz "https://github.com/madler/zlib/releases/download/v$ZLIB_VERSION/zlib-$ZLIB_VERSION.tar.gz" \
            "https://zlib.net/fossils/zlib-$ZLIB_VERSION.tar.gz"
        rm -rf "zlib-$ZLIB_VERSION"
        tar -xzf zlib.tar.gz
        (
            cd "zlib-$ZLIB_VERSION"
            # -O3 explicitly: zlib's configure defaults it via ${CFLAGS--O3},
            # which any externally set CFLAGS suppresses -- so passing -fPIC
            # alone compiles inflate at -O0 into every shipped avcodec.
            CFLAGS="-O3 -fPIC ${CFLAGS:-}" ./configure --prefix="$DEPS" --static
            make -j"$JOBS"
            make install
        ) || exit 1
    fi
    license "zlib-$ZLIB_VERSION/LICENSE" "zlib-LICENSE"

    if [ ! -f "$DEPS/lib/libbz2.a" ]; then
        fetch bzip2.tar.gz "https://sourceware.org/pub/bzip2/bzip2-$BZIP2_VERSION.tar.gz"
        rm -rf "bzip2-$BZIP2_VERSION"
        tar -xzf bzip2.tar.gz
        (
            cd "bzip2-$BZIP2_VERSION"
            # No configure; the makefile takes CC/CFLAGS and PREFIX directly.
            make -j"$JOBS" libbz2.a CC="${CC:-cc}" CFLAGS="-fPIC -O2 -D_FILE_OFFSET_BITS=64"
            mkdir -p "$DEPS/lib" "$DEPS/include"
            cp libbz2.a "$DEPS/lib/"
            cp bzlib.h "$DEPS/include/"
        ) || exit 1
    fi
    license "bzip2-$BZIP2_VERSION/LICENSE" "bzip2-LICENSE"

    # liblzma joins zlib and bzip2 for the same reason: matroska reads
    # LZMA-compressed headers through it, and left to autodetect it becomes a
    # host dependency -- measured, liblzma.so.5 appeared in the decode tier
    # the moment --enable-lzma went in without this.
    if [ ! -f "$DEPS/lib/liblzma.a" ]; then
        fetch xz.tar.gz "https://github.com/tukaani-project/xz/releases/download/v$XZ_VERSION/xz-$XZ_VERSION.tar.gz" \
            "https://tukaani.org/xz/xz-$XZ_VERSION.tar.gz"
        rm -rf "xz-$XZ_VERSION"
        tar -xzf xz.tar.gz
        (
            cd "xz-$XZ_VERSION"
            # --disable-nls: on native Windows xz's translation support wants
            # UCRT and a gettext newer than MSYS2 carries, and a build of
            # liblzma has no use for localised messages anywhere.
            ./configure --prefix="$DEPS" --disable-shared --enable-static --with-pic \
                --disable-nls \
                --disable-xz --disable-xzdec --disable-lzmadec --disable-lzmainfo \
                --disable-lzma-links --disable-scripts --disable-doc \
                ${MAC_CROSS_X64:+--host=x86_64-apple-darwin}
            make -j"$JOBS"
            make install
        ) || exit 1
    fi
    license "xz-$XZ_VERSION/COPYING" "xz-COPYING"

    if has av1 && [ ! -f "$DEPS/lib/libdav1d.a" ]; then
        fetch dav1d.tar.gz "https://code.videolan.org/videolan/dav1d/-/archive/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.gz"
        rm -rf "dav1d-$DAV1D_VERSION"
        tar -xzf dav1d.tar.gz
        meson setup "dav1d-$DAV1D_VERSION/build" "dav1d-$DAV1D_VERSION" \
            --prefix="$DEPS" --libdir=lib --default-library=static --buildtype=release \
            -Denable_tools=false -Denable_tests=false ${MESON_CROSS[@]+"${MESON_CROSS[@]}"}
        ninja -C "dav1d-$DAV1D_VERSION/build" install
    fi
    if has av1; then license "dav1d-$DAV1D_VERSION/COPYING" "dav1d-COPYING"; fi

    # libwebp ships SHARED into the bundle prefix (the webp bindings load it at
    # runtime; it is not linked into ffmpeg). Autotools elsewhere -- libtool
    # produces the soname naming the loader expects (libwebp.so.7 /
    # libwebp.7.dylib / libwebp-7.dll). On CLANGARM64 that libtool cannot fold
    # the static sharpyuv convenience lib into a DLL and emits no libwebp DLL at
    # all, so build with cmake there (as MSYS2 does); CMAKE_DLL_NAME_WITH_SOVERSION
    # reproduces the same -<major> DLL names.
    if has webp && ! ls "$PREFIX"/lib/libwebp.* >/dev/null 2>&1 && ! ls "$PREFIX"/bin/libwebp-*.dll >/dev/null 2>&1; then
        fetch libwebp-dist.tar.gz "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-$WEBP_VERSION.tar.gz"
        rm -rf "libwebp-$WEBP_VERSION"
        tar -xzf libwebp-dist.tar.gz
        if [ "$HOST_OS" = windows ] && [ "$HOST_ARCH" = arm64 ]; then
            cmake -G Ninja -S "libwebp-$WEBP_VERSION" -B "libwebp-$WEBP_VERSION/build" \
                -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_BUILD_TYPE=Release \
                -DBUILD_SHARED_LIBS=ON -DCMAKE_DLL_NAME_WITH_SOVERSION=ON \
                -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF -DWEBP_BUILD_GIF2WEBP=OFF \
                -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF -DWEBP_BUILD_WEBPINFO=OFF \
                -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_EXTRAS=OFF
            ninja -C "libwebp-$WEBP_VERSION/build" install
            # cmake also emits decoder-only and mux libraries; the bindings use
            # neither (libwebp covers decode, libwebpdemux drives animation), and
            # the autotools build on other platforms ships neither -- drop them.
            rm -f "$PREFIX"/bin/libwebpdecoder-*.dll "$PREFIX"/bin/libwebpmux-*.dll
        else
            (
                cd "libwebp-$WEBP_VERSION"
                ./configure --prefix="$PREFIX" --enable-shared --disable-static \
                    --enable-libwebpdemux --disable-libwebpmux \
                    ${MAC_CROSS_X64:+--host=x86_64-apple-darwin}
                make -j"$JOBS"
                make install
            ) || exit 1
        fi
    fi
    if has webp; then license "libwebp-$WEBP_VERSION/COPYING" "libwebp-COPYING"; fi

    if has vpx && [ ! -f "$DEPS/lib/libvpx.a" ]; then
        fetch libvpx.tar.gz "https://github.com/webmproject/libvpx/archive/refs/tags/$VPX_VERSION.tar.gz"
        rm -rf "libvpx-${VPX_VERSION#v}"
        tar -xzf libvpx.tar.gz
        (
            cd "libvpx-${VPX_VERSION#v}"
            # CLANGARM64 has no gcc, and libvpx will not auto-detect a win-arm64
            # target (it falls to generic-gnu, which invokes gcc and dies). Name
            # the arm64 target explicitly (this turns NEON on) and hand it the
            # llvm toolchain -- clang to compile AND link, llvm-ar/nm/ranlib/
            # strip -- the way MSYS2's environment does for its own libvpx, since
            # the -gcc target otherwise reaches for gcc/binutils that are absent.
            if [ "$HOST_OS" = windows ] && [ "$HOST_ARCH" = arm64 ]; then
                export CC=clang CXX=clang++ LD=clang AR=llvm-ar NM=llvm-nm RANLIB=llvm-ranlib STRIP=llvm-strip
                : "${VPX_TARGET:=arm64-win64-gcc}"
            fi
            # Decode-only: skinema reads vp8/vp9 through ffmpeg's libvpx decoders
            # and never encodes them. Smaller, and it drops libvpx's only C++ (the
            # encoder's ratectrl_rtc), which on CLANGARM64 would otherwise reach
            # for a g++ that the clang toolchain does not ship.
            ./configure --prefix="$DEPS" --disable-examples --disable-tools \
                --disable-docs --disable-unit-tests --enable-pic --enable-vp8 \
                --enable-vp9 --disable-vp8-encoder --disable-vp9-encoder \
                --disable-shared --enable-static \
                ${VPX_TARGET:+--target=$VPX_TARGET} \
                || { echo "=== libvpx config.log tail ==="; tail -40 config.log 2>/dev/null; exit 1; }
            make -j"$JOBS"
            make install
        ) || exit 1
    fi
    if has vpx; then license "libvpx-${VPX_VERSION#v}/LICENSE" "libvpx-LICENSE"; fi

    # x264 (H.264 encoder, GPL -- M12 encode bundle). Static + PIC, folded
    # into FFmpeg like dav1d/libvpx so the shipped library carries no extra
    # runtime dependency. Autotools + nasm (already in CI), no cmake -- the
    # cmake encoders (x265, SVT-AV1) and libopus arrive in later rounds.
    if has enc-h264 && [ ! -f "$DEPS/lib/libx264.a" ]; then
        fetch x264.tar.gz "https://code.videolan.org/videolan/x264/-/archive/$X264_VERSION/x264-$X264_VERSION.tar.gz"
        rm -rf "x264-$X264_VERSION"
        tar -xzf x264.tar.gz
        (
            cd "x264-$X264_VERSION"
            # CLANGARM64 has no gcc; x264's configure defaults CC to gcc and
            # bails ("no working C compiler found"). Hand it clang and the llvm
            # binutils, as for libvpx above.
            if [ "$HOST_OS" = windows ] && [ "$HOST_ARCH" = arm64 ]; then
                export CC=clang AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip
            fi
            ./configure --prefix="$DEPS" --enable-static --enable-pic \
                --disable-cli --disable-opencl \
                ${MAC_CROSS_X64:+--host=x86_64-apple-darwin}
            make -j"$JOBS"
            make install
        ) || exit 1
    fi
    if has enc-h264; then license "x264-$X264_VERSION/COPYING" "x264-COPYING"; fi

    # x265 (HEVC encoder, GPL). cmake + nasm; static 8-bit (10/12-bit HDR
    # multilib skipped -- 8-bit is the common case), PIC, folded in. x265 is
    # C++, so on Windows the ffmpeg link folds the C++ runtime in (see FFLD
    # at the configure below); Linux/macOS take the system C++ runtime.
    if has enc-hevc && [ ! -f "$DEPS/lib/libx265.a" ]; then
        fetch x265.tar.gz "https://download.videolan.org/pub/videolan/x265/x265_$X265_VERSION.tar.gz" \
            "https://bitbucket.org/multicoreware/x265_git/downloads/x265_$X265_VERSION.tar.gz"
        rm -rf "x265_$X265_VERSION"
        tar -xzf x265.tar.gz
        # x265 4.1 predates cmake 4.x: it sets pre-3.5 policies to OLD (which
        # cmake 4.x refuses) and detects Apple's clang via STREQUAL "Clang"
        # (which the CMP0025 OLD reporting relied on). Patch the source so
        # cmake 4.x (brew on macOS, MSYS2 on Windows) configures it -- which
        # lets full ship HEVC encode there (issue #22). Harmless on Linux's
        # cmake 3.28 (NEW is the default, GNU never matches the clang branch).
        #
        # ENABLE_LIBNUMA=OFF because x265 links libnuma when it finds it, and
        # libnuma is a separate package a desktop need not have -- so the
        # bundle's dependencies would follow the build host again, one level
        # below ffmpeg's own autodetect. NUMA-aware thread placement is a
        # multi-socket server concern; this encodes video on a desktop.
        sed -i.bak \
            -e 's/cmake_policy(SET CMP0025 OLD)/cmake_policy(SET CMP0025 NEW)/' \
            -e 's/cmake_policy(SET CMP0054 OLD)/cmake_policy(SET CMP0054 NEW)/' \
            -e 's/cmake_minimum_required (VERSION 2.8.8)/cmake_minimum_required (VERSION 3.5)/' \
            -e 's/${CMAKE_CXX_COMPILER_ID} STREQUAL "Clang"/${CMAKE_CXX_COMPILER_ID} MATCHES "Clang"/' \
            "x265_$X265_VERSION/source/CMakeLists.txt"
        cmake -G Ninja -S "x265_$X265_VERSION/source" -B "x265_$X265_VERSION/build" \
            -DCMAKE_INSTALL_PREFIX="$DEPS" -DCMAKE_BUILD_TYPE=Release \
            -DENABLE_SHARED=OFF -DENABLE_CLI=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
            -DENABLE_LIBNUMA=OFF \
            ${MAC_CROSS_X64:+-DCMAKE_OSX_ARCHITECTURES=x86_64}
        ninja -C "x265_$X265_VERSION/build" install
    fi
    if has enc-hevc; then license "x265_$X265_VERSION/COPYING" "x265-COPYING"; fi
fi

if [ "${STATIC_DEPS:-}" = "1" ] && has subs; then
    # --- The libass stack (text subtitles). libass ships SHARED in the
    # bundle; fribidi is LGPL-2.1 and ships as its OWN shared library
    # (the libwebp precedent). freetype (FTL) and harfbuzz (MIT) fold in
    # STATICALLY on Linux/macOS -- but MinGW libtool will not put a
    # static archive into a DLL, so on Windows they too ship as shared
    # DLLs (installed into the bundle prefix, preloaded by the loader),
    # each linking the C++/GCC runtime in so it stays self-contained.
    # Their licenses already travel with every bundle (FTL + harfbuzz
    # COPYING below), so shipping the DLLs adds no licensing surface.
    if [ "$HOST_OS" = windows ]; then WINASS=1; else WINASS=; fi
    if [ -n "$WINASS" ]; then
        FT_PREFIX="$PREFIX"; FT_KIND="--enable-shared --disable-static"
        HB_PREFIX="$PREFIX"; HB_KIND="shared"; HB_PKG="$DEPS/lib/pkgconfig:$PREFIX/lib/pkgconfig"
        # GCC/MinGW x64 folds its C++/GCC runtime into the DLLs; aarch64 clang
        # ships libc++/libunwind as DLLs instead, so it adds no fold flags here.
        RT_LDFLAGS=""
        [ "$HOST_ARCH" = x64 ] && RT_LDFLAGS="-static-libgcc -static-libstdc++"
    else
        FT_PREFIX="$DEPS"; FT_KIND="--disable-shared --enable-static"
        HB_PREFIX="$DEPS"; HB_KIND="static"; HB_PKG="$DEPS/lib/pkgconfig"
        RT_LDFLAGS=""
    fi

    if [ ! -f "$FT_PREFIX/lib/libfreetype.a" ] && ! ls "$FT_PREFIX"/bin/libfreetype-*.dll >/dev/null 2>&1; then
        fetch freetype.tar.xz \
            "https://download.savannah.gnu.org/releases/freetype/freetype-$FREETYPE_VERSION.tar.xz" \
            "https://downloads.sourceforge.net/project/freetype/freetype2/$FREETYPE_VERSION/freetype-$FREETYPE_VERSION.tar.xz"
        rm -rf "freetype-$FREETYPE_VERSION"
        tar -xJf freetype.tar.xz
        (
            cd "freetype-$FREETYPE_VERSION"
            # CLANGARM64: the tarball's libtool cannot build a DLL for the
            # aarch64-mingw host -- it silently falls back to a static archive,
            # leaving no import library for harfbuzz/libass to link. Regenerate
            # the build with the system libtool, which does support it.
            if [ "$HOST_OS" = windows ] && [ "$HOST_ARCH" = arm64 ]; then sh autogen.sh; fi
            # No harfbuzz refinement loop (an auto-hinter nicety) and no
            # optional codecs: glyphs for libass need none of them.
            ./configure --prefix="$FT_PREFIX" $FT_KIND --with-pic \
                --with-harfbuzz=no --with-brotli=no --with-bzip2=no --with-png=no --with-zlib=no \
                ${MAC_CROSS_X64:+--host=x86_64-apple-darwin}
            make -j"$JOBS"
            make install
        ) || exit 1
    fi
    license "freetype-$FREETYPE_VERSION/docs/FTL.TXT" "freetype-FTL.TXT"

    if ! ls "$PREFIX"/lib/libfribidi.* >/dev/null 2>&1 && ! ls "$PREFIX"/bin/libfribidi-*.dll >/dev/null 2>&1; then
        fetch fribidi.tar.xz "https://github.com/fribidi/fribidi/releases/download/v$FRIBIDI_VERSION/fribidi-$FRIBIDI_VERSION.tar.xz"
        rm -rf "fribidi-$FRIBIDI_VERSION"
        tar -xJf fribidi.tar.xz
        (
            cd "fribidi-$FRIBIDI_VERSION"
            # CLANGARM64 libtool builds no aarch64-mingw DLL; regenerate with the
            # system libtool first (as for freetype above).
            if [ "$HOST_OS" = windows ] && [ "$HOST_ARCH" = arm64 ]; then autoreconf -fi; fi
            ./configure --prefix="$PREFIX" --enable-shared --disable-static \
                ${MAC_CROSS_X64:+--host=x86_64-apple-darwin}
            make -j"$JOBS"
            make install
        ) || exit 1
    fi
    license "fribidi-$FRIBIDI_VERSION/COPYING" "fribidi-COPYING"

    if [ ! -f "$HB_PREFIX/lib/libharfbuzz.a" ] && ! ls "$HB_PREFIX"/bin/libharfbuzz-*.dll >/dev/null 2>&1; then
        fetch harfbuzz.tar.xz "https://github.com/harfbuzz/harfbuzz/releases/download/$HARFBUZZ_VERSION/harfbuzz-$HARFBUZZ_VERSION.tar.xz"
        rm -rf "harfbuzz-$HARFBUZZ_VERSION"
        tar -xJf harfbuzz.tar.xz
        if [ "$HOST_OS" = windows ] && [ "$HOST_ARCH" = arm64 ]; then
            # meson always builds libharfbuzz-subset, which will not link as a
            # separate DLL under clang/lld on aarch64-mingw (undefined main-lib
            # symbols) and which skinema never uses (libass shapes, never
            # subsets). cmake omits it (HB_BUILD_SUBSET=OFF). harfbuzz's cmake
            # sets no SOVERSION, so the DLL is libharfbuzz.dll -- no -<major>
            # suffix, unlike libwebp -- and the Ass loader preloads it by that
            # bare name. Point cmake at the freetype built above, and replace
            # the pkg-config file cmake emits with a minimal one whose
            # -lharfbuzz matches the libharfbuzz.dll.a import lib libass links.
            mkdir -p "$HB_PREFIX/lib/pkgconfig"
            cmake -G Ninja -S "harfbuzz-$HARFBUZZ_VERSION" -B "harfbuzz-$HARFBUZZ_VERSION/build" \
                -DCMAKE_INSTALL_PREFIX="$HB_PREFIX" -DCMAKE_BUILD_TYPE=Release \
                -DCMAKE_PREFIX_PATH="$PREFIX" -DBUILD_SHARED_LIBS=ON \
                -DHB_BUILD_SUBSET=OFF -DHB_BUILD_UTILS=OFF -DHB_HAVE_FREETYPE=ON \
                -DHB_HAVE_GLIB=OFF -DHB_HAVE_GOBJECT=OFF -DHB_HAVE_ICU=OFF
            ninja -C "harfbuzz-$HARFBUZZ_VERSION/build" install
            printf '%s\n' \
                "prefix=$HB_PREFIX" 'exec_prefix=${prefix}' 'libdir=${prefix}/lib' \
                'includedir=${prefix}/include' 'Name: harfbuzz' \
                'Description: HarfBuzz text shaping library' "Version: $HARFBUZZ_VERSION" \
                'Libs: -L${libdir} -lharfbuzz' 'Cflags: -I${includedir}/harfbuzz' \
                > "$HB_PREFIX/lib/pkgconfig/harfbuzz.pc"
        else
            # RT_LDFLAGS folds the C++/GCC runtime into the Windows DLL so it
            # needs no libstdc++-6.dll/libgcc_s alongside; empty elsewhere.
            LDFLAGS="$RT_LDFLAGS ${LDFLAGS:-}" \
            meson setup "harfbuzz-$HARFBUZZ_VERSION/build" "harfbuzz-$HARFBUZZ_VERSION" \
                --prefix="$HB_PREFIX" --libdir=lib --default-library="$HB_KIND" --buildtype=release \
                --pkg-config-path="$HB_PKG" \
                -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled \
                -Dicu=disabled -Dchafa=disabled -Dtests=disabled -Ddocs=disabled \
                ${MESON_CROSS[@]+"${MESON_CROSS[@]}"}
            ninja -C "harfbuzz-$HARFBUZZ_VERSION/build" install
        fi
    fi
    license "harfbuzz-$HARFBUZZ_VERSION/COPYING" "harfbuzz-COPYING"

    if ! ls "$PREFIX"/lib/libass.* >/dev/null 2>&1 && ! ls "$PREFIX"/bin/libass-*.dll >/dev/null 2>&1; then
        fetch libass.tar.xz "https://github.com/libass/libass/releases/download/$LIBASS_VERSION/libass-$LIBASS_VERSION.tar.xz"
        rm -rf "libass-$LIBASS_VERSION"
        tar -xJf libass.tar.xz
        (
            cd "libass-$LIBASS_VERSION"
            # libunibreak is optional (marginal CJK line-break gain)
            # and would otherwise bind to a system copy the bundle
            # cannot promise.
            ASS_FLAGS="--disable-libunibreak"
            ASS_LDFLAGS=""
            case "$HOST_OS" in
                linux)
                    # fontconfig stays a dynamic SYSTEM library (every
                    # desktop has it); exclude-libs keeps the STATIC
                    # freetype/harfbuzz symbols private, or ELF
                    # interposition binds FT_* across the system copy
                    # fontconfig drags in -- silent version-skew crashes.
                    # Name those two archives, not ALL: libass's own
                    # ass_* enter the link through a libtool convenience
                    # archive, so ALL localizes them too and the .so
                    # ships an empty dynamic symbol table -- it loads but
                    # exports nothing, so the binding finds no entries.
                    ASS_FLAGS="$ASS_FLAGS --enable-fontconfig"
                    ASS_LDFLAGS="-Wl,--exclude-libs,libfreetype.a:libharfbuzz.a"
                    ;;
                mac)
                    ASS_FLAGS="$ASS_FLAGS --disable-fontconfig" # CoreText autodetects
                    ;;
                windows)
                    ASS_FLAGS="$ASS_FLAGS --disable-fontconfig" # DirectWrite autodetects
                    # freetype/harfbuzz are shared DLLs on Windows (built
                    # above), so libtool links their import libs cleanly;
                    # fold libass's own C++/GCC runtime in to match.
                    ASS_LDFLAGS="$RT_LDFLAGS"
                    ;;
            esac
            # CLANGARM64 libtool builds no aarch64-mingw DLL; regenerate with the
            # system libtool first (as for freetype/fribidi above).
            if [ "$HOST_OS" = windows ] && [ "$HOST_ARCH" = arm64 ]; then autoreconf -fi; fi
            PKG_CONFIG_PATH="$DEPS/lib/pkgconfig:$PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}" \
            LDFLAGS="$ASS_LDFLAGS ${LDFLAGS:-}" \
            ./configure --prefix="$PREFIX" --enable-shared --disable-static $ASS_FLAGS \
                ${MAC_CROSS_X64:+--host=x86_64-apple-darwin}
            make -j"$JOBS"
            make install
        ) || exit 1
        # The static freetype+harfbuzz fold leaves several MB of dead
        # symbol weight; the bundles ship stripped.
        case "$HOST_OS" in
            mac) strip -x "$PREFIX"/lib/libass.*.dylib "$PREFIX"/lib/libfribidi.*.dylib 2>/dev/null || true ;;
            windows) strip --strip-unneeded "$PREFIX"/bin/libass-*.dll "$PREFIX"/bin/libfribidi-*.dll 2>/dev/null || true ;;
            *) strip --strip-unneeded "$PREFIX"/lib/libass.so.9.* "$PREFIX"/lib/libfribidi.so.0.* 2>/dev/null || true ;;
        esac
        if [ "$HOST_OS" = mac ]; then
            # Normalize the fribidi edge so the loader resolves it next
            # to libass regardless of the build prefix.
            FRIBIDI_REF="$(otool -L "$PREFIX/lib/libass.9.dylib" | awk '/fribidi/ {print $1}')"
            if [ -n "$FRIBIDI_REF" ]; then
                install_name_tool -change "$FRIBIDI_REF" "@loader_path/libfribidi.0.dylib" "$PREFIX/lib/libass.9.dylib"
            fi
        fi
    fi
    license "libass-$LIBASS_VERSION/COPYING" "libass-COPYING"
fi

if [ ! -d "ffmpeg-$FFMPEG_VERSION" ]; then
    # Two sources. This is the one download every job in the matrix needs, and
    # it had no alternate: ffmpeg.org resetting the connection for twenty
    # seconds is enough to fail a build that is otherwise sound. The GitHub
    # mirror carries the same tree under a different root name, so the
    # extension differs and the directory is normalised after unpacking.
    fetch ffmpeg.tar "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" \
        "https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n$FFMPEG_VERSION"
    tar -xf ffmpeg.tar
    if [ -d "FFmpeg-n$FFMPEG_VERSION" ]; then
        mv "FFmpeg-n$FFMPEG_VERSION" "ffmpeg-$FFMPEG_VERSION"
    fi
fi

cd "ffmpeg-$FFMPEG_VERSION"

# EXTRA_FLAGS exists to be word-split (several configure flags in one
# env var); splitting through an array keeps that working with an empty
# default and quiet shellcheck.
read -ra EXTRA <<< "${EXTRA_FLAGS:-}" || true

# Hardware decode (the hwaccel feature, M11). --disable-everything turns
# every hwaccel off, so the platform's are re-enabled explicitly.
# VideoToolbox (macOS) and D3D11VA/DXVA2 (Windows) need only the system SDK;
# VAAPI (Linux) links libva, which the CI build image must provide
# (libva-dev) and the user's machine provides at runtime like fontconfig.
# NVDEC/NVENC/QSV/AMF need extra SDKs and stay a follow-up.
HWACCEL=()
if has hwaccel; then
case "$HOST_OS" in
    linux)
        HWACCEL=(--enable-vaapi
            --enable-hwaccel=h264_vaapi,hevc_vaapi,vp8_vaapi,vp9_vaapi,av1_vaapi)
        ;;
    mac)
        HWACCEL=(--enable-videotoolbox
            --enable-hwaccel=h264_videotoolbox,hevc_videotoolbox,vp9_videotoolbox)
        ;;
    windows)
        HWACCEL=(--enable-d3d11va --enable-dxva2
            --enable-hwaccel=h264_d3d11va,hevc_d3d11va,vp9_d3d11va,av1_d3d11va,h264_dxva2,hevc_dxva2,vp9_dxva2,av1_dxva2)
        ;;
esac
fi

# On Windows x64 (MinGW GCC) fold the C++/GCC runtime into the ffmpeg
# libraries, so nothing needs libstdc++-6.dll or libgcc_s alongside (the
# libass DLLs do the same). Unconditional, not gated on the C++ encoder that
# first motivated it: swscale alone pulls libgcc_s_seh-1 on this line, so the
# lean tiers were shipping an import no bundle carried. Caught by the
# import-closed check rather than by a user, which is what it is for. The
# aarch64 clang toolchain ships libc++/libunwind as DLLs instead (see the
# runtime-DLL copy below). Empty on Linux/macOS -- the C++ runtime is a
# system library there.
FFLD=()
if [ "$HOST_OS" = windows ] && [ "$HOST_ARCH" = x64 ]; then
    FFLD=(--extra-ldflags=-static-libstdc++ --extra-ldflags=-static-libgcc)
fi

# CLANGARM64 has no gcc, so ffmpeg's configure (which defaults cc=gcc) fails its
# compiler test. Point it at clang and the llvm binutils. This is a native arm64
# build on an arm64 runner, not a cross, so no --enable-cross-compile.
FFTOOLS=()
if [ "$HOST_OS" = windows ] && [ "$HOST_ARCH" = arm64 ]; then
    FFTOOLS=(--cc=clang --cxx=clang++ --ar=llvm-ar --nm=llvm-nm --ranlib=llvm-ranlib --strip=llvm-strip)
fi

# The whitelist is assembled from FEATURES (ROADMAP.md section 4). The
# always-on base is the core playback set: H.264/HEVC video, the native
# audio decoders (opus/vorbis/aac/mp3/flac plus the real-life rip set --
# ac3/eac3, alac, 24/32-bit and float WAV; the ac3 parser frames both ac3
# and eac3), the still-image decoders, the consumer's containers, and the
# playback-rate filter chain (atempo plus its abuffer/abuffersink ends).
# Each optional feature adds its decoders/demuxers and, for av1/vpx, links
# its external library; vp8/vp9 ride the vpx feature because the bundle
# uses libvpx (the native decoders drop the webm alpha channel). Any
# encoder (software x264/x265 or hardware VAAPI) pulls in the output muxers
# and the native aac/flac encoders; only the software x264/x265 flip
# --enable-gpl -- the VAAPI encoder lives in the driver and stays LGPL.
DEMUX="mov,matroska,gif,apng,image2,image_png_pipe,image_jpeg_pipe,ogg,mp3,flac,wav,ac3,eac3"
DECODE="h264,hevc,mjpeg,png,apng,gif,aac,mp3,opus,vorbis,flac,ac3,eac3,alac,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le"
PARSE="h264,hevc,mjpeg,png,gif,aac,mpegaudio,opus,vorbis,flac,ac3"
LIBS=()
ENC=()
GPL=()
has av1  && { LIBS+=(--enable-libdav1d); DECODE+=",libdav1d,av1"; PARSE+=",av1"; }
has vpx  && { LIBS+=(--enable-libvpx); DECODE+=",vp8,vp9,libvpx_vp8,libvpx_vp9"; PARSE+=",vp8,vp9"; }
has webp && { DEMUX+=",image_webp_pipe"; DECODE+=",webp"; PARSE+=",webp"; }
has subs && { DEMUX+=",ass,srt,webvtt,sup"; DECODE+=",ass,ssa,srt,subrip,movtext,webvtt,pgssub,dvdsub"; }
# The broad legacy/extended decode set (the "formats" feature). All native
# FFmpeg decoders/demuxers/parsers -- no external library, no --enable-gpl.
has formats && {
    DEMUX+=",avi,mpegps,mpegts,mpegtsraw,flv,live_flv,asf,asf_o,dv,m4v,mpegvideo,rm,rpl,aiff,au,w64,caf,swf,flic"
    DECODE+=",vvc,mpeg1video,mpeg2video,mpeg4,msmpeg4v1,msmpeg4v2,msmpeg4v3,wmv1,wmv2,wmv3,vc1,h263,h263i,h263p,flv,theora,vp3,vp5,vp6,vp6a,vp6f,prores,dnxhd,ffv1,huffyuv,ffvhuff,cinepak,msvideo1,msrle,qtrle,rpza,smc,svq1,svq3,rv10,rv20,rv30,rv40,indeo2,indeo3,indeo4,indeo5,dvvideo,cavs,mjpegb,jpegls,eightbps,targa,tiff,bmp,pcx,sgi,qoi,flic,truemotion1,truemotion2"
    DECODE+=",dca,truehd,mlp,mp1,mp2,wmav1,wmav2,wmapro,wmavoice,amrnb,amrwb,tta,wavpack,ape,gsm,gsm_ms,adpcm_ima_qt,adpcm_ima_wav,adpcm_ms,adpcm_swf,adpcm_yamaha,adpcm_g722,adpcm_g726,adpcm_g726le,cook,sipr,ra_144,ra_288,ralf,nellymoser,qdm2,qdmc,atrac1,atrac3,atrac3p,atrac3pal,atrac9,dvaudio,mp3on4,aac_latm,tak,als,mpc7,mpc8,pcm_u8,pcm_s8,pcm_s16be,pcm_s24be,pcm_s32be,pcm_f64le,pcm_mulaw,pcm_alaw"
    PARSE+=",vvc,mpegvideo,mpeg4video,vc1,h263,dca,cavsvideo"
}
# Software H.264/HEVC encoders (x264/x265) -- GPL, so they flip --enable-gpl.
has enc-h264 && { LIBS+=(--enable-libx264); ENC+=(libx264); GPL=(--enable-gpl); }
has enc-hevc && { LIBS+=(--enable-libx265); ENC+=(libx265); GPL=(--enable-gpl); }

# Hardware H.264/HEVC encoders (M13). The codec runs in the GPU driver
# (Mesa/iHD for VAAPI), so these are LGPL -- they do NOT flip --enable-gpl,
# the licence-clean route to GPU output. VAAPI is Linux-only; a silent
# no-op elsewhere (NVENC/QSV/AMF/VideoToolbox stay a follow-up). --enable-vaapi
# is already on when the hwaccel decode feature is present; add it otherwise.
VAENC=()
if has enc-vaapi && [ "$HOST_OS" = linux ]; then
    ENC+=(h264_vaapi hevc_vaapi)
    has hwaccel || VAENC=(--enable-vaapi)
fi

ENCFLAG=()
MUXFLAG=()
if [ ${#ENC[@]} -gt 0 ]; then
    # Any encoder (software or hardware) needs the output muxers and the
    # native LGPL audio encoders; the GPL flip above is x264/x265 only.
    ENC+=(aac flac)
    ENCFLAG=(--enable-encoder="$(IFS=,; echo "${ENC[*]}")")
    MUXFLAG=(--enable-muxer=mov,mp4,matroska,webm)
fi

# STATIC_DEPS needs the dep prefix on the search paths: zlib ships a .pc that
# pkg-config finds, bzip2 does not. ($ORIGIN is NOT set here -- see the patchelf
# pass at bundle assembly; through configure the $$ reaches a shell rather than
# make and lands in the binary as a pid.)
FFPATHS=()
if [ "${STATIC_DEPS:-}" = "1" ]; then
    FFPATHS+=(--extra-cflags="-I$DEPS/include" --extra-ldflags="-L$DEPS/lib")
fi

# --disable-autodetect is the whole point of this block: without it configure
# links whatever it finds installed, so the machine that BUILDS the bundle
# decides what the machine that RUNS it must have. That is not theoretical --
# a musl bundle built without the hwaccel feature still came out needing
# libva, purely because the container had libva-dev, and it would have failed
# on any musl desktop without a graphics stack. Everything the bundle uses is
# now named here, and tools/bundle-surface.txt asserts the result.
#
# zlib/bzip2/lzma are the three configure would otherwise guess at and that
# skinema uses: png and apng decode needs zlib, matroska reads bzip2- and
# lzma-compressed headers. All three are static, so they cost a consumer
# nothing.
#
# iconv is deliberately NOT among them. It serves ffmpeg's sub_charenc, which
# skinema never sets, and forcing it on links libiconv_* symbols that glibc
# provides inside libc but macOS and MinGW need -liconv for -- so enabling it
# without that flag broke every non-glibc platform. --disable-autodetect still
# lets FFmpeg use an iconv that lives in libc itself, so it ends up on for
# glibc and musl and off for macOS and Windows. That asymmetry costs nothing
# here: skinema never sets sub_charenc, which is the only thing that would
# reach it.
AUTODETECT=(--disable-autodetect --enable-zlib --enable-bzlib)
if [ "${STATIC_DEPS:-}" = "1" ]; then
    AUTODETECT+=(--enable-lzma)
fi

./configure \
    --prefix="$PREFIX" \
    --enable-shared --disable-static \
    --disable-programs --disable-doc --disable-debug \
    --disable-everything --disable-network \
    --disable-avdevice \
    --pkg-config-flags=--static \
    ${GPL[@]+"${GPL[@]}"} \
    ${LIBS[@]+"${LIBS[@]}"} \
    --enable-protocol=file,pipe \
    --enable-demuxer="$DEMUX" \
    --enable-decoder="$DECODE" \
    --enable-parser="$PARSE" \
    ${ENCFLAG[@]+"${ENCFLAG[@]}"} \
    ${MUXFLAG[@]+"${MUXFLAG[@]}"} \
    --enable-filter=atempo \
    ${HWACCEL[@]+"${HWACCEL[@]}"} \
    ${VAENC[@]+"${VAENC[@]}"} \
    ${FFLD[@]+"${FFLD[@]}"} \
    ${FFTOOLS[@]+"${FFTOOLS[@]}"} \
    ${AUTODETECT[@]+"${AUTODETECT[@]}"} \
    ${FFPATHS[@]+"${FFPATHS[@]}"} \
    ${FFMPEG_CROSS[@]+"${FFMPEG_CROSS[@]}"} ${EXTRA[@]+"${EXTRA[@]}"}

# configure warns about a --enable-<thing>=a,b,c list only when it matches
# NOTHING: a name that matches nothing while its siblings match is dropped in
# silence, and the build stays green without it. That is how three image
# demuxers shipped missing for a whole release line (the component name is
# image_webp_pipe, not the webp_pipe the demuxer registers under). Assert every
# requested component actually landed, so a rename between majors fails the
# build that dropped it rather than a user's playback.
verify_enabled() { # kind, comma-list
    local kind="$1" name upper
    for name in $(printf '%s' "$2" | tr ',' ' '); do
        upper="$(printf '%s_%s' "$name" "$kind" | tr '[:lower:]' '[:upper:]')"
        grep -qx "#define CONFIG_$upper 1" config_components.h || WHITELIST_GAPS="$WHITELIST_GAPS $kind:$name"
    done
}
WHITELIST_GAPS=""
if [ -f config_components.h ]; then
    verify_enabled demuxer "$DEMUX"
    verify_enabled decoder "$DECODE"
    verify_enabled parser "$PARSE"
    verify_enabled protocol "file,pipe"
    # Filters: only atempo is a component. abuffer and abuffersink are not --
    # buffersrc and buffersink are built into libavfilter unconditionally, so
    # naming them enabled nothing and was dropped in silence. Measured off
    # config_components.h, then confirmed against the built library, which
    # still resolves all three by name.
    verify_enabled filter atempo
    # A hwaccel that quietly failed to configure costs no error and no missing
    # symbol -- decoding just falls back to software, on every machine, for
    # good. That is the failure this whole check exists to catch, and it was
    # the one kind not being checked.
    if [ ${#HWACCEL[@]} -gt 0 ]; then
        verify_enabled hwaccel "$(printf '%s\n' "${HWACCEL[@]}" | sed -n 's/^--enable-hwaccel=//p')"
    fi
    [ ${#ENC[@]} -gt 0 ] && verify_enabled encoder "$(IFS=,; echo "${ENC[*]}")"
    [ ${#MUXFLAG[@]} -gt 0 ] && verify_enabled muxer "mov,mp4,matroska,webm"
    if [ -n "$WHITELIST_GAPS" ]; then
        echo "build-natives: configure silently dropped:$WHITELIST_GAPS" >&2
        echo "  (the whitelist names a component this FFmpeg line does not have under that name)" >&2
        exit 1
    fi
    echo "whitelist verified: every requested component is enabled"
else
    # Not fatal: a layout change here must not break every native build at
    # once. It does need noticing, hence the shout.
    echo "build-natives: WARNING -- no config_components.h, whitelist NOT verified" >&2
fi

make -j"$JOBS"
make install

# License compliance travels with the binaries: the texts ship inside every
# native bundle, and the exact source is pinned by FFMPEG_VERSION. FFmpeg's
# own license is LGPL until an encoder flips --enable-gpl, so the GPL build
# ships the GPL text too; each dependency's text ships only when its feature
# put that dependency in the bundle.
mkdir -p "$PREFIX/licenses"
cp COPYING.LGPLv2.1 LICENSE.md "$PREFIX/licenses/"
[ ${#GPL[@]} -gt 0 ] && cp COPYING.GPLv2 "$PREFIX/licenses/"
# Each dependency writes its own text into $PREFIX/licenses as it is built,
# next to the guard that decides whether it gets built at all. Stashing them
# in $WORK instead put the two on different lifetimes: a prefix that survived
# a failed run skipped the rebuild, and the text it needed was gone with the
# work tree.

# The Windows DLLs may link toolchain runtime libraries that live in the MSYS2
# prefix (MINGW_PREFIX is /mingw64 on MINGW64, /clangarm64 on CLANGARM64)
# rather than the bundle, so a clean machine that lacks them cannot load
# avcodec or libass. Which ones those are differs by toolchain, by tier and by
# what is linked statically this round -- so they are read off the built DLLs
# instead of named here, and their notices ride along.
if [ "$HOST_OS" = windows ]; then
    MINGW="${MINGW_PREFIX:-/mingw64}"
    command -v objdump >/dev/null 2>&1 || { echo "build-natives: objdump is required to close the DLL imports" >&2; exit 1; }
    # Take the set from what the built DLLs actually import, closed
    # transitively, rather than from a list written by hand. A fixed list is
    # wrong in both directions at once: it shipped zlib1/libbz2-1/liblzma-5/
    # libiconv-2 into every bundle after those became static, and libc++ and
    # libunwind into arm64 tiers with no C++ in them, while a genuinely new
    # import would have gone unnoticed until a clean Windows failed to load.
    runtime_dlls=""
    queue="$(ls "$PREFIX"/bin/*.dll 2>/dev/null | xargs -r -n1 basename)"
    while [ -n "$queue" ]; do
        next=""
        for dll in $queue; do
            [ -f "$PREFIX/bin/$dll" ] || continue
            for imp in $(objdump -p "$PREFIX/bin/$dll" | sed -n 's/.*DLL Name:[[:space:]]*//p'); do
                [ -f "$PREFIX/bin/$imp" ] && continue          # already ours
                [ -f "$MINGW/bin/$imp" ] || continue           # a system DLL; Windows provides it
                cp "$MINGW/bin/$imp" "$PREFIX/bin/"
                runtime_dlls="$runtime_dlls $imp"
                next="$next $imp"
            done
        done
        queue="$next"
    done
    echo "toolchain runtimes pulled in by import closure:${runtime_dlls:- none}"

    # Licence text per DLL that actually shipped. The names are MSYS2 package
    # directories under share/licenses, which do not match the DLL names.
    runtime_lics=""
    for dll in $runtime_dlls; do
        case "$dll" in
            zlib1.dll)           runtime_lics="$runtime_lics zlib" ;;
            libbz2-*.dll)        runtime_lics="$runtime_lics bzip2" ;;
            libiconv-*.dll)      runtime_lics="$runtime_lics libiconv" ;;
            liblzma-*.dll)       runtime_lics="$runtime_lics xz" ;;
            libwinpthread-*.dll) runtime_lics="$runtime_lics winpthreads" ;;
            libc++.dll)          runtime_lics="$runtime_lics libc++" ;;
            libunwind.dll)       runtime_lics="$runtime_lics libunwind" ;;
            libgcc_s*.dll|libstdc++-*.dll) runtime_lics="$runtime_lics gcc-libs" ;;
            *) echo "build-natives: no licence mapping for shipped runtime $dll" >&2; exit 1 ;;
        esac
    done
    if [ "$HOST_ARCH" != arm64 ]; then
        # x64 links libstdc++ and libgcc statically, so no DLL names them --
        # but their code is inside the shipped ones (x265 pulls in the C++
        # runtime), and the GCC Runtime Library Exception is what allows that
        # without the whole bundle inheriting GPLv3.
        runtime_lics="$runtime_lics gcc-libs"
    fi
    runtime_lics="$(printf '%s\n' $runtime_lics | sort -u)"
    # Fail on a missing text rather than skipping it. Shipping a binary whose
    # licence is silently absent is a redistribution problem, and a skip is how
    # the GCC runtime went unlicensed on x64 through several releases.
    for lic in $runtime_lics; do
        found=0
        for dir in "$MINGW/share/licenses/$lic" "/usr/share/licenses/$lic"; do
            for f in "$dir"/*; do
                [ -f "$f" ] || continue
                cp "$f" "$PREFIX/licenses/mingw-$lic-$(basename "$f")"
                found=1
            done
        done
        [ "$found" = 1 ] || { echo "no licence text for toolchain runtime $lic under $MINGW/share/licenses" >&2; exit 1; }
    done
fi

# Flatten into the bundle layout NativeBundle deploys: real files under
# the soname-level names the loader asks for (jars cannot carry the
# symlink chains a normal install uses), licenses, and an index.txt whose
# first line is the content fingerprint -- a jar cannot enumerate its own
# resources, so the index is the bundle's table of contents.
BUNDLE="$PREFIX/bundle"
rm -rf "$BUNDLE"
mkdir -p "$BUNDLE/licenses"
cp "$PREFIX"/licenses/* "$BUNDLE/licenses/"
# Soname-level names come from the symlinks (ELF) or the install names
# (Mach-O): dereference whatever matches lib<name>.<so-or-dylib>.<major>,
# which covers both ffmpeg's x.y.z chains and libtool's single-level
# naming for libwebp.
shopt -s nullglob
for f in "$PREFIX"/lib/lib*.so.*; do
    base="$(basename "$f")"
    if [[ "$base" =~ ^lib[^.]+\.so\.[0-9]+$ ]]; then
        cp -L "$f" "$BUNDLE/$base"
    fi
done
for f in "$PREFIX"/lib/lib*.dylib; do
    base="$(basename "$f")"
    if [[ "$base" =~ ^lib[^.]+\.[0-9]+\.dylib$ ]]; then
        cp -L "$f" "$BUNDLE/$base"
    fi
done
# Plain *.dll, not *-*.dll: the MinGW runtime ships zlib1.dll (no
# version dash), and everything in bin here is a shipping DLL.
for f in "$PREFIX"/bin/*.dll; do
    cp "$f" "$BUNDLE/"
done
shopt -u nullglob

# ELF: make every bundled library find its siblings next to itself. Without
# this a bundle resolves nothing on its own -- dlopen of an absolute
# libavcodec fails on the libswresample lying beside it, and only Libav's
# declaration-order preload papers over that, which is why the bundle loads
# zero of six libraries on a store-based distribution (#23). It also wipes the
# build-time RUNPATH libtool bakes into libass, a CI path that is dead on
# every user machine. Mach-O gets the same treatment below with
# install_name_tool; Windows has no such mechanism, hence the loader's
# preload list.
if [ "$HOST_OS" = mac ]; then
    # Mach-O records an absolute install name per library and copies it into
    # every dependent, so a bundle straight out of the build tree asks for the
    # build runner's own paths. That resolves today only because Libav preloads
    # all six in dependency order by absolute path; anything else fails, and
    # when it fails dyld falls back to $HOME/lib:/usr/local/lib:/usr/lib, where
    # a Homebrew FFmpeg of the same soname major can bind into a binding that
    # pins struct offsets. Rewrite to @loader_path -- the ELF $ORIGIN analogue.
    bundled="$(for f in "$BUNDLE"/*.dylib; do [ -e "$f" ] && basename "$f"; done | sort -u)"
    for f in "$BUNDLE"/*.dylib; do
        install_name_tool -id "@loader_path/$(basename "$f")" "$f"
        otool -L "$f" | awk 'NR > 1 {print $1}' | while read -r ref; do
            base="$(basename "$ref")"
            # Only intra-bundle edges move; /usr/lib and the system
            # frameworks are the host's to provide, as on every platform.
            printf '%s\n' "$bundled" | grep -qx "$base" || continue
            [ "$ref" = "@loader_path/$base" ] || install_name_tool -change "$ref" "@loader_path/$base" "$f"
        done
    done
    # Editing load commands invalidates the ad-hoc signature, and Apple
    # Silicon refuses to map an unsigned or wrongly-signed dylib outright.
    # install_name_tool re-signs by itself on current toolchains; doing it
    # here as well costs nothing and does not depend on that staying true.
    for f in "$BUNDLE"/*.dylib; do
        codesign --force --sign - --timestamp=none "$f" >/dev/null 2>&1 || {
            echo "build-natives: could not re-sign $(basename "$f") after rewriting its install names" >&2
            exit 1
        }
    done
    codesign --verify "$BUNDLE"/*.dylib || { echo "build-natives: bundle signatures do not verify" >&2; exit 1; }
    echo "bundle dylibs re-signed after the install-name rewrite"

    # Prove it: any surviving reference to the build tree is a path that
    # exists on no user machine, and it must not leave this script.
    leaked=0
    for f in "$BUNDLE"/*.dylib; do
        for ref in $(otool -L "$f" | awk 'NR > 1 {print $1}') $(otool -D "$f" | awk 'NR > 1 {print $1}'); do
            case "$ref" in
                "$PREFIX"/*|"$WORK"/*)
                    echo "install name still points into the build tree: $(basename "$f") -> $ref" >&2
                    leaked=$((leaked + 1)) ;;
            esac
        done
    done
    [ "$leaked" = 0 ] || exit 1
    echo "bundle install names rewritten to @loader_path; siblings resolve beside each other"

    # The Mach-O half of the portability surface. /usr/lib and the system
    # frameworks are dropped: they ship with macOS itself and are not
    # something a consumer can lack, which is what the declaration is about.
    key="macos"
    # Filtered with grep rather than a case: macOS still ships bash 3.2, whose
    # parser cannot read a case statement inside a command substitution, and
    # `bash -n` on any newer bash accepts it happily.
    host_deps="$(
        for f in "$BUNDLE"/*.dylib; do
            otool -L "$f" | awk 'NR > 1 {print $1}'
        done | sort -u \
            | { grep -Ev '^(@loader_path/|/usr/lib/|/System/Library/Frameworks/)' || true; } \
            | while read -r ref; do
                  printf '%s\n' "$bundled" | grep -qx "${ref##*/}" || printf '%s\n' "$ref"
              done
    )"
fi

if [ "$HOST_OS" = linux ]; then
    command -v patchelf >/dev/null 2>&1 || { echo "build-natives: patchelf is required to set the bundle RUNPATH" >&2; exit 1; }
    for f in "$BUNDLE"/*.so.*; do
        patchelf --set-rpath '$ORIGIN' "$f"
    done
    # Prove it, and separate the two failure kinds. A sibling that is IN the
    # bundle yet unresolved means the RUNPATH did not take -- that is a build
    # bug and fails here. Anything unresolved that is NOT in the bundle is a
    # host library the consumer must supply; that is the portability surface
    # (#54), so it gets named rather than silently accepted.
    for t in readelf ldd; do
        command -v "$t" >/dev/null 2>&1 || { echo "build-natives: $t is required to verify the bundle" >&2; exit 1; }
    done
    bundled="$(for f in "$BUNDLE"/*.so.*; do [ -e "$f" ] && basename "$f"; done | sort -u)"
    rpath_broken=0
    for f in "$BUNDLE"/*.so.*; do
        # Both ldd dialects: glibc writes "name => not found" to stdout, musl
        # writes "Error loading shared library name: ..." to stderr. Reading
        # only glibc's made this whole proof a no-op on exactly the two
        # platforms where a missing RUNPATH is fatal rather than papered over.
        for miss in $(env -u LD_LIBRARY_PATH ldd "$f" 2>&1 | sed -n \
                -e 's/^[[:space:]]*\([^[:space:]]*\)[[:space:]]*=>[[:space:]]*not found.*/\1/p' \
                -e 's/^Error loading shared library \([^:]*\):.*/\1/p'); do
            printf '%s\n' "$bundled" | grep -qx "$miss" || continue
            echo "RUNPATH did not take: $(basename "$f") cannot see $miss beside it" >&2
            rpath_broken=$((rpath_broken + 1))
        done
    done
    [ "$rpath_broken" = 0 ] || exit 1
    echo "bundle RUNPATH set to \$ORIGIN; siblings resolve without a search path"

    # The portability surface, computed from the ELF rather than from what this
    # build host happens to have installed: every NEEDED entry the bundle does
    # not itself carry is a library the CONSUMER must supply.
    # The program interpreter is dropped rather than declared: it is the thing
    # that resolves NEEDED entries, so it is mapped before any of them can be
    # asked for, and no host can lack it. aarch64 toolchains emit it as a
    # NEEDED entry where x86-64 ones do not, which would otherwise force this
    # file to grow an architecture column to say something never actionable.
    # musl's libc.musl-*.so.1 is that same file under its libc name, and it
    # stays declared -- there it carries the libc, not just the loader.
    host_deps="$(
        for f in "$BUNDLE"/*.so.*; do
            readelf -d "$f" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'
        done | sort -u | { grep -Ev '^ld-(linux|musl)-' || true; } | while read -r n; do
            printf '%s\n' "$bundled" | grep -qx "$n" || printf '%s\n' "$n"
        done
    )"

    # Read the libc off the bundle rather than off the build host: what the
    # libraries actually link is the fact that matters.
    if printf '%s\n' "$host_deps" | grep -q '^libc\.musl-'; then key="linux-musl"; else key="linux-glibc"; fi
fi

# Windows sets no key: it has no equivalent of a NEEDED entry that resolves
# by soname, so its invariant is the loader's preload list instead, checked by
# tools/check-windows-bundle.sh.
if [ -n "${key:-}" ]; then
# Assert it against the declaration rather than printing it and hoping
# someone reads the log. Drift in EITHER direction fails: an unexpected
# dependency is a bundle that will not load somewhere it should, and a
# declared one that never appears means the file is describing a bundle
# we no longer build.
surface="$SCRIPT_DIR/bundle-surface.txt"
if [ -z "$TIER" ]; then
    echo "host surface ($key, custom FEATURES, not asserted): $(echo $host_deps)"
    allowed=""
else
    # [[:space:]][[:space:]]* rather than [[:space:]]\+ -- \+ is a GNU basic
    # regex extension that BSD sed reads as a literal plus, so on macOS the
    # row never matched and the bundle stage failed claiming the file declares
    # no surface for it. GNU sed warns about exactly this.
    allowed="$(sed -n "s/^$key[[:space:]][[:space:]]*$TIER[[:space:]]*=[[:space:]]*//p;/^$key[[:space:]][[:space:]]*$TIER[[:space:]]*=/q" "$surface")"
    [ -n "$allowed" ] || { echo "build-natives: $surface declares no surface for $key $TIER" >&2; exit 1; }
fi
[ "$allowed" = "-" ] && allowed=""
drift=0
[ -z "$TIER" ] && allowed="$(echo $host_deps)"   # nothing to assert against
for got in $host_deps; do
    case " $allowed " in *" $got "*) ;; *)
        echo "UNDECLARED host dependency: $got" >&2; drift=1 ;;
    esac
done
for want in $allowed; do
    # An arch-specific musl libc is only expected on its own arch.
    case "$want" in libc.musl-*) continue ;; esac
    printf '%s\n' "$host_deps" | grep -qx "$want" || {
        echo "DECLARED but unused: $want -- the declaration is stale" >&2; drift=1
    }
done
if [ "$drift" != 0 ]; then
    echo "The bundle's host surface does not match $surface for $key $TIER." >&2
    echo "Needed:   $(echo $host_deps)" >&2
    echo "Declared: $allowed" >&2
    exit 1
fi
[ -z "$TIER" ] || echo "host surface matches the declaration for $key $TIER: $(echo $host_deps)"
fi

# What the bundle PROVIDES, taken from the build rather than restated by hand.
# The requires side is asserted against tools/bundle-surface.txt above; this is
# the other half, and the same argument applies -- the format table in the
# README is hand-maintained, and it advertised mov_text for the whole life of
# the project while no bundle carried the decoder.
#
# Names here are configure's component names, which are not always the name a
# codec registers under at runtime (movtext registers as "mov_text"). The file
# is the substrate for checking a claim, and it makes "what changed between two
# revisions" a diff instead of an archaeology exercise -- which the natives
# version line needs, since a repack that changes nothing must not be republished.
FF_CONFIG="$WORK/ffmpeg-$FFMPEG_VERSION/config_components.h"
if [ -f "$FF_CONFIG" ]; then
    {
        echo "# skinema natives bundle -- enabled components"
        echo "ffmpeg $FFMPEG_VERSION"
        echo "features $FEATURES"
        for kind in DEMUXER MUXER DECODER ENCODER PARSER PROTOCOL FILTER HWACCEL BSF; do
            lower="$(printf '%s' "$kind" | tr '[:upper:]' '[:lower:]')"
            names="$(sed -n "s/^#define CONFIG_\([A-Z0-9_]*\)_$kind 1$/\1/p" "$FF_CONFIG" \
                     | tr '[:upper:]' '[:lower:]' | LC_ALL=C sort | tr '\n' ' ')"
            [ -n "$names" ] && echo "$lower $names"
        done
    } > "$BUNDLE/manifest.txt"
    echo "manifest: $(grep -cE '^(demuxer|muxer|decoder|encoder|parser|protocol|filter|hwaccel|bsf) ' \
        "$BUNDLE/manifest.txt") component lines"
else
    # Same reasoning as the whitelist check: not fatal, but a bundle that
    # ships without its manifest must not do so quietly -- the manifest is
    # what the tier and version claims are checked against.
    echo "build-natives: WARNING -- no $FF_CONFIG, manifest NOT written" >&2
fi

if command -v sha256sum >/dev/null 2>&1; then SHA="sha256sum"; else SHA="shasum -a 256"; fi
(
    cd "$BUNDLE"
    files="$(find . -type f ! -name index.txt | sed 's|^\./||' | LC_ALL=C sort)"
    fingerprint="$(printf '%s\n' "$files" | xargs cat | $SHA | cut -c1-16)"
    { echo "$fingerprint"; printf '%s\n' "$files"; } > index.txt
) || exit 1

echo "== bundle =="
du -sh "$BUNDLE"
ls "$BUNDLE"
