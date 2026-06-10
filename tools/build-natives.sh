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
#   STATIC_DEPS=1   build libvpx + dav1d from source, statically linked in
#   VPX_VERSION     libvpx tag for STATIC_DEPS (default v1.15.2)
#   VPX_TARGET      libvpx configure --target (needed under MSYS2: x86_64-win64-gcc)
#   DAV1D_VERSION   dav1d tag for STATIC_DEPS (default 1.5.1)
#   EXTRA_FLAGS     appended to ffmpeg ./configure (cross builds etc.)
#   JOBS            parallel make (default nproc)
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-8.1.1}"
VPX_VERSION="${VPX_VERSION:-v1.15.2}"
DAV1D_VERSION="${DAV1D_VERSION:-1.5.1}"
JOBS="${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu)}"
PREFIX="$(cd "$(dirname "${1:-natives-out}")" 2>/dev/null && pwd)/$(basename "${1:-natives-out}")"
WORK="${WORK:-/tmp/skinema-natives}"

mkdir -p "$WORK" "$PREFIX"
cd "$WORK"

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
            -Denable_tools=false -Denable_tests=false
        ninja -C "dav1d-$DAV1D_VERSION/build" install
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
fi

if [ ! -d "ffmpeg-$FFMPEG_VERSION" ]; then
    fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" ffmpeg.tar.xz
    tar -xJf ffmpeg.tar.xz
fi

cd "ffmpeg-$FFMPEG_VERSION"

# Decode whitelist (ROADMAP.md section 4). Demuxers cover the consumer's
# container set plus standalone audio for M5; native opus/vorbis/aac/mp3/
# flac decoders need no external libraries. libvpx is required, not a
# nicety: the native vp8/vp9 decoders drop the webm alpha side-channel.
./configure \
    --prefix="$PREFIX" \
    --enable-shared --disable-static \
    --disable-programs --disable-doc --disable-debug \
    --disable-everything --disable-network \
    --disable-avdevice --disable-avfilter \
    --enable-libvpx --enable-libdav1d \
    --enable-protocol=file,pipe \
    --enable-demuxer=mov,matroska,gif,apng,image2,png_pipe,webp_pipe,jpeg_pipe,ogg,mp3,flac,wav \
    --enable-decoder=h264,hevc,vp8,vp9,libvpx_vp8,libvpx_vp9,libdav1d,av1,mjpeg,png,apng,gif,webp,aac,mp3,opus,vorbis,flac,pcm_s16le \
    --enable-parser=h264,hevc,vp8,vp9,av1,mjpeg,png,webp,gif,aac,mpegaudio,opus,vorbis,flac \
    ${EXTRA_FLAGS:-}

make -j"$JOBS"
make install

# LGPL compliance travels with the binaries: license texts ship inside
# every native bundle, and the exact source is pinned by FFMPEG_VERSION.
mkdir -p "$PREFIX/licenses"
cp COPYING.LGPLv2.1 LICENSE.md "$PREFIX/licenses/"
if [ "${STATIC_DEPS:-}" = "1" ]; then
    cp "$WORK/dav1d-$DAV1D_VERSION/COPYING" "$PREFIX/licenses/dav1d-COPYING"
    cp "$WORK/libvpx-${VPX_VERSION#v}/LICENSE" "$PREFIX/licenses/libvpx-LICENSE"
fi

echo "== installed libraries =="
du -sh "$PREFIX"/lib* 2>/dev/null || true
ls -la "$PREFIX"/lib/*.so* "$PREFIX"/lib/*.dylib "$PREFIX"/bin/*.dll 2>/dev/null | grep -v '\->' || true
