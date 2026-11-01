# Architecture

This is the map. Each subsystem has its own page; this one shows how
they fit and the rules that shaped them.

## Modules

```
skinema-core          FFM bindings + demux/decode + pacing + VideoPlayer
                      + MediaWriter (encode/mux); emits RGBA frames with pts,
                      takes them back in; zero UI dependency. Multiplatform:
                      the desktop engine above, and a Media3 one on Android
skinema-skiko         frame bytes and subtitle patches -> org.jetbrains.skia.Image
                      (one raster copy), under one borrow rule for both;
                      desktop only
skinema-compose       VideoSurface, rememberPlayerState, VideoScale,
                      VideoFrameHolder; multiplatform like core
skinema-demo          the in-repo harness: run, harness, spike, seekbench, soak
skinema-demo-android  the device harness: one file with a timeline, on a phone
skinema-natives       trimmed FFmpeg + libass, one classifier jar per tier
                      and platform; desktop only
```

`skinema-core` is the whole engine and has no UI dependency, so it is
usable from any frontend. Compose Desktop renders through Skiko (Skia),
so targeting a Skia `Image` as the interchange type serves Compose for
free while keeping core renderer-agnostic. All FFmpeg access hides
behind the `Libav` facade, so the pure-Kotlin upper layers (pacer,
frame queue, clocks, rational math) compile and unit-test with no
natives present.

## Two engines behind one API

`skinema-core` and `skinema-compose` are Kotlin Multiplatform with two
targets: `jvm`, the desktop, and `android`. A source set shared by those two
alone, `jvmSharedMain`, holds the public API and every type that does not
depend on an engine: the states, tracks, chapters, the overlay, the PCM types,
the encode configs, the exceptions, the tone mapper and `Transcoder`, which is
written against the public surface and runs on both. Sharing it between two
JVM-family targets is what lets it keep JDK types such as `Path` and
`AutoCloseable`.

`VideoPlayer`, `VideoDecoder`, `AudioDecoder` and `MediaWriter` are `expect`
classes there, and the contract KDoc lives on the expect, worded for both
platforms, with each difference marked on the member it touches. The desktop
`actual` of each is the FFmpeg implementation this page describes. The
Android `actual` is the platform's own:

| Class          | Android engine                                                                                                                                                        |
|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `VideoPlayer`  | ExoPlayer on MediaCodec, rendered into a SurfaceTexture and drawn on the GPU into RGBA images the player owns (`FrameBridge`); image files paced by the player itself |
| `VideoDecoder` | the platform extractor and a MediaCodec decoder in buffer mode, YUV to RGBA on the CPU with the desktop's colour rules                                                |
| `AudioDecoder` | the platform extractor and a MediaCodec decoder                                                                                                                       |
| `MediaWriter`  | MediaCodec encoders and the platform muxer, with .flac and .wav written by the library                                                                                |

What the platform does not read, the library reads in Kotlin: chapters
(Matroska, MP4 and QuickTime) and the frames of animated GIF, APNG and WebP,
which are taken apart at the container level and decoded a frame at a time by
the platform. No native code ships for Android. The reasoning for Media3 over
an FFmpeg port, and for `expect` classes over a facade, is in ROADMAP M23.

## The data path

One file, from disk to pixel, on the desktop:

```
[decode thread]   av_read_frame -> avcodec_send_packet
                  -> avcodec_receive_frame (YUV)
                  -> sws_scale -> RGBA (-> tone-map if HDR)
                  -> FrameQueue cell (array reused, no per-frame alloc)

[pacer thread]    wait until the head cell's pts is due against the clock
                  -> swap its array into the mailbox writing slot
                  -> publish (TripleBuffer)

[consumer]        acquireFrame() reads the freshest published slot;
                  late frames were already dropped
```

With sound, a third thread runs the audio: it decodes, and writes to the
device in the shape the file has, walking down to one the device takes. The device's consumed-sample
count *is* the clock the pacer waits against -- audio masters, video
follows. A fourth watches that thread: a write that never returns is a
device that died silently, and the watchdog hands the clock to wall time
so the picture keeps moving. A fifth, lazy thread runs subtitles when a
track is selected.

The decode thread never presents. Presentation lives entirely on the
pacer thread, so a stalled decode cannot stall the screen while
read-ahead inventory lasts. This split is the core of the runtime; see
[threading-and-clocks.md](threading-and-clocks.md).

### Where the bytes come from

Each side above opens its own demuxer: the decode thread, the audio thread
and the subtitle thread each call `avformat_open_input` on the file, and each
reads and seeks at its own pace. A `MediaInput` decides how that holds for
bytes a consumer hands over (`ResolvedInput`, resolved once per open):

- a file: as above.
- a `MediaSource` that can seek: the same three demuxers, each through a
  cursor of its own over the one source (`SharedSource`), taking turns under
  a lock so calls into the source never overlap.
- a `MediaSource` that reads forward only: one demuxer on a thread of its own
  (`SharedDemux`), opened by whichever side asks first, routing each packet to
  the lane of the side that plays its stream. The decoders read a lane
  through the same entry points they read a demuxer through, and a reposition
  on a lane flushes and, for the picture, starts again from the newest
  keyframe. Read-ahead stops at about a second of each stream unless a side is
  starving.

On Android Media3 is already one demuxer, so a forward-only stream reaches it
through a tape that keeps the stream's start (`ForwardTape`), and the decoders
read one through `MediaExtractorCompat`. The decisions are in ROADMAP M24.

## Where each concern lives

| Concern                          | Page                                               |
|----------------------------------|----------------------------------------------------|
| Panama bindings, ABI, loader     | [ffm-bindings.md](ffm-bindings.md)                 |
| Decoders, swscale, HDR, atempo   | [decoding.md](decoding.md)                         |
| Encoders, muxing, transcode      | [encoding.md](encoding.md)                         |
| Threads, queue, pacer, clocks    | [threading-and-clocks.md](threading-and-clocks.md) |
| Native build, bundle, CI ship    | [natives-build.md](natives-build.md)               |
| Tests, capability gates, doubles | [testing.md](testing.md)                           |

## Design rules

These are non-negotiable and explain choices that would otherwise look
arbitrary. The full reasoning is in `../ROADMAP.md` sections 2-10.

- **Fail closed, per file.** Any file the pipeline cannot handle: log,
  skip, surface `Failed`, let the consumer fall back. One code path, no
  heroics.
- **No network.** Local files only; the bundled FFmpeg is built
  `--disable-network`. A video library that physically cannot do I/O
  beyond the file given to it is a feature. On Android the player wires a
  file data source alone, and turns off the two Media3 defaults that reach
  outside the app (platform diagnostics and the bandwidth meter).
- **Hand-written bindings.** jextract (here, a tiny C program against
  the pinned headers) is an offline oracle for struct layouts and
  constants, never a code generator wired into the build. The binding
  layer must stay small enough to read in one sitting -- a
  maintainability requirement, not a style preference.
- **Pinned natives.** Releases load the FFmpeg build we ship, by exact
  soname, never the system one. Development on Linux may use the system
  FFmpeg if it matches the pinned major.
- **Boring beats clever.** One memcpy per frame, buffers reused rather
  than allocated per frame, and no GPU interop in the frame path: GPU
  decode and encode exist, but the frames still come back through system
  memory and the RGBA contract is identical on every path. Zero-copy
  interop waits until a consumer actually needs it.
- **One clock, never two.** Pacing depends only on the `MediaClock`
  interface, and audio masters when present. The seam exists precisely so
  nothing inverts when sound is bolted on.

  What the rule is NOT is "video never moves the clock". It does -- at a
  lap and at the end of playback the decode thread places media time
  whoever supplies it. The rule is about WHEN rather than WHO: the clock
  is placed only where nothing is in flight on either side, and the decode
  thread picks those points because it is the one that knows when a lap or
  a landing is complete. The other arrangement was tried and could not
  describe a file whose track is shorter than its picture -- the sound
  wrapped the clock at its own end and the picture waited, so the timeline
  sawed back to zero with seconds of video still to run. See
  [threading-and-clocks.md](threading-and-clocks.md), which carries the
  full list of the points where time is placed.

## The consumer that shapes scope

The primary consumer is the Nexira launcher -- animated and video
backgrounds, later transparent overlays. A background renderer may
refuse a weird file; an editor may not. skinema is the former, which is
why the edge-case policy is "correctness for the common case, graceful
skip for the rest." The launcher adopts only published artifacts, so
API pressure comes from the in-repo `skinema-demo` harness rather than
from co-developing against a moving product.
