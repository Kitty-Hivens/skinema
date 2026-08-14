# Native build and shipping

How the trimmed FFmpeg gets built, packed and delivered. The build
script is `tools/build-natives.sh`; the delivery is two GitHub
workflows plus the `skinema-natives` module.

## What the bundle contains

A per-platform bundle carries a decode-only FFmpeg (LGPL, shared
libraries) plus libwebp, libass and their dependencies -- roughly 11-15
MB against ~70 MB for a full build. The FFmpeg `./configure` is
disable-everything plus an explicit whitelist;
`tools/build-natives.sh` is authoritative, but the shape is:

```
--disable-everything --disable-network --enable-shared --disable-static
--disable-programs --disable-doc --disable-avdevice
--enable-libvpx --enable-libdav1d
--enable-protocol=file,pipe
--enable-demuxer=mov,matroska,gif,apng,image2,image_png_pipe,image_webp_pipe,image_jpeg_pipe,
                 ogg,mp3,flac,wav,ac3,eac3,ass,srt,webvtt,sup
--enable-decoder=h264,hevc,vp8,vp9,libvpx_vp8,libvpx_vp9,libdav1d,av1,mjpeg,
                 png,apng,gif,webp,aac,mp3,opus,vorbis,flac,ac3,eac3,alac,
                 pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,
                 ass,ssa,srt,subrip,movtext,webvtt,pgssub,dvdsub
--enable-parser=h264,hevc,vp8,vp9,av1,mjpeg,png,webp,gif,aac,mpegaudio,
                opus,vorbis,flac,ac3
--enable-filter=atempo,abuffer,abuffersink
```

`--disable-network` is a load-bearing guarantee, not an optimization:
the shipped library physically cannot do I/O beyond the file given to
it. The avfilter trim is exactly the playback-rate chain (atempo plus
its buffer endpoints). The subtitle demuxers/decoders and the libass
stack arrived with the subtitle tier.

## Dependency strategy

With `STATIC_DEPS=1` (shipping mode) the script builds the dependencies
itself. The fold-vs-ship decision per library matters for licensing and
for Windows:

- **dav1d, libvpx** -- built static and folded into FFmpeg. (libvpx is
  required for the webm alpha path; the native vp8/vp9 decoders drop
  it.)
- **libwebp** -- built shared and shipped; the bindings load it at
  runtime on every OS (FFmpeg cannot decode animated WebP at all).
- **fribidi** -- built shared and shipped: it is LGPL and must not fold
  into the libass binary. The preload pattern resolves it.
- **freetype, harfbuzz** -- folded static into libass on Linux and
  macOS; built shared on Windows (see below).
- **libass** -- built shared (soname 9).

### The exclude-libs scoping (Linux)

On Linux, libass folds static freetype and harfbuzz but must keep their
`FT_*`/`hb_*` symbols private (anti-interposition against the system
fontconfig's own freetype). The script scopes that to the two archives:

```
ASS_LDFLAGS="-Wl,--exclude-libs,libfreetype.a:libharfbuzz.a"
```

The blunt `--exclude-libs,ALL` was the original choice and it localized
libass's own `ass_*` symbols too -- the bundle exported nothing and
every subtitle suite silently skipped. The scoped form is the fix.
Linux links the system fontconfig (universal on desktops); Windows uses
DirectWrite, macOS CoreText. libunibreak is deliberately off so a
system copy cannot become a silent NEEDED dependency.

### Windows is different

MinGW libtool refuses to fold a static archive into a DLL, so the
static freetype/harfbuzz fold that works on Linux/macOS leaves the
Windows libass DLL unbuildable. On Windows, freetype and harfbuzz are
built **shared** (their own DLLs, which the loader preloads), and the
MinGW runtime DLLs the bundle links -- `zlib1`, `libbz2-1`,
`libiconv-2`, `liblzma-5`, `libwinpthread-1` -- are copied into the
bundle and preloaded by name. These had been a latent gap since M3
(`liblzma-5`, avcodec's lzma path, surfaced later): a clean machine
without them on PATH could fail to load avcodec; the runner's PATH had
masked it. The script hard-fails if any is missing, so a broken bundle
never ships.

## Bundle layout

The script flattens everything into a jar-ready directory:

```
bundle/
  lib<name>.so.<major>        (Linux soname-level real files)
  lib<name>.<major>.dylib     (macOS)
  <name>-<major>.dll          (Windows, plus the MinGW runtime DLLs)
  licenses/                   (every shipped library's license text)
  index.txt                   (first line: content fingerprint; rest: sorted file list)
```

Files are the soname-level real files (symlinks dereferenced -- jars
cannot hold symlinks). `index.txt` exists because a jar cannot
enumerate its own resources: its first line is the first 16 hex chars
of the SHA-256 over all file contents in sorted order, and that
fingerprint keys the per-user unpack cache (see
[ffm-bindings.md](ffm-bindings.md)).

## The classifier jars

`skinema-natives` packs each bundle into a classifier jar named
`<tier>-<platform>` -- three tiers (`core`, `decode`, `full`) across eight
platforms (`linux-x64`, `linux-arm64`, `linux-musl-x64`, `linux-musl-arm64`,
`windows-x64`, `windows-arm64`, `macos-arm64`, `macos-x64`), 24 in all -- resources under
`dev/hivens/skinema/natives/<platform>/`. The layout is keyed by platform
alone, so `NativeBundle` stays tier-agnostic: it loads whichever bundle the
platform carries. The main jar is empty; the bundles attach as classifiers.
CI downloads the bundles from the rolling release; for local work, `jarLocal`
packs a bundle you built:

```
./gradlew :skinema-natives:jarLocal -Pplatform=linux-x64 -PbundleDir=<dir>
```

### Versioning

The module publishes on its own version line, `<ffmpeg>-<revision>` from
`nativesVersion` in `gradle.properties` (`9.0.1-1`): the FFmpeg build the
bundles carry, plus a revision for repacks of that same build. `nativesTag`
derives from its FFmpeg half, so the version and the rolling release it packs
from cannot drift.

The rule is one line: **the bundles change, the version bumps.** Central
versions are immutable, so a repack that ships different bytes under a version
already published is simply rejected -- and the library's own version cannot
carry the natives, because 24 bundles at ~211 MiB republished per library
release is what put the namespace over Maven Central's monthly size limit
(ROADMAP M17). A release that does not touch the bundles publishes none of
them.

## Delivery: two workflows

Delivery is **asynchronous by design**. Each platform uploads
independently to a rolling `natives-<ffmpeg version>` GitHub release the
moment it passes its on-runner acceptance suite. A queued or broken
platform delays only itself, never a release and never the other
platforms, and a rebuild replaces just its own asset.

- **natives.yml** (manual `workflow_dispatch`) builds the eight platforms
  across the three tiers -- 24 bundles -- with `fail-fast: false`. Linux
  x64/arm64, Windows x64 (MSYS2 MINGW64) and arm64 (MSYS2 CLANGARM64), and
  macOS-arm64 all build, run the acceptance suite on metal, and upload.
  macos-x64 is cross-compiled on the arm runner (GitHub's Intel macs queue
  for days to months) and ships **without** an on-metal test -- an arm JVM
  cannot load x86_64 dylibs. That is exactly what community-tier support
  means, and it is the only platform in that boat: both arm64 targets are
  first-class, because GitHub's free `ubuntu-24.04-arm` and `windows-11-arm`
  runners are real machines. The matrix is generated in a prepare job, so a
  dispatch can narrow to one platform or tier (`only_platform`/`only_tier`).
- **build.yml** (push and PR) runs the test matrix on four platforms,
  downloads OUR release bundles (not BtbN or brew), points
  `SKINEMA_LIBAV_DIR` at them, and runs `./gradlew build`. On Windows it
  strips mingw/msys from PATH first, so a green run proves the bundle is
  self-contained rather than leaning on the toolchain.

Both set `SKINEMA_REQUIRE_CAPS` per row so a missing capability fails
loudly instead of skipping green (see [testing.md](testing.md)).

## The push-sequencing rule

This is the one operational trap that bites every time the native
surface grows. When the bindings pin a **new** library soname (for
example libavfilter, added for the rate filter) or enable a new decoder
that changes the bundle, the order is mandatory:

1. Push the `build-natives.sh` change.
2. Dispatch `natives.yml` and wait for **every** affected asset on the
   rolling release to be replaced -- all 18 for a change that touches every
   tier and platform (check each asset's `updated_at`).
3. Only then push the consuming code.

Push the code first and `build.yml` downloads the old bundles, which
lack the new library or codec, and the matrix goes red on every
platform until the natives catch up. Local development is fine
throughout (the system FFmpeg has everything); only CI and shipped
consumers see the gap.

## Bumping the FFmpeg pin

A pin bump is a coordinated change: re-run the layout oracle and update
`LibavAbi.kt` ([ffm-bindings.md](ffm-bindings.md)), update the soname
majors in `LibavLibrary`, rebuild the natives on every platform, and
land it as a PR where CI runs the integration suite against the new
build on every OS. Struct offset tables are per-major; nothing about a
pin bump is local.
