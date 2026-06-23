#!/usr/bin/env bash
# Trimmed FFmpeg build for skinema (ROADMAP.md section 4): shared, LGPL,
# decode-only whitelist, no network. Used both locally (against system
# libvpx/dav1d) and in CI (STATIC_DEPS=1 builds them from source as static
# PIC so the shipped libav* carry no extra runtime dependencies).
#
#   tools/build-natives.sh [prefix]
#
# Env:
#   FFMPEG_VERSION  release to build (default 8.1.1; must stay in the n8.1 pin)
#   STATIC_DEPS=1   shipping mode: libvpx + dav1d from source statically
#                   linked into ffmpeg, plus libwebp/libwebpdemux built as
#                   SHARED libraries for the bundle (the animated-WebP path
#                   binds them directly; FFmpeg cannot decode animations)
#   WEBP_VERSION    libwebp release for STATIC_DEPS (default 1.5.0)
#   VPX_VERSION     libvpx tag for STATIC_DEPS (default v1.15.2)
#   VPX_TARGET      libvpx configure --target (needed under MSYS2: x86_64-win64-gcc)
#   DAV1D_VERSION   dav1d tag for STATIC_DEPS (default 1.5.1)
#   MAC_CROSS_X64=1 cross-compile x86_64 binaries on an arm64 mac (GitHub's
#                   Intel runners are scarce-to-dead; Apple's toolchain
#                   cross-builds natively via -arch)
#   EXTRA_FLAGS     appended to ffmpeg ./configure (cross builds etc.)
#   JOBS            parallel make (default nproc)
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-8.1.1}"
WEBP_VERSION="${WEBP_VERSION:-1.5.0}"
VPX_VERSION="${VPX_VERSION:-v1.15.2}"
DAV1D_VERSION="${DAV1D_VERSION:-1.5.1}"
X264_VERSION="${X264_VERSION:-stable}"
X265_VERSION="${X265_VERSION:-4.1}"
FREETYPE_VERSION="${FREETYPE_VERSION:-2.13.3}"
HARFBUZZ_VERSION="${HARFBUZZ_VERSION:-10.1.0}"
FRIBIDI_VERSION="${FRIBIDI_VERSION:-1.0.16}"
LIBASS_VERSION="${LIBASS_VERSION:-0.17.4}"
JOBS="${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu)}"
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
    [ -f "$2" ] || curl -fsSL -o "$2" "$1"
}

if [ "${STATIC_DEPS:-}" = "1" ]; then
    DEPS="$WORK/deps"
    export PKG_CONFIG_PATH="$DEPS/lib/pkgconfig:$DEPS/lib64/pkgconfig:${PKG_CONFIG_PATH:-}"

    if [ ! -f "$DEPS/lib/libdav1d.a" ]; then
        fetch "https://code.videolan.org/videolan/dav1d/-/archive/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.gz" dav1d.tar.gz
        rm -rf "dav1d-$DAV1D_VERSION"
        tar -xzf dav1d.tar.gz
        meson setup "dav1d-$DAV1D_VERSION/build" "dav1d-$DAV1D_VERSION" \
            --prefix="$DEPS" --libdir=lib --default-library=static --buildtype=release \
            -Denable_tools=false -Denable_tests=false ${MESON_CROSS[@]+"${MESON_CROSS[@]}"}
        ninja -C "dav1d-$DAV1D_VERSION/build" install
    fi

    # libwebp ships SHARED into the bundle prefix (the webp bindings load
    # it at runtime; it is not linked into ffmpeg). Autotools, not cmake:
    # libtool produces the soname naming the loader expects on every OS
    # (libwebp.so.7 / libwebp.7.dylib / libwebp-7.dll).
    if ! ls "$PREFIX"/lib/libwebp.* >/dev/null 2>&1 && ! ls "$PREFIX"/bin/libwebp-*.dll >/dev/null 2>&1; then
        fetch "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-$WEBP_VERSION.tar.gz" libwebp-dist.tar.gz
        rm -rf "libwebp-$WEBP_VERSION"
        tar -xzf libwebp-dist.tar.gz
        (
            cd "libwebp-$WEBP_VERSION"
            ./configure --prefix="$PREFIX" --enable-shared --disable-static \
                --enable-libwebpdemux --disable-libwebpmux \
                ${MAC_CROSS_X64:+--host=x86_64-apple-darwin}
            make -j"$JOBS"
            make install
        )
        cp "libwebp-$WEBP_VERSION/COPYING" "$WORK/libwebp-COPYING"
    fi

    if [ ! -f "$DEPS/lib/libvpx.a" ]; then
        fetch "https://github.com/webmproject/libvpx/archive/refs/tags/$VPX_VERSION.tar.gz" libvpx.tar.gz
        rm -rf "libvpx-${VPX_VERSION#v}"
        tar -xzf libvpx.tar.gz
        (
            cd "libvpx-${VPX_VERSION#v}"
            ./configure --prefix="$DEPS" --disable-examples --disable-tools \
                --disable-docs --disable-unit-tests --enable-pic --enable-vp8 \
                --enable-vp9 --disable-shared --enable-static \
                ${VPX_TARGET:+--target=$VPX_TARGET}
            make -j"$JOBS"
            make install
        )
    fi

    # x264 (H.264 encoder, GPL -- M12 encode bundle). Static + PIC, folded
    # into FFmpeg like dav1d/libvpx so the shipped library carries no extra
    # runtime dependency. Autotools + nasm (already in CI), no cmake -- the
    # cmake encoders (x265, SVT-AV1) and libopus arrive in later rounds.
    if [ ! -f "$DEPS/lib/libx264.a" ]; then
        fetch "https://code.videolan.org/videolan/x264/-/archive/$X264_VERSION/x264-$X264_VERSION.tar.gz" x264.tar.gz
        rm -rf "x264-$X264_VERSION"
        tar -xzf x264.tar.gz
        (
            cd "x264-$X264_VERSION"
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
    if [ ! -f "$DEPS/lib/libx265.a" ]; then
        fetch "https://download.videolan.org/pub/videolan/x265/x265_$X265_VERSION.tar.gz" x265.tar.gz
        rm -rf "x265_$X265_VERSION"
        tar -xzf x265.tar.gz
        cmake -G Ninja -S "x265_$X265_VERSION/source" -B "x265_$X265_VERSION/build" \
            -DCMAKE_INSTALL_PREFIX="$DEPS" -DCMAKE_BUILD_TYPE=Release \
            -DENABLE_SHARED=OFF -DENABLE_CLI=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
            ${MAC_CROSS_X64:+-DCMAKE_OSX_ARCHITECTURES=x86_64}
        ninja -C "x265_$X265_VERSION/build" install
        cp "x265_$X265_VERSION/COPYING" "$WORK/x265-COPYING"
    fi
fi

if [ "${STATIC_DEPS:-}" = "1" ]; then
    # --- The libass stack (text subtitles). libass ships SHARED in the
    # bundle; fribidi is LGPL-2.1 and ships as its OWN shared library
    # (the libwebp precedent). freetype (FTL) and harfbuzz (MIT) fold in
    # STATICALLY on Linux/macOS -- but MinGW libtool will not put a
    # static archive into a DLL, so on Windows they too ship as shared
    # DLLs (installed into the bundle prefix, preloaded by the loader),
    # each linking the C++/GCC runtime in so it stays self-contained.
    # Their licenses already travel with every bundle (FTL + harfbuzz
    # COPYING below), so shipping the DLLs adds no licensing surface.
    case "$(uname -s)" in MINGW*|MSYS*) WINASS=1 ;; *) WINASS= ;; esac
    if [ -n "$WINASS" ]; then
        FT_PREFIX="$PREFIX"; FT_KIND="--enable-shared --disable-static"
        HB_PREFIX="$PREFIX"; HB_KIND="shared"; HB_PKG="$DEPS/lib/pkgconfig:$PREFIX/lib/pkgconfig"
        RT_LDFLAGS="-static-libgcc -static-libstdc++"
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
            case "$(uname -s)" in
                Linux)
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
                Darwin)
                    ASS_FLAGS="$ASS_FLAGS --disable-fontconfig" # CoreText autodetects
                    ;;
                MINGW*|MSYS*)
                    ASS_FLAGS="$ASS_FLAGS --disable-fontconfig" # DirectWrite autodetects
                    # freetype/harfbuzz are shared DLLs on Windows (built
                    # above), so libtool links their import libs cleanly;
                    # fold libass's own C++/GCC runtime in to match.
                    ASS_LDFLAGS="$RT_LDFLAGS"
                    ;;
            esac
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
        case "$(uname -s)" in
            Darwin) strip -x "$PREFIX"/lib/libass.*.dylib "$PREFIX"/lib/libfribidi.*.dylib 2>/dev/null || true ;;
            MINGW*|MSYS*) strip --strip-unneeded "$PREFIX"/bin/libass-*.dll "$PREFIX"/bin/libfribidi-*.dll 2>/dev/null || true ;;
            *) strip --strip-unneeded "$PREFIX"/lib/libass.so.9.* "$PREFIX"/lib/libfribidi.so.0.* 2>/dev/null || true ;;
        esac
        if [ "$(uname -s)" = "Darwin" ]; then
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

# Hardware decode (M11). --disable-everything turns every hwaccel off, so
# the platform's are re-enabled explicitly. VideoToolbox (macOS) and
# D3D11VA/DXVA2 (Windows) need only the system SDK; VAAPI (Linux) links
# libva, which the CI build image must provide (libva-dev) and the user's
# machine provides at runtime like fontconfig. NVDEC/NVENC/QSV/AMF need
# extra SDKs and stay a follow-up. UNVALIDATED until a natives.yml run --
# the hw decode code is proven against the system FFmpeg, not yet a bundle.
HWACCEL=()
case "$(uname -s)" in
    Linux)
        HWACCEL=(--enable-vaapi
            --enable-hwaccel=h264_vaapi,hevc_vaapi,vp8_vaapi,vp9_vaapi,av1_vaapi)
        ;;
    Darwin)
        HWACCEL=(--enable-videotoolbox
            --enable-hwaccel=h264_videotoolbox,hevc_videotoolbox,vp9_videotoolbox)
        ;;
    MINGW*|MSYS*)
        HWACCEL=(--enable-d3d11va --enable-dxva2
            --enable-hwaccel=h264_d3d11va,hevc_d3d11va,vp9_d3d11va,av1_d3d11va,h264_dxva2,hevc_dxva2,vp9_dxva2,av1_dxva2)
        ;;
esac

# x265 is C++; on Windows fold the MinGW C++/GCC runtime into the ffmpeg
# libraries so avcodec needs no libstdc++-6.dll / libgcc_s alongside (the
# libass DLLs already do this). Empty elsewhere -- the Linux/macOS C++
# runtime is a system library.
FFLD=()
case "$(uname -s)" in
    MINGW*|MSYS*) FFLD=(--extra-ldflags=-static-libstdc++ --extra-ldflags=-static-libgcc) ;;
esac

# Decode whitelist (ROADMAP.md section 4). Demuxers cover the consumer's
# container set plus standalone audio for M5; native opus/vorbis/aac/mp3/
# flac decoders need no external libraries. The real-life audio set --
# ac3/eac3 (movie rips), alac (m4a lossless), 24/32-bit and float WAV --
# rides the same native decoders. The ac3 parser frames both ac3 and
# eac3. libvpx is required, not a nicety: the native vp8/vp9 decoders
# drop the webm alpha side-channel. libavfilter carries exactly the
# playback-rate chain: atempo plus its abuffer/abuffersink endpoints.
./configure \
    --prefix="$PREFIX" \
    --enable-shared --disable-static \
    --disable-programs --disable-doc --disable-debug \
    --disable-everything --disable-network \
    --disable-avdevice \
    --pkg-config-flags=--static \
    --enable-libvpx --enable-libdav1d \
    --enable-gpl --enable-libx264 --enable-libx265 \
    --enable-protocol=file,pipe \
    --enable-demuxer=mov,matroska,gif,apng,image2,png_pipe,webp_pipe,jpeg_pipe,ogg,mp3,flac,wav,ac3,eac3,ass,srt,webvtt,sup \
    --enable-decoder=h264,hevc,vp8,vp9,libvpx_vp8,libvpx_vp9,libdav1d,av1,mjpeg,png,apng,gif,webp,aac,mp3,opus,vorbis,flac,ac3,eac3,alac,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,ass,ssa,srt,subrip,mov_text,webvtt,pgssub,dvdsub \
    --enable-parser=h264,hevc,vp8,vp9,av1,mjpeg,png,webp,gif,aac,mpegaudio,opus,vorbis,flac,ac3 \
    --enable-encoder=libx264,libx265,aac,flac \
    --enable-muxer=mov,mp4,matroska,webm \
    --enable-filter=atempo,abuffer,abuffersink \
    ${HWACCEL[@]+"${HWACCEL[@]}"} \
    ${FFLD[@]+"${FFLD[@]}"} \
    ${FFMPEG_CROSS[@]+"${FFMPEG_CROSS[@]}"} ${EXTRA[@]+"${EXTRA[@]}"}

make -j"$JOBS"
make install

# LGPL compliance travels with the binaries: license texts ship inside
# every native bundle, and the exact source is pinned by FFMPEG_VERSION.
mkdir -p "$PREFIX/licenses"
cp COPYING.LGPLv2.1 LICENSE.md "$PREFIX/licenses/"
if [ "${STATIC_DEPS:-}" = "1" ]; then
    cp "$WORK/dav1d-$DAV1D_VERSION/COPYING" "$PREFIX/licenses/dav1d-COPYING"
    cp "$WORK/libvpx-${VPX_VERSION#v}/LICENSE" "$PREFIX/licenses/libvpx-LICENSE"
    cp "$WORK/x264-COPYING" "$PREFIX/licenses/x264-COPYING"
    cp "$WORK/x265-COPYING" "$PREFIX/licenses/x265-COPYING"
    cp "$WORK/libwebp-COPYING" "$PREFIX/licenses/libwebp-COPYING"
    cp "$WORK/libass-COPYING" "$PREFIX/licenses/libass-COPYING"
    cp "$WORK/freetype-FTL.TXT" "$PREFIX/licenses/freetype-FTL.TXT"
    cp "$WORK/harfbuzz-COPYING" "$PREFIX/licenses/harfbuzz-COPYING"
    cp "$WORK/fribidi-COPYING" "$PREFIX/licenses/fribidi-COPYING"
fi

# The Windows DLLs link MinGW runtime libraries -- zlib1/libbz2-1 (the
# ffmpeg demuxers), libiconv-2 (avcodec + libass), libwinpthread-1
# (av threading) -- that live in the MSYS2 prefix, not the bundle, so a
# clean machine that lacks them cannot load avcodec or libass. Copy them
# in (enumerated from the shipped DLLs' import tables) with their notices
# so the bundle is self-contained. Hard-fail on a missing name -- a typo
# would silently ship a broken bundle that only the build host can load.
case "$(uname -s)" in
    MINGW*|MSYS*)
        MINGW="${MINGW_PREFIX:-/mingw64}"
        for dll in zlib1 libbz2-1 libiconv-2 libwinpthread-1; do
            cp "$MINGW/bin/$dll.dll" "$PREFIX/bin/" \
                || { echo "missing MinGW runtime $dll.dll under $MINGW/bin" >&2; exit 1; }
        done
        for lic in zlib bzip2 libiconv winpthreads; do
            f="$(ls "$MINGW/share/licenses/$lic"/* 2>/dev/null | head -1)"
            [ -n "$f" ] && cp "$f" "$PREFIX/licenses/mingw-$lic-LICENSE.txt"
        done
        ;;
esac

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
