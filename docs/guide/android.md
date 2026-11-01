# Android

The same API runs on Android, on a different engine. On the desktop the
player decodes through FFmpeg on threads of its own. On Android it plays
through Media3 (ExoPlayer) on the platform's MediaCodec decoders, which
decode on the phone's video hardware at a fraction of the power a software
decode costs. Your shared code calls the same `VideoPlayer`, `VideoDecoder`,
`AudioDecoder`, `MediaWriter`, `Transcoder` and `VideoSurface` on both, and
this page lists where the answers differ.

## Dependencies

```kotlin
implementation("dev.hivens:skinema-compose:0.9.0")   // brings -core
```

The same coordinate as on the desktop: Gradle picks the Android variant from
the module metadata, in an Android module or in a multiplatform module's
Android source set. There is no natives jar to add, since nothing native is
shipped for Android. `skinema-skiko` is desktop only and is not pulled in.

| Floor   | Value                                                                           |
|---------|---------------------------------------------------------------------------------|
| minSdk  | 29 (Android 10)                                                                 |
| Engine  | Media3 1.11.1 (Apache-2.0), with the Guava and androidx libraries it depends on |
| Compose | Compose Multiplatform 1.12, or the androidx Compose it resolves to              |

A Maven, sbt or Bazel build of the desktop library names `skinema-core-jvm`
and `skinema-compose-jvm`: those tools read the root POM of a multiplatform
module, which carries no classes. Gradle needs nothing of that.

## No context to pass

The shared API takes no Android `Context`, so a player opens the same way in
shared code on both platforms. The library's manifest declares a provider
that records the application context when the app starts and does nothing
else: it answers no queries and stores nothing. An app that removes it from
its merged manifest calls `AppContext.install(context)` once, before the first
player.

## What leaves the phone

Nothing. The player is built on a file data source and the library's
`MediaSource` adapter only, so no network data source is wired and the
library needs no INTERNET permission. Two Media3 defaults are turned off where
the player is built, because both are on unless asked: platform diagnostics,
which report every playback session to the system's media metrics service
from Android 12 on, and the default bandwidth meter, which reads the
network's country and watches its type. Media3's manifests ask for
ACCESS_NETWORK_STATE and WAKE_LOCK for features this library never turns on,
and the library's manifest removes both from what Media3 brings. An app that
declares either itself keeps it.

## Frames

`acquireFrame` works as on the desktop: RGBA8888, straight alpha, tightly
packed. The frames are drawn on the GPU into images the player owns, and
`acquireFrame` copies one out.

Android adds `acquireFrameBitmap`, which wraps the same image as a hardware
`Bitmap` with no copy. The frame is borrowed until you close it: a consumer
that keeps one past the next frame holds a buffer the player cannot reuse,
and one that never closes them stops the picture once the player's five
images are out. `VideoFrameHolder` in `skinema-compose` is the shared way to
hold frames for drawing, and on Android it holds these.

`rotationDegrees` is always zero, because the platform applies the file's
orientation as it renders. The frames arrive already turned.

## Constructor parameters

| Parameter         | On Android                                                                                                                                                              |
|-------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `explicitClock`   | Refused: the player fails by name, since ExoPlayer takes no external clock                                                                                              |
| `readAheadFrames` | No effect: ExoPlayer buffers by its own policy                                                                                                                          |
| `hardware`        | `AUTO` is the one to want. `OFF` decodes in software, the expensive answer on a phone. `REQUIRE` fails when no hardware decoder takes the video, and for any image file |
| `sink`            | Supported, through an adapter that drives your `PcmSink` from a thread of its own                                                                                       |
| everything else   | As on the desktop                                                                                                                                                       |

## Images

A still (JPEG, PNG, WebP, GIF, BMP, HEIF, AVIF) or an animated GIF, APNG or
WebP plays through the same player. ExoPlayer plays none of them as video, so
the player paces their frames itself and draws each into the same images, and
`setSource` moves between images and video either way. A still reads `Ended`
unless it loops, and its clock stands at zero, where the desktop turns a
40 ms lap over and over. Turning `loop` off on a looping still ends it at
once, since a still is always at its end.

## Subtitles

Embedded tracks and side-loaded files work, through Media3's parsers, and the
overlay reaches `VideoSurface` or `acquireSubtitles` the same way. Three
differences:

- Text is laid out by the platform's text engine at the size and place the
  cue asks for. ASS styling goes as far as Media3's parser keeps (position,
  alignment, size), without libass's per-run fonts, karaoke or drawing.
- A side-loaded file is told by its extension (.srt, .ass, .ssa, .vtt,
  .ttml), and attaching it prepares the item again at the current position,
  so the sound pauses for as long as that takes. An image playing takes none.
- Closed captions (CEA-608/708) are not offered.

## Chapters

The platform reads none, so the library reads them itself: Matroska and
WebM, and MP4 and QuickTime chapter tracks or Nero lists. Other containers
report none on Android.

## Decoding without the player

`VideoDecoder` and `AudioDecoder` are the platform extractor and a MediaCodec
decoder, converting to RGBA on the CPU with the same colour rules the
desktop follows. HDR in PQ or HLG is tone-mapped to SDR from Android 13,
where the platform hands out ten-bit frames. A `MediaSource` has to be
seekable, since the platform extractor reads by offset. Closed-caption bytes
are not available. A 24-bit lossless source arrives as float, which carries
its 24 bits exactly.

## Writing

`MediaWriter` uses the device's MediaCodec encoders and the platform muxer.

| Extension          | Holds                                                  |
|--------------------|--------------------------------------------------------|
| mp4, m4a, m4v, mov | what the platform muxer takes, which varies by release |
| webm               | VP8 or VP9 video, Opus sound                           |
| ogg, opus          | Opus sound                                             |
| 3gp                | what the platform muxer takes                          |
| flac               | FLAC sound, written by the library                     |
| wav                | `pcm_s16le`, written by the library                    |
| mkv, mka           | refused by name: the platform does not write Matroska  |

Name a codec by its family (`h264`, `hevc`, `av1`, `vp9`, `vp8`, `aac`,
`opus`, `flac`) in shared code: each platform then picks its own encoder.
A desktop encoder name (`libx264`, `libsvtav1`, ...) is mapped onto its
codec. A codec the device has no encoder for is refused by name, and so is a
container that cannot hold the codec, before the output file is touched. A
codec the mp4 muxer turns down on that release is refused when the first
packet reaches it, and leaves no file. Encoder options and the VAAPI device
are ignored.

## Threads

Each player has a thread of its own for its commands and ExoPlayer, one for
the GPU frame path, and one for background work such as finding the frame
before the one shown. A `sink` adds a writer thread. Every call is safe from
any thread, as on the desktop.

## Not there yet

HDR through the player is not tone-mapped: ExoPlayer's frames reach the
images as they are drawn. The first frame after a switch between files of
different sizes is drawn at the old size for one frame, because ExoPlayer
releases it before it reports the new size.
