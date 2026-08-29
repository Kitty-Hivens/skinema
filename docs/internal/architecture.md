# Architecture

This is the map. Each subsystem has its own page; this one shows how
they fit and the rules that shaped them.

## Modules

```
skinema-core      FFM bindings + demux/decode + pacing + VideoPlayer
                  + MediaWriter (encode/mux); emits RGBA frames with pts,
                  takes them back in; zero UI dependency
skinema-skiko     frame bytes and subtitle patches -> org.jetbrains.skia.Image
                  (one raster copy), under one borrow rule for both
skinema-compose   VideoSurface, rememberPlayerState, VideoScale
skinema-demo      the in-repo harness: run, harness, spike, seekbench, soak
skinema-natives   trimmed FFmpeg + libass, one classifier jar per tier
                  and platform
```

`skinema-core` is the whole engine and has no UI dependency, so it is
usable from any frontend. Compose Desktop renders through Skiko (Skia),
so targeting a Skia `Image` as the interchange type serves Compose for
free while keeping core renderer-agnostic. All FFmpeg access hides
behind the `Libav` facade, so the pure-Kotlin upper layers (pacer,
frame queue, clocks, rational math) compile and unit-test with no
natives present.

## The data path

One file, from disk to pixel:

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

With sound, a third thread runs the audio: it decodes, resamples to
stereo S16LE, and writes to the device. The device's consumed-sample
count *is* the clock the pacer waits against -- audio masters, video
follows. A fourth watches that thread: a write that never returns is a
device that died silently, and the watchdog hands the clock to wall time
so the picture keeps moving. A fifth, lazy thread runs subtitles when a
track is selected.

The decode thread never presents. Presentation lives entirely on the
pacer thread, so a stalled decode cannot stall the screen while
read-ahead inventory lasts. This split is the core of the runtime; see
[threading-and-clocks.md](threading-and-clocks.md).

## Where each concern lives

| Concern                         | Page                                                  |
|---------------------------------|-------------------------------------------------------|
| Panama bindings, ABI, loader    | [ffm-bindings.md](ffm-bindings.md)                    |
| Decoders, swscale, HDR, atempo  | [decoding.md](decoding.md)                            |
| Threads, queue, pacer, clocks   | [threading-and-clocks.md](threading-and-clocks.md)    |
| Native build, bundle, CI ship   | [natives-build.md](natives-build.md)                  |
| Tests, capability gates, doubles| [testing.md](testing.md)                              |

## Design rules

These are non-negotiable and explain choices that would otherwise look
arbitrary. The full reasoning is in `../ROADMAP.md` sections 2-10.

- **Fail closed, per file.** Any file the pipeline cannot handle: log,
  skip, surface `Failed`, let the consumer fall back. One code path, no
  heroics.
- **No network.** Local files only; the bundled FFmpeg is built
  `--disable-network`. A video library that physically cannot do I/O
  beyond the file given to it is a feature.
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
