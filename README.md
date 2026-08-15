# skinema

Video decode, encode and playback for JVM desktop apps. FFmpeg through
hand-written Java FFM (Panama) bindings: frames out as raw RGBA or Skia
images with a Compose Desktop surface on top, and frames back in to a
muxed file. No JNI wrapper stacks, no embedded player engines. The bundled
FFmpeg is built with `--disable-network`, so skinema performs no network
I/O of its own -- it works on the file you hand it (or the bytes a
`MediaSource` feeds it) and nothing more.

```kotlin
val player = VideoPlayer(Path.of("background.webm"), loop = true)

// Compose Desktop:
VideoSurface(player, Modifier.fillMaxSize(), scale = VideoScale.Cover)

// Or poll frames yourself from any render loop:
player.acquireFrame()?.let { frame -> /* frame.rgba, frame.width, ... */ }
```

`positionNanos()` and `durationNanos` carry a timeline (duration is
null for animated webp, which declares none); `chapters`, `tags` and
`coverArt` carry the rest of the container's metadata -- the cover
ships as the stored png/jpeg bytes for your own image stack. A file
whose only video stream is the embedded cover plays frameless, art
exposed, sound intact. Phone footage's orientation surfaces as
`rotationDegrees`; `VideoSurface` applies it, a consumer drawing
frames itself must do the same.

Full developer documentation -- a consumer guide and the contributor
internals -- lives in [docs/](docs/README.md).

## Dependencies

```kotlin
implementation("dev.hivens:skinema-compose:0.8.0")   // brings -core and -skiko
runtimeOnly("dev.hivens:skinema-natives:9.0.1-1:decode-linux-x64")
runtimeOnly("dev.hivens:skinema-natives:9.0.1-1:decode-linux-arm64")
runtimeOnly("dev.hivens:skinema-natives:9.0.1-1:decode-linux-musl-x64")
runtimeOnly("dev.hivens:skinema-natives:9.0.1-1:decode-linux-musl-arm64")
runtimeOnly("dev.hivens:skinema-natives:9.0.1-1:decode-windows-x64")
runtimeOnly("dev.hivens:skinema-natives:9.0.1-1:decode-windows-arm64")
runtimeOnly("dev.hivens:skinema-natives:9.0.1-1:decode-macos-arm64")
runtimeOnly("dev.hivens:skinema-natives:9.0.1-1:decode-macos-x64")
```

The two versions are independent, and that is deliberate. `skinema-natives`
is versioned as the FFmpeg build it carries plus a repack revision
(`<ffmpeg>-<revision>`), so it moves only when the bundles themselves change
-- most library releases leave it untouched. Take the natives version the
release notes name and pair it with any library version that names it.

Independent, but not unrelated: the bindings are valid for one set of FFmpeg
soname majors, so a library release that moves the pin needs the natives that
carry it. Gradle consumers do not have to track that by hand -- `skinema-core`
publishes a constraint naming the natives version it was built against, so a
stale pin is raised to it rather than failing at runtime. Override with
`version { strictly("...") }` to hold a specific bundle (a local build, a
repack under test); the runtime refuses a mismatched major either way.

The natives classifier is `<tier>-<platform>`: pick one tier for the
platforms you target. The tier sets what the bundle carries -- and its
license:

| Tier     | Carries                                                            | License |
|----------|-------------------------------------------------------------------|---------|
| `core`   | the modern essentials (H.264/HEVC/VP8/VP9/AV1, mainstream audio, images), no subtitles, no GPU decode | LGPL |
| `decode` | core + GPU decode + subtitles + the broad legacy/extended format set; on Linux also GPU H.264/HEVC encode, AAC/FLAC encode and the mp4/mkv/webm muxers | LGPL |
| `full`   | decode + H.264/HEVC software encode                                | GPL     |

`core` is the lean tier -- the modern codecs only, no subtitles, no GPU
decode -- for apps that just play current video. It is also the portable one:
it needs nothing from the host but the C library, so it loads in a bare
container and on a distribution that keeps no libraries at the usual paths.
The desktop tiers carry GPU decode, which means they need libva alongside
fontconfig. `decode` (used above) is the complete LGPL
player: it adds the libass subtitle stack and the broad `formats` set (the
legacy and broadcast containers and codecs in "What it plays" below). `full`
adds software encode and is GPL because it bundles x264/x265; HEVC encode
ships everywhere since the x265 source patch (issue #22). On first use the jars unpack to a per-user cache. Without a
natives jar, skinema looks for matching system libraries -- fine for
development, not what you ship.

On JDK 24+ launch with `--enable-native-access=ALL-UNNAMED` (or grant
your named module): skinema's FFM calls -- and Skiko's, if you use
Compose -- are restricted methods that otherwise warn on every run.

## Platforms

Every tier ships for eight platforms -- x64 and arm64 on all three desktop
operating systems, and both C libraries on Linux:

| Platform        | Bundle built                 | Acceptance suite |
|-----------------|------------------------------|------------------|
| `linux-x64`     | on metal                     | on metal         |
| `linux-arm64`   | on metal                     | on metal         |
| `linux-musl-x64`| in Alpine, on metal          | on metal, musl JVM |
| `linux-musl-arm64` | in Alpine, on metal       | on metal, musl JVM |
| `windows-x64`   | on metal (MSYS2 MINGW64)     | on metal         |
| `windows-arm64` | on metal (MSYS2 CLANGARM64)  | on metal         |
| `macos-arm64`   | on metal                     | on metal         |
| `macos-x64`     | cross-compiled on an arm mac | none             |

Linux ships twice because a glibc shared object cannot load into a musl
process at all -- Alpine and Void-musl take the `linux-musl-*` bundles, and
`nativesPlatform()` picks between them by reading which C library the running
JVM is itself linked against, not by looking at what the system has installed.

arm64 is first-class here, not an afterthought: `linux-arm64` and
`windows-arm64` are built on real arm machines and must pass the same
acceptance suite as their x64 siblings before a bundle uploads. `macos-x64`
is the one exception, and it runs the other way -- Intel mac runners are
scarce, so it cross-compiles on an arm mac and ships without an on-metal
run, because an arm JVM cannot load x86_64 dylibs. That is what
community-tier support means here.

**How old a system the bundles still load on.** glibc 2.39 for `linux-arm64`
`full`, 2.38 for the other glibc bundles, 2.35 for `core-linux-x64` -- so
Ubuntu 24.04, Debian 13 and Fedora 39 upward for the desktop tiers, and not
RHEL 9. `core-linux-x64` alone still loads on Ubuntu 22.04.
macOS bundles are built for 14.0, so Sonoma upward. The `linux-musl-*`
bundles have no such floor: musl does not version its symbols. The `decode`
and `full` Linux bundles link `libva`, `libva-drm` and `libfontconfig` from
the host and load none of their libraries without them, which is worth
knowing before putting them in a headless container -- `core` needs only
libc and libm and has no such requirement.

## What it plays

|                 |                                                                                               |
|-----------------|-----------------------------------------------------------------------------------------------|
| Containers      | mp4/mov/m4a, webm/mkv, avi, MPEG-PS/TS, flv, asf/wmv, dv, RealMedia, ogg, mp3, flac, wav, gif, apng, webp, raw ac3/eac3 |
| Video           | H.264, HEVC, H.266/VVC, VP8, VP9 (incl. webm alpha), AV1; MPEG-1/2, MPEG-4 Part 2, VC-1, WMV 7-9, H.263, Theora, ProRes, DNxHD, FFV1, RealVideo, Cinepak, Indeo, VP6; MJPEG |
| Animated images | GIF, APNG, animated WebP                                                                      |
| Audio           | AAC, AC-3/E-AC-3, DTS, TrueHD, ALAC, Opus, Vorbis, MP1/MP2/MP3, FLAC, WMA (v1/v2/Pro), AMR, WavPack, Monkey's Audio, TTA, ADPCM, G.72x, RealAudio, ATRAC, GSM, WAV PCM -- the device clock masters A/V sync |
| Subtitles       | ASS/SSA, SRT, mov_text, WebVTT (libass-rendered); PGS, VobSub (bitmap); external .srt/.ass   |
| Pixels out      | RGBA8888, straight alpha, exact-pts pacing, BT.601/709/2020 matrix and range honored, PQ/HLG tone-mapped to SDR |

The legacy and broadcast formats (avi/MPEG-TS/flv/asf/dv/RealMedia and the older codecs) ride the `decode` and `full` tiers; the lean `core` tier carries only the modern essentials (H.264/HEVC/VP8/VP9/AV1 and the mainstream audio). H.266/VVC decodes through FFmpeg's native decoder (CPU-only, no GPU path yet).

Audio: pass `audio = true` to `VideoPlayer` -- aac, ac3/eac3, alac,
opus, vorbis, mp3, flac and WAV pcm (16/24/32-bit and float) decode
through the same bindings, multichannel downmixes to stereo, and the
audio device becomes the player's clock (video follows sound, never the
reverse). Audio-only files play frameless. Files with several audio
tracks expose them (`audioTracks`, language and title included) and
switch in place (`selectAudioTrack`) -- the picture keeps playing and
the sound re-anchors at the playhead. `setRate` plays at 0.5x-4x with
the pitch preserved (FFmpeg's atempo). HDR content (PQ/HLG over
BT.2020) is tone-mapped to SDR on the decode path -- the transfer is
inverted, highlights roll off against BT.2408 diffuse white, and the
gamut maps to BT.709 -- so it no longer plays washed out. This is a
fixed reasonable-look mapping, not a metadata-accurate one: the stream's
HDR metadata (MaxCLL, mastering-display luminance, HDR10+, Dolby Vision)
is not read, so the roll-off uses an assumed peak rather than the
content's. Native-HDR passthrough (driving an HDR display) is out of scope.

Subtitles: `subtitleTracks` enumerates what the container carries
(language, title, default/forced); `selectSubtitleTrack` turns one on
-- off by default, nothing subtitle-related runs until then. Text
tracks (ASS/SSA, SRT, mov_text, WebVTT) render through libass with the
full typesetting, mkv-embedded fonts included; bitmap tracks (PGS,
VobSub) decode to pixels and need no libass at all. External `.srt` and
`.ass` files join via `addExternalSubtitles` on the same timeline.
`VideoSurface` composites the overlay and keeps glyphs crisp at window
size; a consumer drawing frames itself polls `acquireSubtitles` -- the
`acquireFrame` contract for text. libass is an optional capability:
without it text tracks refuse selection and everything else plays on.
Text rendering uses the system's fonts (fontconfig, DirectWrite,
CoreText); a fontless headless box renders blank overlays.

## Behavior contract

- **Fail closed.** A file the pipeline cannot handle surfaces as
  `VideoPlayer.State.Failed` -- show your fallback. No partial recovery,
  no garbage frames, no hangs.
- **Drop late.** A slow consumer skips frames; the clock never lags.
- **Seeks answer immediately.** An exact seek previews its keyframe
  while the frame-precise landing decodes behind it; `exact = false`
  lands on the keyframe outright -- picture and sound at once, position
  as coarse as the file's keyframe spacing. Skip buttons want inexact;
  timeline scrubbing wants exact. `stepForward`/`stepBackward` move a
  single frame and leave the player paused on it.
- **Two threads per player** (a third with audio): decode fills a small
  frame queue, a pacer presents from it. Players are independent and
  self-synced; play as many as your CPU affords (a desktop comfortably
  runs dozens of 1080p30 streams).
- **Read-ahead is opt-in.** `readAheadFrames` (default 1) holds that
  many decoded frames of inventory, so a decode stall does not stall
  the screen while inventory lasts. Each step of depth costs one full
  RGBA frame of memory -- 8.3 MB at 1080p. Backgrounds stay at 1; a
  player UI wants 3-5.

## Modules

| Module            | Contents                                                | Floor                            |
|-------------------|---------------------------------------------------------|----------------------------------|
| `skinema-core`    | FFM bindings, demux/decode, pacing, `VideoPlayer`       | JDK 22                           |
| `skinema-skiko`   | `VideoFrameImage`: frames as `org.jetbrains.skia.Image` | Skiko (provided by your Compose) |
| `skinema-compose` | `VideoSurface`, `rememberPlayerState`, `VideoScale`     | Compose Desktop                  |
| `skinema-natives` | trimmed FFmpeg in tiers, classifier jar per tier+platform| --                               |

ROADMAP.md is the project's working memory: every architectural
decision, with its reasoning, lives there.

## Compatibility policy

Decode correctness for well-formed files in the formats above is a bug
when broken. Exotic, damaged or adversarial files get the fail-closed
treatment by design; compatibility issues beyond that are triaged at the
maintainer's discretion.

## License

skinema itself (core, skiko, compose) is Apache-2.0. The natives bundle's
license is set by its tier (see Dependencies): the `core` and `decode` tiers
carry no encoders and are LGPL; the `full` tier adds the x264/x265 software
encoders, which are GPL, so its FFmpeg build is configured `--enable-gpl` and
its libraries are GPL. Either way the natives ride in separate per-tier,
per-platform classifier jars, dynamically linked, never statically embedded
into the Apache code, and every bundle ships its license texts (the GPL text
on `full`, LGPL on the rest). An application that distributes the `full`
natives takes on FFmpeg's GPL obligations, as anyone distributing a GPL
FFmpeg build does; a consumer that needs to stay LGPL takes `core` or
`decode`. skinema's own Apache code is unaffected either way.

libwebp, libvpx and dav1d are BSD-family; x264 and x265 are GPL (the reason
the `full` build is `--enable-gpl`). libass (ISC) ships with FreeType and
HarfBuzz folded in
-- portions of the bundled software are copyright The FreeType Project
(freetype.org), licensed under the FreeType License -- while FriBidi stays a
separate shared library precisely because it is LGPL. On Windows, where
libtool cannot fold a static archive into a DLL, FreeType and HarfBuzz ship
as their own DLLs instead, and the bundle also carries the MinGW runtime
they link -- zlib, bzip2, and lzma (permissive), winpthread, and libiconv
(LGPL, dynamically linked) -- each with its license text.
