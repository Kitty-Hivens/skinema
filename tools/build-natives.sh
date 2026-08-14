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

mkdir -p "$WORK"
cd "$WORK"

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

fetch() { # url, dest-file
    # Retry: a single upstream hiccup used to kill a twenty-minute build, and
    # it happens -- savannah has answered 502 and zlib.net has dropped TLS
    # mid-handshake during this script's own development. --retry covers the
    # transport, --retry-all-errors extends it to HTTP 5xx, which curl does
    # not treat as retryable by default.
    [ -f "$2" ] || curl -fsSL --retry 5 --retry-delay 2 --retry-all-errors \
        --connect-timeout 20 -o "$2" "$1"
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
        fetch "https://github.com/madler/zlib/releases/download/v$ZLIB_VERSION/zlib-$ZLIB_VERSION.tar.gz" zlib.tar.gz
        rm -rf "zlib-$ZLIB_VERSION"
        tar -xzf zlib.tar.gz
        (
            cd "zlib-$ZLIB_VERSION"
            CFLAGS="-fPIC ${CFLAGS:-}" ./configure --prefix="$DEPS" --static
            make -j"$JOBS"
            make install
        )
        cp "zlib-$ZLIB_VERSION/LICENSE" "$WORK/zlib-LICENSE"
    fi

    if [ ! -f "$DEPS/lib/libbz2.a" ]; then
        fetch "https://sourceware.org/pub/bzip2/bzip2-$BZIP2_VERSION.tar.gz" bzip2.tar.gz
        rm -rf "bzip2-$BZIP2_VERSION"
        tar -xzf bzip2.tar.gz
        (
            cd "bzip2-$BZIP2_VERSION"
            # No configure; the makefile takes CC/CFLAGS and PREFIX directly.
            make -j"$JOBS" libbz2.a CC="${CC:-cc}" CFLAGS="-fPIC -O2 -D_FILE_OFFSET_BITS=64"
            mkdir -p "$DEPS/lib" "$DEPS/include"
            cp libbz2.a "$DEPS/lib/"
            cp bzlib.h "$DEPS/include/"
        )
        cp "bzip2-$BZIP2_VERSION/LICENSE" "$WORK/bzip2-LICENSE"
    fi

    # liblzma joins zlib and bzip2 for the same reason: matroska reads
    # LZMA-compressed headers through it, and left to autodetect it becomes a
    # host dependency -- measured, liblzma.so.5 appeared in the decode tier
    # the moment --enable-lzma went in without this.
    if [ ! -f "$DEPS/lib/liblzma.a" ]; then
        fetch "https://github.com/tukaani-project/xz/releases/download/v$XZ_VERSION/xz-$XZ_VERSION.tar.gz" xz.tar.gz
        rm -rf "xz-$XZ_VERSION"
        tar -xzf xz.tar.gz
        (
            cd "xz-$XZ_VERSION"
            ./configure --prefix="$DEPS" --disable-shared --enable-static --with-pic \
                --disable-xz --disable-xzdec --disable-lzmadec --disable-lzmainfo \
                --disable-lzma-links --disable-scripts --disable-doc \
                ${MAC_CROSS_X64:+--host=x86_64-apple-darwin}
            make -j"$JOBS"
            make install
        )
        cp "xz-$XZ_VERSION/COPYING" "$WORK/xz-COPYING"
    fi

    if has av1 && [ ! -f "$DEPS/lib/libdav1d.a" ]; then
        fetch "https://code.videolan.org/videolan/dav1d/-/archive/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.gz" dav1d.tar.gz
        rm -rf "dav1d-$DAV1D_VERSION"
        tar -xzf dav1d.tar.gz
        meson setup "dav1d-$DAV1D_VERSION/build" "dav1d-$DAV1D_VERSION" \
            --prefix="$DEPS" --libdir=lib --default-library=static --buildtype=release \
            -Denable_tools=false -Denable_tests=false ${MESON_CROSS[@]+"${MESON_CROSS[@]}"}
        ninja -C "dav1d-$DAV1D_VERSION/build" install
        cp "dav1d-$DAV1D_VERSION/COPYING" "$WORK/dav1d-COPYING"
    fi

    # libwebp ships SHARED into the bundle prefix (the webp bindings load it at
    # runtime; it is not linked into ffmpeg). Autotools elsewhere -- libtool
    # produces the soname naming the loader expects (libwebp.so.7 /
    # libwebp.7.dylib / libwebp-7.dll). On CLANGARM64 that libtool cannot fold
    # the static sharpyuv convenience lib into a DLL and emits no libwebp DLL at
    # all, so build with cmake there (as MSYS2 does); CMAKE_DLL_NAME_WITH_SOVERSION
    # reproduces the same -<major> DLL names.
    if has webp && ! ls "$PREFIX"/lib/libwebp.* >/dev/null 2>&1 && ! ls "$PREFIX"/bin/libwebp-*.dll >/dev/null 2>&1; then
        fetch "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-$WEBP_VERSION.tar.gz" libwebp-dist.tar.gz
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
            )
        fi
        cp "libwebp-$WEBP_VERSION/COPYING" "$WORK/libwebp-COPYING"
    fi

    if has vpx && [ ! -f "$DEPS/lib/libvpx.a" ]; then
        fetch "https://github.com/webmproject/libvpx/archive/refs/tags/$VPX_VERSION.tar.gz" libvpx.tar.gz
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
        )
        cp "libvpx-${VPX_VERSION#v}/LICENSE" "$WORK/libvpx-LICENSE"
    fi

    # x264 (H.264 encoder, GPL -- M12 encode bundle). Static + PIC, folded
    # into FFmpeg like dav1d/libvpx so the shipped library carries no extra
    # runtime dependency. Autotools + nasm (already in CI), no cmake -- the
    # cmake encoders (x265, SVT-AV1) and libopus arrive in later rounds.
    if has enc-h264 && [ ! -f "$DEPS/lib/libx264.a" ]; then
        fetch "https://code.videolan.org/videolan/x264/-/archive/$X264_VERSION/x264-$X264_VERSION.tar.gz" x264.tar.gz
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
        )
        cp "x264-$X264_VERSION/COPYING" "$WORK/x264-COPYING"
    fi

    # x265 (HEVC encoder, GPL). cmake + nasm; static 8-bit (10/12-bit HDR
    # multilib skipped -- 8-bit is the common case), PIC, folded in. x265 is
    # C++, so on Windows the ffmpeg link folds the C++ runtime in (see FFLD
    # at the configure below); Linux/macOS take the system C++ runtime.
    if has enc-hevc && [ ! -f "$DEPS/lib/libx265.a" ]; then
        fetch "https://download.videolan.org/pub/videolan/x265/x265_$X265_VERSION.tar.gz" x265.tar.gz
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
        cp "x265_$X265_VERSION/COPYING" "$WORK/x265-COPYING"
    fi
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
        fetch "https://download.savannah.gnu.org/releases/freetype/freetype-$FREETYPE_VERSION.tar.xz" freetype.tar.xz
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
        )
        cp "freetype-$FREETYPE_VERSION/docs/FTL.TXT" "$WORK/freetype-FTL.TXT"
    fi

    if ! ls "$PREFIX"/lib/libfribidi.* >/dev/null 2>&1 && ! ls "$PREFIX"/bin/libfribidi-*.dll >/dev/null 2>&1; then
        fetch "https://github.com/fribidi/fribidi/releases/download/v$FRIBIDI_VERSION/fribidi-$FRIBIDI_VERSION.tar.xz" fribidi.tar.xz
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
        )
        cp "fribidi-$FRIBIDI_VERSION/COPYING" "$WORK/fribidi-COPYING"
    fi

    if [ ! -f "$HB_PREFIX/lib/libharfbuzz.a" ] && ! ls "$HB_PREFIX"/bin/libharfbuzz-*.dll >/dev/null 2>&1; then
        fetch "https://github.com/harfbuzz/harfbuzz/releases/download/$HARFBUZZ_VERSION/harfbuzz-$HARFBUZZ_VERSION.tar.xz" harfbuzz.tar.xz
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
        cp "harfbuzz-$HARFBUZZ_VERSION/COPYING" "$WORK/harfbuzz-COPYING"
    fi

    if ! ls "$PREFIX"/lib/libass.* >/dev/null 2>&1 && ! ls "$PREFIX"/bin/libass-*.dll >/dev/null 2>&1; then
        fetch "https://github.com/libass/libass/releases/download/$LIBASS_VERSION/libass-$LIBASS_VERSION.tar.xz" libass.tar.xz
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
        )
        cp "libass-$LIBASS_VERSION/COPYING" "$WORK/libass-COPYING"
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
fi

if [ ! -d "ffmpeg-$FFMPEG_VERSION" ]; then
    fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" ffmpeg.tar.xz
    tar -xJf ffmpeg.tar.xz
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

# x265 is C++; on Windows x64 (MinGW GCC) fold the C++/GCC runtime into the
# ffmpeg libraries so avcodec needs no libstdc++-6.dll / libgcc_s alongside
# (the libass DLLs do the same). The aarch64 clang toolchain ships libc++ /
# libunwind as DLLs instead (see the runtime-DLL copy below). Empty on
# Linux/macOS -- the C++ runtime is a system library there.
FFLD=()
if has enc-hevc && [ "$HOST_OS" = windows ] && [ "$HOST_ARCH" = x64 ]; then
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
# zlib/bzip2/lzma/iconv are the four configure would otherwise guess at. All
# four are kept -- png and apng decode needs zlib, matroska uses bzip2 and
# lzma for compressed headers, and iconv is the subtitle charset path -- and
# all four are static, so they cost the consumer nothing.
AUTODETECT=(--disable-autodetect --enable-zlib --enable-bzlib)
if [ "${STATIC_DEPS:-}" = "1" ]; then
    AUTODETECT+=(--enable-lzma --enable-iconv)
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
    --enable-filter=atempo,abuffer,abuffersink \
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
    # Filters are deliberately not verified: configure derives their component
    # names by stripping the ff_<type>_ prefix, so ff_asrc_abuffer and
    # ff_asink_abuffer both collapse to "abuffer" and the sink has no component
    # name of its own. --enable-filter=abuffer is what brings both in.
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
if [ "${STATIC_DEPS:-}" = "1" ]; then
    # Every text is read from $WORK, stashed there when its dependency was
    # built: the source trees are only unpacked when the library is missing,
    # so a warm $WORK has the archives and none of the trees to copy from.
    cp "$WORK/zlib-LICENSE" "$PREFIX/licenses/zlib-LICENSE"
    cp "$WORK/bzip2-LICENSE" "$PREFIX/licenses/bzip2-LICENSE"
    cp "$WORK/xz-COPYING" "$PREFIX/licenses/xz-COPYING"
    has av1      && cp "$WORK/dav1d-COPYING" "$PREFIX/licenses/dav1d-COPYING"
    has vpx      && cp "$WORK/libvpx-LICENSE" "$PREFIX/licenses/libvpx-LICENSE"
    has webp     && cp "$WORK/libwebp-COPYING" "$PREFIX/licenses/libwebp-COPYING"
    has enc-h264 && cp "$WORK/x264-COPYING" "$PREFIX/licenses/x264-COPYING"
    has enc-hevc && cp "$WORK/x265-COPYING" "$PREFIX/licenses/x265-COPYING"
    if has subs; then
        cp "$WORK/libass-COPYING" "$PREFIX/licenses/libass-COPYING"
        cp "$WORK/freetype-FTL.TXT" "$PREFIX/licenses/freetype-FTL.TXT"
        cp "$WORK/harfbuzz-COPYING" "$PREFIX/licenses/harfbuzz-COPYING"
        cp "$WORK/fribidi-COPYING" "$PREFIX/licenses/fribidi-COPYING"
    fi
fi

# The Windows DLLs link toolchain runtime libraries -- zlib1/libbz2-1 (the
# ffmpeg demuxers), libiconv-2 (avcodec + libass), liblzma-5 (avcodec's
# xz/lzma path), libwinpthread-1 (av threading) -- that live in the MSYS2
# prefix, not the bundle, so a clean machine that lacks them cannot load
# avcodec or libass. Every one an av* DLL hard-imports must ride here, or
# LoadLibrary of the importer fails on a clean Windows. The aarch64
# clang toolchain additionally links its C++ runtime and unwinder as DLLs
# (libc++/libunwind), where x64 GCC folded libstdc++/libgcc in statically.
# Copy them all in (MINGW_PREFIX is /mingw64 on MINGW64, /clangarm64 on
# CLANGARM64) with their notices so the bundle is self-contained. Hard-fail
# on a missing name -- a typo would silently ship a host-only bundle.
if [ "$HOST_OS" = windows ]; then
    MINGW="${MINGW_PREFIX:-/mingw64}"
    runtime_dlls="zlib1 libbz2-1 libiconv-2 liblzma-5 libwinpthread-1"
    runtime_lics="zlib bzip2 libiconv xz winpthreads"
    if [ "$HOST_ARCH" = arm64 ]; then
        runtime_dlls="$runtime_dlls libc++ libunwind"
        runtime_lics="$runtime_lics libc++ libunwind"
    fi
    for dll in $runtime_dlls; do
        cp "$MINGW/bin/$dll.dll" "$PREFIX/bin/" \
            || { echo "missing toolchain runtime $dll.dll under $MINGW/bin" >&2; exit 1; }
    done
    for lic in $runtime_lics; do
        f="$(ls "$MINGW/share/licenses/$lic"/* 2>/dev/null | head -1)"
        [ -n "$f" ] && cp "$f" "$PREFIX/licenses/mingw-$lic-LICENSE.txt"
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
# every user machine. The Mach-O equivalent is the install_name_tool pass
# above; Windows has no such mechanism, hence the loader's preload list.
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
    bundled="$(ls "$BUNDLE"/*.so.* 2>/dev/null | xargs -n1 basename | sort -u)"
    rpath_broken=0
    for f in "$BUNDLE"/*.so.*; do
        for miss in $(env -u LD_LIBRARY_PATH ldd "$f" 2>/dev/null | awk '/not found/ {print $1}'); do
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
    host_deps="$(
        for f in "$BUNDLE"/*.so.*; do
            readelf -d "$f" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'
        done | sort -u | while read -r n; do
            printf '%s\n' "$bundled" | grep -qx "$n" || printf '%s\n' "$n"
        done
    )"

    # Assert it against the declaration rather than printing it and hoping
    # someone reads the log. Drift in EITHER direction fails: an unexpected
    # dependency is a bundle that will not load somewhere it should, and a
    # declared one that never appears means the file is describing a bundle
    # we no longer build.
    # Read the libc off the bundle rather than off the build host: what the
    # libraries actually link is the fact that matters.
    if printf '%s\n' "$host_deps" | grep -q '^libc\.musl-'; then key="linux-musl"; else key="linux-glibc"; fi
    surface="$SCRIPT_DIR/bundle-surface.txt"
    if [ -z "$TIER" ]; then
        echo "host surface ($key, custom FEATURES, not asserted): $(echo $host_deps)"
        allowed=""
    else
        allowed="$(sed -n "s/^$key[[:space:]]\+$TIER[[:space:]]*=[[:space:]]*//p" "$surface" | head -1)"
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

if command -v sha256sum >/dev/null 2>&1; then SHA="sha256sum"; else SHA="shasum -a 256"; fi
(
    cd "$BUNDLE"
    files="$(find . -type f ! -name index.txt | sed 's|^\./||' | LC_ALL=C sort)"
    fingerprint="$(printf '%s\n' "$files" | xargs cat | $SHA | cut -c1-16)"
    { echo "$fingerprint"; printf '%s\n' "$files"; } > index.txt
)

echo "== bundle =="
du -sh "$BUNDLE"
ls "$BUNDLE"
