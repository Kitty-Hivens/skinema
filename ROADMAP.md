# skinema -- roadmap and working memory

This file is the project's memory. Decisions land here together with their
reasoning so work can resume after any pause without re-deriving them. When
a decision changes, edit the entry and say why -- do not silently rewrite.

## 1. Why this exists

The JVM desktop has no usable video story. JavaFX Media supports a handful
of formats and interoperates badly with Compose; vlcj drags a full libvlc
install; JavaCV is a heavyweight JNI wrapper stack; Compose Multiplatform
ships no desktop video component at all. skinema fills that gap with the
smallest honest stack: FFmpeg for decoding (the only realistic cross-format
engine), hand-written FFM bindings (no JNI, no generated monster), Skia
images out, a composable on top.

Primary consumer: the Nexira launcher -- animated and video backgrounds,
later transparent video overlays for its widget system. The consumer shapes
every scope call below: a background renderer may refuse a weird file; an
editor may not. We are the former.

## 2. Ground rules

- **Consumer-shaped, product-clean.** The API grows under consumer-shaped
  pressure from a harness inside this repo (the demo grown into a
  background testbed), never against imagination -- but the primary
  consumer adopts only published artifacts once the hard problem is
  solved end-to-end, natives included. The product is not a test bench,
  and a half-solved library is worth less than none.
- **Fail closed per file.** Any file the pipeline cannot handle: log, skip,
  let the consumer fall back (static image). One code path, no heroics.
- **No network.** Local files only; the pinned FFmpeg builds are configured
  with `--disable-network`. A video library that physically cannot perform
  I/O beyond the file given to it is a feature, not a limitation.
- **Hand-written bindings.** jextract is an offline oracle for struct
  layouts and constants, never a code generator wired into the build.
- **Pinned natives.** Releases load the FFmpeg build we ship, never the
  system one. Development on Linux may use the system FFmpeg if it matches
  the pinned major.
- **Boring beats clever.** Software decode, one memcpy per frame, no GPU
  interop until a consumer actually needs it.

## 3. Architecture

```
skinema-core      FFM bindings + demux/decode loop + pacing primitives
                  emits VideoFrame(buffer RGBA, width, height, ptsNanos)
                  zero UI dependencies -- usable from any frontend
skinema-skiko     VideoFrame -> org.jetbrains.skia.Image (one memcpy)
skinema-compose   VideoSurface composable, frame clock pacing
```

Compose Desktop renders through Skiko (Skia); targeting skia `Image` as the
interchange type serves Compose for free while keeping core renderer-
agnostic. All FFmpeg access hides behind a `Libav` facade so the pure-Kotlin
upper layers (pacer, frame queue, rational math) compile and test without
natives.

Timing model: FFmpeg owns no clock. The decode thread produces frames with
their pts; the UI side asks "which frame should be visible now" against a
monotonic clock (Compose: `withFrameNanos`). Late frames are dropped, never
shown. The pacer is pure Kotlin and unit-tested.

Audio (designed now, built later): pacing depends only on the `MediaClock`
interface. Silent playback runs on the wall-time `PlaybackClock`; when a
player gains sound, its audio sink becomes the clock -- a DAC consumes
samples at its own immutable rate, so audio masters and video follows,
never the reverse. Bolting sound onto a video API built around a wall
clock is how players end up with two competing clocks; the seam exists
precisely so nothing inverts later. Each player owns its sink and syncs
itself; simultaneous sources stay independent because the OS audio server
(PipeWire / WASAPI / CoreAudio) mixes client streams -- there is
deliberately no global media clock and no in-process mixer. Volume policy
and ducking across sources are the consumer's domain. swresample and the
audio decoders sit in the pin and the trimmed-build whitelist for exactly
this.

## 4. FFmpeg pin

Pinned line: **FFmpeg n9.0.x, LGPL, shared** (pinned 2026-08; n8.1.x before it).
The n8.1 -> n9.0 move changed no struct offset the bindings read -- only the
six sonames and `AV_CODEC_ID_VP9`, both re-captured with the oracle.

| Library     | soname major (n9.0) |
|-------------|---------------------|
| avutil      | 61                  |
| swresample  | 7                   |
| swscale     | 10                  |
| avcodec     | 63                  |
| avformat    | 63                  |
| avfilter    | 12                  |

Rules:

- Load by exact soname (`libavformat.so.63`), never the bare name -- majors
  coexist on real systems and the unversioned symlink belongs to -dev
  packages. The table lives in `LibavLibrary` with a matching test.
- `avformat_version()` (and siblings) are checked right after load; a major
  mismatch is a clean refusal, not a compatibility dance.
- Struct offset tables are per-major. Bumping the pin = a PR where CI runs
  the integration suite against the new build on every OS.

Binary sources, by stage:

| Stage    | Linux              | Windows            | macOS              |
|----------|--------------------|--------------------|--------------------|
| Spike    | system FFmpeg 8.1  | --                 | --                 |
| v0.1     | BtbN n8.1 lgpl-shared | BtbN n8.1 lgpl-shared | skipped       |
| Endgame  | own trimmed CI build | own trimmed CI build | own trimmed CI build |

BtbN (github.com/BtbN/FFmpeg-Builds) does not build macOS; Homebrew dylibs
drag a dependency tree and are not redistributable as-is. macOS support
therefore arrives together with our own trimmed builds.

Trimmed build configure baseline: `--disable-everything --enable-shared
--disable-programs --disable-network`, then a whitelist (the script,
tools/build-natives.sh, is authoritative): demuxers mov/mp4,
matroska/webm, gif, apng, image2 and the still pipes, plus the
standalone-audio set (ogg, mp3, flac, wav, ac3, eac3); video decoders
h264, hevc, vp8, vp9, av1 (libdav1d -- the native decoder is too slow
for 1080p), mjpeg, png, webp; audio decoders aac, ac3/eac3, alac, opus,
vorbis, mp3, flac and WAV pcm (s16/s24/s32/float -- the real-life set
added 2026-06-11: movie-rip tracks, m4a lossless, DAW exports);
libswscale + libswresample; libavfilter trimmed to exactly the
playback-rate chain (atempo + abuffer/abuffersink, added in M8);
subtitle demuxers (ass, srt, webvtt, sup) and decoders (ass/ssa,
srt/subrip, mov_text, webvtt, pgssub, dvdsub) since M9. The M9 bundles
also carry libass (shared, soname 9; static freetype + harfbuzz folded
in, symbols hidden) and fribidi (shared -- LGPL must not fold into the
libass binary; the webp-pair preload pattern resolves it). Linux links
the system fontconfig (universal on desktops); Windows uses DirectWrite,
macOS CoreText. libunibreak is deliberately off -- optional, and a
system copy must not become a silent dependency. Expected size: 11-15 MB
per platform against ~70 MB for a full build.

Modular tiers (M16, 2026-06): the whitelist above is the union, not a
single fixed bundle. `tools/build-natives.sh` assembles it from a FEATURES
set -- each feature gates both its source-built dependency and its slice of
the demuxer/decoder/parser/encoder/muxer list -- so one script produces
three curated tiers, because some support (the libass stack above all)
weighs a lot and not every consumer wants it. The always-on base is the
core playback set (H.264/HEVC, the native audio decoders, the still images,
the containers, the atempo chain); each tier layers features on:

| Tier     | Features over the base                                            | License |
|----------|-------------------------------------------------------------------|---------|
| `core`   | av1 vpx webp                                                      | LGPL    |
| `decode` | + hwaccel + subs (libass) + formats + enc-vaapi enc-av1 enc-opus  | LGPL    |
| `full`   | + enc-h264 enc-hevc (x264/x265)                                   | GPL     |

Three of the five encoder features carry no GPL surface, so they ride
`decode` as well as `full`. `enc-vaapi` (M13) is the hardware H.264/HEVC
encoder, whose codec lives in the GPU driver; it is Linux/VAAPI only and a
no-op elsewhere. `enc-av1` (SVT-AV1) and `enc-opus` (libopus) are BSD
software encoders and run everywhere. So the LGPL tier can WRITE -- AV1
video, Opus/AAC/FLAC audio, and H.264/HEVC through the GPU on Linux -- and
what the GPL tier buys is specifically SOFTWARE H.264 and HEVC.

The `formats` feature is the broad legacy/extended decode set: the avi,
MPEG-PS/TS, flv, asf, dv and RealMedia demuxers, and the older video (MPEG-1/2,
MPEG-4 Part 2, VC-1/WMV, H.263, Theora, ProRes, DNxHD, FFV1, RealVideo,
Cinepak, Indeo, VP6, ...) and audio (DTS, TrueHD, WMA, MP1/2, AMR, WavPack,
APE, TTA, ADPCM/G.72x, RealAudio, ATRAC, GSM, ...) decoders, plus H.266/VVC.
All native FFmpeg components, so it adds no external library and stays LGPL;
it rides decode/full and is left out of the lean core tier (the policy in #10
generalized -- broad support is cheap once the lean consumer can opt out).

A bundle stays LGPL until `enc-h264` or `enc-hevc` pulls in GPL x264/x265, so
`core` and `decode` are LGPL and only `full` is GPL -- the LGPL path is a
first-class tier now, for writing as well as reading, not "ship your own
FFmpeg". enc-hevc (x265) ships on every platform since the source patch that
answered issue #22 (x265 4.1 sets pre-3.5 cmake policies to OLD, which cmake
4.x refuses; the build rewrites those lines rather than pinning an old cmake
into three CI images). CI builds the 3 x 8 tier-by-platform matrix; the
natives module publishes each as the classifier `<tier>-<platform>`.

## 5. Bindings

Surface: roughly 30 functions -- avformat_open_input,
avformat_find_stream_info, av_find_best_stream, av_read_frame,
av_seek_frame, avformat_close_input; avcodec_find_decoder,
avcodec_alloc_context3, avcodec_parameters_to_context, avcodec_open2,
avcodec_send_packet, avcodec_receive_frame, the alloc/unref/free family for
AVPacket and AVFrame; sws_getContext/sws_scale/sws_freeContext; av_strerror,
av_dict helpers, av_log_set_level. Downcall handles only; no upcalls in
v0.1 (no custom AVIO, no log callback).

Struct access is minimized: everything reachable through a function goes
through the function (`avcodec_parameters_to_context` instead of reading
codecpar fields). Direct offsets are limited to the unavoidable set:
`AVFrame.data/linesize/pts`, `AVFormatContext.streams/nb_streams`,
`AVStream.time_base`. Offsets come from a one-off jextract pass over the
pinned major's headers and are committed as a per-major table.

Memory discipline: one confined Arena per decode session, owned by the
decode thread, used only for out-parameters, strings and dicts. Everything
FFmpeg allocates is released through the matching av_*_free function, never
through the Arena.

Known traps (each verified the hard way elsewhere; do not rediscover):

1. `AVERROR(x)` is negative errno and **errno values differ per OS** --
   EAGAIN is 11 on Linux and 35 on macOS. Error constants must be
   platform-dependent or the receive loop never terminates on macOS.
2. FFmpeg logs to stderr by default; call `av_log_set_level(QUIET)` at init
   instead of wiring an upcall.
3. FFM pointer dereferences need `.reinterpret(size)` -- a raw pointer read
   yields a zero-length segment and an IndexOutOfBoundsException on first
   access.
4. `MethodHandle.invoke`, not `invokeExact`, and that is measured rather
   than assumed. `invokeExact` needs every call site's static types to match
   its handle exactly, and a mismatch is a `WrongMethodTypeException` at
   runtime rather than a compile error -- across two hundred bindings. What
   it buys here is nothing: 50M calls of a trivial native function came out
   at 9 to 13 ns per call either way, the difference inside the run-to-run
   noise and negative in two of three rounds. Nothing here calls libav per
   pixel; the hot paths run per packet and per frame, a few thousand calls a
   second, each wrapping orders of magnitude more native work than the call
   overhead.

## 6. Frame pipeline

```
decode thread:  av_read_frame -> send_packet -> receive_frame (YUV)
                -> sws_scale into a queue cell (RGBA, reused)
pacer thread:   wait until the head cell's pts is due -> swap its array
                into the mailbox's writing slot -> publish
UI side:        latest-frame mailbox; acquire the freshest; drop late
```

- Decoded video is YUV; swscale converts to RGBA at one chokepoint. The
  whole format zoo collapses there.
- 1080p RGBA is ~8 MB/frame, ~250 MB/s at 30 fps -- trivial memcpy
  bandwidth, but **buffers must be reused** (double/triple buffering);
  allocating per frame churns the GC.
- On the Skiko side every `Image` must be closed BY THE LIBRARY -- Skia
  objects hold native memory and waiting for the finalizer is a leak in
  practice -- and the only question is when. Not when it is replaced: the
  drawing thread may still be painting with it, and freeing it there is a
  native crash rather than a wrong picture. When nothing can name it any
  more, which is one live borrow per side (M19).
- Variable frame rate is normal, not an edge case: pacing is by pts, never
  by an assumed 1/fps step.

## 7. v0.1 scope

In: local files; play, loop, seek-to-frame; RGBA software pipeline; alpha preserved where the codec carries it (VP9/webm);
GIF / APNG / animated WebP through the same pipeline (covers the consumer's
"animated background" category for free).

Out (explicitly, revisit only with a consumer in hand): ~~hardware decode
and GPU YUV shaders~~ (hardware decode landed in M11; zero-copy GPU->Skia
interop still deferred); network sources and custom AVIO; Dolby Vision
profile 5 (proprietary colorspace -- out of scope forever unless
licensing changes).

Audio is not in v0.1 but is no longer indefinite: the clock seam is in
place (section 3) and audio lands as milestone M5 with its own consumer,
at an unhurried pace.

Animated WebP is the one format FFmpeg cannot decode (never merged
upstream; even a full build refuses files its own encoder produced), so
skinema covers it with a second tiny FFM binding: libwebp's
WebPAnimDecoder (`dev.hivens.skinema.webp`, ~7 downcalls, its own oracle
at tools/webp-oracle.c). FrameSources routes RIFF/WEBP there when
libwebpdemux is loadable -- frames arrive as RGBA with millisecond
timestamps, no swscale -- and falls back to libav otherwise (stills
decode, animations fail closed). The capability is optional by design:
the trimmed bundles will carry libwebp + libwebpdemux; until then,
system copies serve. The animated-background category is therefore
GIF + APNG + animated WebP, all tested, alpha included.

## 8. Edge-case policy ("the circus")

The compatibility surface is a bounded list, not an open-ended swamp --
FFmpeg already absorbs 25 years of malformed files. What leaks through to
us: VFR timing, rotation side-data (phone videos), 10-bit/HDR pixel
formats, alpha, files FFmpeg itself rejects. Policy: correctness for the
common 95 percent, graceful skip for the rest, and compatibility bugs are
triaged at the maintainer's discretion -- this is stated plainly in the
README once the library is usable.

## 9. Testing

- Pure units: pacer, pts/rational math, frame mailbox, soname table --
  no natives involved.
- Semantic integration tests: tiny fixture videos generated by the ffmpeg
  CLI at test time (not committed binaries), decoded through the real
  bindings, asserted on meaning -- frame counts, exact pts grids, solid-
  color pixels within tolerance, alpha survival -- rather than pixel
  checksums: swscale is not bit-exact across versions and SIMD paths, so
  hard checksums would pin an implementation detail. Tests skip (not
  fail) where the CLI or the pinned libraries are absent. Decode is
  deterministic computation -- unlike GUI/OS-integration native code, it
  is genuinely CI-testable, including on macOS runners (both archs)
  standing in for hardware we do not own.
- The bindings layer stays small enough to read in one sitting; that is a
  maintainability requirement, not a style preference.

## 10. Distribution and licensing

- skinema: Apache-2.0.
- FFmpeg: licensed per tier (the modular FEATURES, section 4). `core` carries
  no encoders. `decode` carries every encoder that adds no GPL surface: the
  VAAPI hardware H.264/HEVC pair (M13), whose codec runs in the GPU driver, and
  since M18 the BSD SVT-AV1 and libopus plus FFmpeg's own AAC and FLAC -- so it
  is the complete LGPL WRITER as well as the complete LGPL player. What `full`
  adds is specifically the x264/x265 SOFTWARE encoders, which are GPL, so
  `--enable-gpl` flips only that tier's build (M10 records the encode pivot,
  M12 the software subsystem, M13 the LGPL GPU path, M16 the GPL tier, M18 the
  line moving to where it belongs). **Dynamically linked shared libraries
  only**, license texts shipped (the GPL text on `full`, LGPL on the rest), and
  the source of the exact build referenced -- our own CI artifact, since every
  dependency is built here from a pinned tarball with a recorded sha256 and no
  third party's binary enters a bundle (section 13). Static linking is off the
  table. A consumer needing LGPL takes `core` or `decode`; only one shipping
  `full` takes on GPL.
- Natives packaging: per-tier/OS/arch classifier jars `<tier>-<platform>`
  (the lwjgl/skiko pattern) carrying the trimmed runtime plus an `index.txt`;
  NativeBundle deploys them to a fingerprint-keyed per-user cache (atomic,
  race-safe), keyed by platform so the loader stays tier-agnostic.
- Natives versioning: `skinema-natives` is versioned as the FFmpeg build it
  carries plus a repack revision (`<ffmpeg>-<revision>`), NOT as the library
  (M17). A library release republishes none of the ~211 MiB of bundles; they
  move only when their bytes do. Consumers pair the natives version the
  release notes name with any library version that names it.
- Natives delivery is asynchronous by design: every platform build
  uploads independently to the rolling `natives-<ffmpeg version>` release
  the moment it passes its on-runner acceptance suite. A queued or broken
  platform delays only itself -- never a release, never the other
  platforms -- and a rebuild replaces just its own asset. Matches the
  platform tier model: a missing community-tier build degrades that
  platform, nothing else. macos-x64 is cross-compiled on the arm runner
  (GitHub's Intel macs queue for days to months) and ships without an
  on-metal acceptance run -- an arm JVM cannot load x86_64 dylibs --
  which is exactly what community tier means. linux-arm64 (added in
  M8) is NOT in that boat: GitHub's free arm64 runners are real
  machines, so it builds and passes the acceptance suite on metal like
  the first-class platforms.

## 11. Milestones

- **M0 -- spike: DONE (2026-06-10).** Linux, system FFmpeg 8.1.1, through
  the real hand-written bindings (`Libav`/`VideoDecoder`, offsets from
  `tools/layout-oracle.c`). Results: H.264/mp4 (1/15360 base) and VP9/webm
  (1/1000 base) both decode with pts landing exactly on the frame grid
  (verified against testsrc2's burned-in timecode); 1080p30 H.264
  decode+swscale-to-RGBA costs ~10.6 ms/frame on the dev machine, ~94 fps
  capacity -- 30 fps playback uses about a third of one core, comfortably
  inside the background budget. Verdict: Panama-FFmpeg is routine, not
  pain. Bindings call through adapting `MethodHandle.invoke`; tightening
  hot paths to `invokeExact` is an M1 option, not a need at this cost
  profile. Run it: `./gradlew :skinema-demo:spike -Pinput=<video>
  -Pout=<dir> [-Pframes=N]`.
- **M1 -- core: DONE (2026-06-10).** PlaybackClock (pausable/seekable media
  clock, fake-clock tested), TripleBuffer (tear-free latest-frame exchange,
  zero copies), VideoPlayer (dedicated decode thread, producer-paced by
  pts, loop-on-EOF, frame-precise seek via keyframe + decode-forward,
  fail-closed Failed state covering open and mid-decode errors), decoder
  seek/flush bindings, `skinema.libav.dir`/`SKINEMA_LIBAV_DIR` bundled-
  natives override, Linux CI against the pinned BtbN n8.1 runtime.
  Resolved on the way: core ships with NO coroutine dependency -- the
  consumer polls `acquireFrame()` on its own cadence and reads `state`;
  adapters belong to skinema-compose. Found on the way: the native
  vp8/vp9 decoders silently drop the webm alpha side-channel; the decoder
  swaps to libvpx for those streams (see `pickDecoder`), so trimmed builds
  must carry libvpx.
- **M2 -- skiko + compose: DONE (2026-06-10); the consumer wired it in
  2026-08.** VideoFrameImage raster-copies a frame into a Skia image and
  closes the previous one (straight/UNPREMUL alpha); deliberately
  core-independent -- it takes width/height/bytes, so skinema-compose is
  what ties core and skiko together. VideoSurface pumps frames on
  `withFrameNanos` (a hidden window stops polling for free), scales via
  pure `destinationRect` (Cover/Fit, unit-tested), and draws nothing
  before the first frame or after a failure -- fallback visuals stay the
  consumer's job. skinema-demo plays a file in a bare Compose window:
  `./gradlew :skinema-demo:run -Pvideo=<file>` -- verified live against
  the 1080p fixture. Pins follow the consumer: Compose 1.11.0, Skiko
  0.144.6, and skiko stays compileOnly (the consumer's Compose provides
  it at runtime). M2 closes here: the early Nexira wiring originally
  planned for this milestone is deliberately dropped -- co-developing the
  product against a moving API doubles every change, so the launcher
  adopts a published artifact after M4 instead (see the adoption bar
  below).
- **M3 -- natives pipeline: DONE (2026-06-10).** Landed:
  the trimmed build recipe (`tools/build-natives.sh`) proven locally --
  8.7 MB for all five libraries against ~70 MB full, suite green against
  it in strict mode; fixtures moved from mpeg4 to libx264 because tests
  must exercise exactly the shipped whitelist (mpeg4 is not in it, and
  h264 was never suite-covered before); SKINEMA_REQUIRE_CAPS makes CI
  fail loudly instead of skip-faking green; the test matrix runs
  Linux/Windows (pinned BtbN via the dir override -- the same mechanism
  the bundles use) and macOS (brew, until our mac builds land), all
  three green; packaging decided and implemented -- per-platform
  classifier jars with an index.txt, deployed by NativeBundle into a
  fingerprint-keyed per-user cache (atomic rename, crash/race-safe,
  upgrades never overwrite mapped libraries), load precedence
  prop > env > bundle > system; the headless soak runner measures the
  adoption bar (2-minute smoke: exact 30 fps pacing, RSS flat after
  warm-up). The natives workflow is green on all eight platforms --
  including macos-x64 cross-compiled on the arm runner -- each delivering
  independently to the rolling release after its on-runner acceptance
  suite (cross builds excepted). The script emits a flat, jar-ready
  bundle/ (soname-named real files + licenses + fingerprint index);
  skinema-natives packs bundles into classifier jars from the release or
  a local build. The whole shipping path is proven end to end on Linux:
  jarLocal -> classpath -> NativeBundle fingerprint cache -> a full-rate
  1080p30 soak with zero overrides set. The windowed harness
  (`:skinema-demo:harness`) runs several players at once, surfaces
  unmount/remount while players keep running, and renders a failed
  source as the consumer's fallback cell -- which immediately earned its
  keep: core's volatile `state` is invisible to composition, so
  skinema-compose grew `rememberPlayerState` (frame-clock poll, one
  volatile read per frame). CI dogfoods the shipped bundles: the test
  matrix downloads OUR release assets on all three platforms instead of
  BtbN/brew. Soak verdicts: one hour of looped 1080p30 through the
  bundle path = 103k frames with RSS FALLING from 161 to 99 MB (the JVM
  returned memory mid-run) while a 24-player stress saturated the same
  machine; 96 surface mount/unmount cycles hold a flat post-GC heap
  baseline (263-269 MB) with RSS an asymptote -- the harness's churn
  mode exists for exactly this question. 24 simultaneous 1080p30 players
  pace at ~5.8 cores and ~94 MB RSS each.
- **M4 -- publish: DONE (2026-06-10, tag v0.1.0).** The libtray recipe
  verbatim: vanniktech maven.publish 0.36.0 to the Central Portal,
  gpg-cmd signing, shared POM config in the root for every module that
  applies the plugin. skinema-core/-skiko/-compose publish as libraries;
  skinema-natives publishes an (empty) main jar with the four platform
  bundles attached as classifiers, assembled straight from the rolling
  natives release -- libwebp included on every platform. README is the
  public face: usage, format table, the behavior contract, the compat
  policy, licensing. Release flow: tag vX.Y.Z, then
  `publishToMavenCentral -PappVersion=X.Y.Z --no-configuration-cache`.
  The adoption bar now lacks only its last clause: an API that holds
  still for a full milestone, counting from 0.1.0.
- **M5 -- audio: core DONE (2026-06-10).** AudioDecoder (decode +
  swresample to S16LE stereo at the source rate; flac decodes
  sample-exact, aac with its priming/padding), PcmSink (JavaSound in
  production, a deterministic fake for CI -- runners have no audio
  device), AudioClock (media time = samples the DAC consumed; pause and
  underrun freeze it by construction; detachToWallTime is the failure
  hatch so a dead audio thread cannot freeze video), AudioPipeline (own
  thread + confined arena, blocking sink writes ARE the pacing,
  sample-precise seek cropping, drain-then-wrap looping). VideoPlayer
  grows `audio` and `setVolume`; with sound on, the audio clock masters
  pacing and video never re-anchors it -- loop wraps wait for the audio
  side to restart time. Audio-only files play frameless through the
  normal lifecycle. The sync proof is a test: a manually-advanced fake
  DAC releases exactly the frames up to its position. The video-facing
  API gained only additive parameters with defaults -- the adoption
  bar's stability clause holds.

  Player-control polish followed the core (commits 3895e42..ab9cf55):
  the seek/landing handshake (audio freezes its sink at the anchor until
  the video side reports its decode-forward landing, so video lands
  against standing time instead of chasing a running clock); seek
  coalescing (a burst supersedes the landing in progress -- one landing
  at the final target, not one per press); `seekBy` accumulating against
  an always-valid intended playhead, NOT the live clock (the clock stands
  at the old anchor mid-landing, so reading it made backward bursts land
  at the wrong place, sometimes walking upward -- measured and fixed via
  the `:skinema-demo:seekbench` tool); resume re-anchoring audio to the
  on-screen frame; the `State.Seeking` advertisement for a loading
  affordance; and the landing drop-run decoding without converting
  (`FrameSource.convertLast`).

  The intermittent post-seek freeze ("то работает, то нет",
  load-dependent, present at both the 100 ms and 200 ms line buffers) is
  ROOT-CAUSED as of 2026-06-11 -- a full-library audit found it in plain
  logic, not device jitter. With an audio-mastered clock, video parks in
  awaitClockWrap after its EOF until the audio wraps time; commands
  arriving during the park were handled with decoder = null, so a seek
  re-anchored the audio but never repositioned the video. A target past
  half the duration froze the picture until the audio reached its own
  end of stream; a nearer target self-healed behind a catch-up chase.
  The window opens on every loop wrap (the audio tail plus its drain
  latency, which stretches under load -- hence the load dependence), so
  live seeking hit it intermittently. Fixed: park commands run against
  the real decoder and a landed seek ends the park, with a deterministic
  regression test built on a bounded blocking sink.

  The same audit hardened the rest of the seek path: relative seeks now
  resolve on the decode thread (a seekBy/stamp TOCTOU could eat one
  press of a burst); the seek prefill is deferred until the sink runs
  again (a cropped remainder larger than the line buffer block-wrote a
  stopped line -- a self-deadlock, since the start() that frees it lives
  on the same thread, and FLAC allows 65535-sample blocks); the EOF
  drain became a clock-based wait that stays on the command queue
  (sink.drain() deafened the audio thread for the whole buffered tail,
  exactly inside the wrap window -- drain() left PcmSink with it); and
  AudioClock clamps media time monotonic between re-anchors (some
  backends reconcile framePosition non-monotonically around a
  flush/restart). SKINEMA_DEBUG_SEEK additionally prints the sink
  position at flush/anchor/start for anchor forensics.

  A SECOND root surfaced minutes into the live listen (2026-06-11,
  reproduced headless by seekbench with the new diagnostics): when the
  video landing finishes BEFORE the audio thread processes its half of
  the seek (it reads commands only between blocking writes), the pace
  loop computes the next frame's wait against the still-running
  pre-seek clock and sleeps the whole seek distance -- a +10s press
  froze the picture for exactly ten seconds over normally playing
  sound, and nothing wakes that sleep when the audio anchors the clock
  moments later. Codec asymmetry explained the "mp4 freezes, webm does
  not" observation: sparse-keyframe h264 lands in tens of milliseconds
  and loses the race; av1/vp9 landings are slower and win it. Fixed:
  pace sleeps cap at 50 ms (PACE_RECHECK_NANOS), so a mid-sleep
  re-anchor is noticed within one period. The anchor-jump theory took
  its first data hit the same session: posAtFlush == posAtAnchor ==
  posAtStart on every observed seek.

  The live listen on real hardware (2026-06-11, the same mp4 that froze)
  confirms both fixes: the intermittent freeze is gone. The wall-time
  spin-up extrapolation is REJECTED (decided 2026-06-11): during the
  line refill no sound is audible, so the standing clock is honest --
  extrapolating would run the picture ahead of sound that has not
  started yet and leave a persistent ~fill-length A/V offset after
  every seek unless slew-back compensation is added on top. The
  deterministic ~one-buffer post-seek hold therefore stays, documented
  as the cost of the underrun-proof 200 ms buffer; if it ever matters,
  the lever is a smaller buffer on a lower-latency backend, not clock
  fiction. Nothing blocks 0.2.0 anymore.

- **M6 -- DONE (released as 0.3.0, 2026-06-12).** The real-life audio
  codec set -- ac3/eac3, alac, 24/32-bit
  and float WAV pcm -- is in: whitelisted, natives rebuilt on all four
  platforms onto the rolling release (each through its on-runner
  acceptance gate), decoder tests dogfooding the shipped bundles green
  across the test matrix. Files from the wild (movie-rip audio tracks,
  m4a lossless, DAW exports) decode instead of failing closed;
  consumers get the new bundles with the next natives publish (0.3.0).

  The read-ahead frame queue (section 13's player-scenario knob) landed
  the same day: `readAheadFrames` (default 1, coerced 1..8) holds N
  converted frames of inventory between decode and presentation, so a
  decode stall (slow frame, disk, GC) stops costing the screen while
  inventory lasts. The architecture is a third actor: presentation
  moved off the decode thread to a per-player pacer thread that waits
  out each queued frame's pts and publishes by swapping arrays with
  the mailbox's writing slot -- no pixel copy anywhere on the path. A
  single-threaded queue (fill and release interleaved on the decode
  thread) was designed first and REJECTED on review: a stalled decode
  blocks the publisher itself, so inventory cannot present during the
  stall, which is the entire point. The pacer touches only heap
  arrays, the clock and the mailbox; the FFM arena stays confined to
  the decode thread. Rules that came with inventory: seeks flush the
  queue and land through it as forced frames (published past the
  pacer's state gate and late policy); EOF actions -- loop wrap, the
  clock-wrap park, Ended -- first wait for the pacer to drain the
  tail; a backward clock jump without a seek is a loop wrap, and a
  stranded pre-wrap tail (video outlasting audio) presents at the wrap
  instead of a lap later. One deliberate behavior change at any depth:
  pause no longer drops the in-flight frame -- it presents after
  resume from inventory, which is strictly better. Depth 1 is
  otherwise today's semantics and stays the default; backgrounds keep
  it, a player scenario passes 3-5 (each step of depth is a full RGBA
  frame of memory, 8.3 MB at 1080p).

  A live-feel pass followed (2026-06-12), driven by the user skipping
  through a 60 fps 1080p AV1 webm. Found and fixed, in order: the
  pacer's pace waits were uninterruptible parks, so a landing paid up
  to the 50 ms re-check (queue mutations now wake them -- a mutation
  tick plus monitor waits); the fill side discovered a freed cell only
  via its 20 ms command-poll timeout, capping production below 60 fps
  and degrading high-rate content to the guard-frame slideshow (the
  pacer now drops a RoomFreed token into the command queue -- 1113 ->
  3547 frames/min on that webm); video decode opened single-threaded,
  the codec-context default, putting a 5.5 s AV1 keyframe gap at ~1.5 s
  of seek landing (threads=auto cuts it ~3x; one new av_opt_set
  downcall). What remained is structural -- an exact seek must decode
  forward from the keyframe -- so seeks grew the standard player
  answers: exact landings publish their keyframe immediately as a
  preview while the run works, and seek/seekBy gained an additive
  `exact = false` that lands on the keyframe outright, sound and the
  relative-seek base re-anchored to the landed pts (audio left at the
  request would run a keyframe interval ahead of the picture). The
  demo's skip buttons are inexact; scrubbing stays exact.

- **M7 -- player surface (released as 0.4.0, 2026-06-12):** durationNanos on
  VideoPlayer -- the container-reported value, the stream's own as the
  fallback, null for animated webp (WebPAnimInfo declares none, and a
  full decode up front is the only way to learn it; after one full lap
  the final frame's end time IS the duration -- a cheap future upgrade
  if a consumer ever scrubs a looping webp). Frameless playback takes
  the audio side's value. The demo grew a timeline: dragging scrubs
  with inexact keyframe landings, release settles with an exact seek --
  both seek modes in their intended roles. The nonzero start_time edge
  (TS captures, IPTV) was recorded here as documented-not-handled, and is
  handled: both decoders normalize the timeline to a zero origin against
  the CONTAINER's start_time -- the minimum across streams, so video and
  audio cannot drift apart -- and every seek re-applies the offset before
  asking libav (`formatStartTimeNanos`, VideoDecoder). The entry said no
  supported format does this in practice; DVB ended that, since a
  broadcast recording is exactly a TS capture.

  Audio track selection followed (same date): AudioTrack enumeration
  (language/title via av_dict_get, channels, rate, the default
  disposition), an audioTrack constructor parameter, and LIVE switching
  -- selectAudioTrack swaps the decoder on the audio thread. Two
  ordering rules carry the protocol, both found by adversarial review
  before a line was written: freeze the sink BEFORE reading the
  playhead (the line plays its buffered tail through any slower
  ordering and the rebase would step the mastered clock backward --
  the one move the pacer's invariants forbid), and open the new
  decoder BEFORE closing the old (every failure -- unopenable track,
  track shorter than the playhead -- then means "nothing changed": the
  old sound resumes, no fallback branches). A successful switch
  reopens the sink at the new rate and re-anchors through
  AudioClock.rebase, the one synchronized point where a rate change
  cannot rescale history. A switch mid-landing re-freezes the fresh
  line for videoLanded.

  The metadata trio closed the milestone: chapters (start/end/title),
  format-level tags, and cover art as the stored png/jpeg bytes (the
  consumer's image stack decodes; mkv attachment-style covers are a
  different mechanism and out of scope). Found and fixed on the way: a
  file whose only video stream is the attached picture used to PLAY
  the cover as one-frame video and advertise Ended while the sound ran
  on -- such files now refuse the video open and go frameless. Known
  risk, recorded not handled: a JavaSound blocking write on a dead
  device raises nothing, and the failure hatch (detachToWallTime)
  fires only on exceptions -- a vanished device can freeze the
  pipeline silently. On PipeWire the server masks unplugs by rerouting;
  bare ALSA and Windows are where it would bite. A wall-deadline
  watchdog around the write (the tail-wait pattern) is the fix when
  the platform pass comes.

- **M8 -- color correctness and playback control (released as 0.5.0, 2026-06-13):** the
  RGBA chokepoint honors what frames declare: the YUV matrix
  (BT.709/601/2020 and friends, via sws_setColorspaceDetails) and the
  sample range. swscale's silent default is BT.601/limited for
  everything, so every BT.709 (HD) file had been decoding through the
  wrong matrix -- a small but measurable shift, strongest in saturated
  greens -- and full-range streams played with crushed levels. Streams
  that declare nothing take the convention players agree on: HD
  geometry means BT.709, smaller means BT.601. Sources with no YUV
  matrix at all (paletted gif, rgba apng) refuse the details call and
  keep swscale's defaults, which is correct there.

  Playback rate followed: `setRate`, 0.5x-4x, pitch preserved. The
  engine is atempo behind hand-written avfilter bindings (the pin's
  sixth library; the trim carries exactly the atempo chain), wrapped
  as TempoFilter on the audio thread's one write path -- at 1.0 the
  stretcher does not exist and PCM flows untouched. The clocks gained
  the factor (AudioClock: media advance = device frames x tempo/rate;
  PlaybackClock scales wall deltas) with the same re-anchor rule as
  every other mutation: the new scale applies only forward. A live
  change is handled as a mini-seek -- freeze the sink, read the
  playhead, rebuild the stretcher, re-scale the clock, re-crop the
  stream -- because the line's buffered tail was stretched at the OLD
  tempo, and re-anchoring over it would leave a permanent A/V offset
  of the tail length times the tempo delta. Rejected on the same
  grounds: avfilter_graph_send_command for a smooth in-graph tempo
  ramp (it preserves exactly the state the re-crop makes stale) and a
  JVM-side WSOLA (more code to own for worse quality than atempo).

  Frame stepping closed the milestone: stepForward/stepBackward, both
  leaving the player paused on the stepped frame with time (sound
  included) re-anchored there. Forward is cheap -- the queue's head
  promotes to forced (a new FrameQueue.forceHead), or one frame
  decodes when inventory is empty. Backward is honest about VFR: the
  previous frame's pts is only knowable by decoding from the keyframe
  toward the shown one, so it runs that pass first and then lands an
  ordinary exact seek on the answer -- a keyframe run's cost,
  advertised through State.Seeking like any landing. The discovery
  pass keeps the pts run it decoded: which pts precedes which is a
  static property of the file, so the memo never invalidates and a
  repeated backstep pays one run, not two. Step landings skip the
  keyframe preview -- for a one-frame step it is a backward picture
  jump, not feedback. Repeated backsteps on sparse-keyframe content
  still cost a decode run each; the only lever past that is a frame
  cache (tens of MB at 1080p), not taken.

  linux-arm64 joined the platform matrix: a fifth bundle on the
  rolling natives release, built and acceptance-tested on GitHub's
  free arm64 runners (real metal, full suite -- no cross-build
  caveat), a fifth classifier jar on the publication, a fifth row in
  the test matrix. The loader side needed nothing: nativesPlatform()
  has mapped aarch64 since M3.

  Rotation closed the section-8 leak-through: phone footage's display
  matrix surfaces as rotationDegrees (clockwise-to-apply, snapped to
  the quarter grid -- av_display_rotation_get reports the matrix's
  counterclockwise angle, display applies the inverse), and
  VideoSurface rotates at draw time: Cover/Fit math runs on the
  displayed (swapped) dimensions, the canvas turns about the rect's
  center, and the pixels are never touched -- a transform, not a
  transpose.

- **M9 -- subtitles (released as 0.5.0, 2026-06-13):** the full tier in one epic --
  libass-rendered text (ASS/SSA native, SRT/mov_text/WebVTT converted),
  bitmap tracks (PGS, dvdsub), external files, track enumeration and
  live selection; off by default, the pipeline starts lazily on first
  select. SubtitlePipeline is the audio pipeline's shape over the third
  stream type: own thread, confined arena, own format context, command
  queue, pendingSeeks handshake contract. The two adversarially-found
  rules that carry seeks: the demux refill gates on ANY stream's pts
  (subtitle packets are sparse; a subtitle-pts gate reads unbounded
  interleaved data deaf to commands), and the libass track flush policy
  is per-codec -- native ASS packets embed stable ReadOrders that libass
  dedups across replays, converted codecs re-number from a decoder
  counter that resets on flush, so their track flushes on EVERY
  reposition and the 10s preroll (matroska cues align to video
  keyframes) replays the visible state. The converted-codec trap's real
  shape pins it in tests: a forward seek past the fed window re-numbers
  the landing cue into a ReadOrder collision and dedup eats the NEW
  event. The ASS style header comes from the opened decoder context's
  subtitle_header (converted decoders synthesize it there; codecpar
  extradata stays empty) -- the one direct AVCodecContext read in the
  bindings. Track switching always spawns a fresh pipeline: subtitles
  own no device, replacement IS the switch. Bitmap display sets convert
  to premultiplied patches once at decode time and live in a window
  schedule (a window closes at its own end, the packet duration, or the
  next event -- num_rects == 0 is the pgs clear); the PGS fixture is a
  synthesized .sup stream-copied into mkv, since no PGS encoder exists
  anywhere and dvdsub's encoder cannot take text input either. The Ass
  binding is optional like the webp pair (Ass.available; text refuses,
  bitmap plays on) and carries skinema's first FFM upcall: libass logs
  to stderr unless a callback is set and NULL is a no-op, so a
  MethodHandles.empty stub silences it -- never dereferencing its
  arguments, va_list as an opaque pointer. mkv font attachments feed
  ass_add_font before the renderer initializes; VideoSurface composites
  the overlay inside the video's own rotation transform, mapped onto the
  pre-rotation rect and posting that storage-oriented rect back, so
  positioned ASS and bitmap planes stay glued to rotated footage rather
  than compositing upright over it. The CI flip then exposed a defect the
  skip-permissive green
  had hidden: linux libass loaded but exported zero symbols (the build
  script's `-Wl,--exclude-libs,ALL` localized libass's own ass_*
  alongside the static freetype/harfbuzz it meant to keep private), so
  the subtitle suites had been silently skipping all along; scoping
  exclude-libs to the two static archives fixed it. That false-green
  generalized the gate: SKINEMA_REQUIRE_CAPS lists the capabilities a
  bundle must load (decode,subs,webp), enforced in BOTH build.yml and
  natives.yml -- so a broken bundle fails before it uploads, in the
  workflow that built it -- plus a CapabilitiesTest asserting each
  listed capability loads independently of any fixture, the antidote to
  a skip reading as green. That gate then caught Windows shipping no
  libass at all: MinGW libtool refuses to fold a static archive into a
  DLL, so the static freetype/harfbuzz fold that works on Linux/macOS
  left the Windows libass DLL unbuildable. On Windows freetype and
  harfbuzz now ship as their own DLLs (the loader preloads them), and
  the four MinGW runtime libraries the bundle links -- zlib, bzip2,
  libiconv, winpthread, a pre-M3 gap the runner's PATH had masked for
  the av* DLLs too -- ride in the bundle and are preloaded by name. The
  Windows CI test strips mingw/msys from PATH so a green run proves the
  bundle is self-contained, not leaning on the toolchain.

- **M10 -- direction change: encode + hardware (decided 2026-06-22).** The
  charter widens past decode-only software playback: the consumer now needs
  encoding/transcoding and GPU acceleration. Two consequences are recorded
  before any code so later work does not re-litigate them. Licensing:
  software H.264/HEVC encode means x264/x265, which are GPL, so the shipped
  FFmpeg flips to `--enable-gpl` and the natives become GPL -- accepted. The
  README's LGPL paragraph and section 10 are rewritten only when GPL natives
  actually ship (an encode milestone), never before, so the public face
  never claims a licence the bundles do not yet carry. GPU encoders
  (NVENC/VAAPI/QSV/VideoToolbox) live in the driver and stay LGPL, so they
  are the licence-clean route to H.264/HEVC OUTPUT regardless.
  `--disable-network` survives both (writing a local file needs no I/O
  beyond it). Sequence: M11 GPU decode, M12 software encode + mux, M13 GPU
  encode, M14 transcode/record; CPU decode is the existing engine.

- **M11 -- hardware decode (DONE, 2026-06-22; negotiation corrected 2026-08-18).**
  VideoDecoder grew an opt-in GPU path behind `HwAccel` (OFF default / AUTO /
  REQUIRE), threaded through FrameSources and a VideoPlayer `hardware`
  parameter; `VideoPlayer.hardwareActive` (and `FrameSource.hardwareActive`)
  report whether it engaged -- the only signal for a feature a GPU-less CI
  cannot test. AUTO tries VAAPI then NVDEC (Linux), D3D11VA then DXVA2
  (Windows), VideoToolbox (macOS): a decoder hw config is matched with
  avcodec_get_hw_config, the device created with av_hwdevice_ctx_create, and
  skinema's FIRST upcall-carrying-logic installed at AVCodecContext.get_format
  pins the hw surface format (falling through to the software format is the
  graceful no-device answer). Decoded GPU frames download via
  av_hwframe_transfer_data into a reused sw frame, props copied so HDR/matrix
  detection still reads true, then run the existing swscale chokepoint -- so
  the RGBA8888 contract is identical on every path. AUTO falls back per file
  (no device, unsupported codec, a per-frame software frame); REQUIRE fails
  closed. The convert=false drop-run reads pts/geometry off the raw frame
  with NO transfer, so seeks stay cheap on the hw path too. ABI:
  AVCodecContext.get_format/hw_device_ctx/opaque and AVCodecHWConfig, from a
  re-run of tools/layout-oracle.c. The acceptance suite (VideoDecoderHwTest)
  is gated behind SKINEMA_TEST_HWACCEL=1 so a GPU-less CI, and macOS's
  always-present VideoToolbox, are not silently exercised on a path this
  change cannot see.

  Corrected 2026-08-18, and the correction is the lesson: the surface to
  negotiate for was scoped to the thread that opened the file, and a
  frame-threaded decoder negotiates on a worker of its own, where a
  thread-scoped value is absent -- so get_format fell through to the software
  entry and the GPU was never used, on any platform, from the day this landed
  beside `threads=auto`. Nothing caught it because every signal in reach --
  `hardwareActive`, the pts grid, the pixels -- reports the REQUEST or a
  result software decode produces identically; only the decoded frame's own
  pixel format distinguishes the two, and nothing looked at it. The target now
  rides AVCodecContext.opaque, which libav leaves alone and frame threading
  copies into each worker context, and the suite asserts the frame's format
  against the surface a device was opened for. Verified on a shipped bundle
  (skinema-natives 9.0.1-1, full tier, linux-x64) against real VAAPI: frames
  arrive as AV_PIX_FMT_VAAPI and download through av_hwframe_transfer_data,
  which closes this milestone's bundle-pending note -- the trimmed natives do
  carry the platform hwaccels (VAAPI adds a libva system dependency on Linux,
  like fontconfig). NVDEC/NVENC/QSV/AMF and zero-copy GPU->Skiko interop are
  deferred.

- **M12 -- software encode + mux (DONE, 2026-06-22; the bundle followed in M16/M18).**
  The push-side inverse of the decode pipeline. `MediaWriter`
  (dev.hivens.skinema.encode) takes a `VideoEncodeConfig` (encoder name,
  geometry, fps, bitrate, private options), opens the muxer inferred from the
  output extension, and accepts RGBA8888 frames -- reverse-swscaled to
  YUV420P, encoded, interleaved into the container -- with `finish` draining
  the encoder and writing the trailer. One confined Arena on the calling
  thread; fail-closed (an unknown encoder or any libav refusal throws and
  leaves nothing allocated). GLOBAL_HEADER is set when the muxer wants it,
  and codec-private options (crf, preset) go through av_opt_set with
  SEARCH_CHILDREN. The codec time_base is microseconds (VFR-friendly);
  packets rescale by hand to the muxer's stream time_base, since av_rescale_q
  would pass AVRational by value and the bindings avoid that. New bindings:
  the encode half (avcodec_find_encoder_by_name / send_frame / receive_packet
  / parameters_from_context, av_frame_make_writable) and the avformat output
  half (alloc_output_context2, new_stream, avio_open/closep, write_header,
  interleaved_write_frame, write_trailer, free_context); ABI for the
  AVCodecContext write fields, AVOutputFormat.flags, AVStream.index and the
  packet/format pointers from the oracle. Audio rides the same writer: an
  optional `AudioEncodeConfig` adds a second stream -- interleaved S16LE
  stereo reverse-swresampled to the encoder's planar format and chunked to
  its frame_size, both streams interleaved by the muxer, both encoders
  drained at finish. Audio encoders (native AAC, libopus, FLAC) are
  LGPL/BSD, so sound needs NO GPL; it reuses the existing swresample
  bindings, and only the AVCodecContext audio write fields plus
  AV_SAMPLE_FMT_FLTP joined the ABI. Validated by semantic round-trips: ten
  RGBA frames -> libx264/mp4 decoded back (frame count, a solid colour
  within yuv tolerance), and a video+audio file -> libx264+aac decoded back
  through BOTH VideoDecoder and AudioDecoder (MediaWriterTest, gated on the
  loaded libav carrying the encoder, so a decode-only bundle skips).
  The trimmed-bundle encoder build landed as M16 and the licence rewrite
  shipped with it, so a shipped bundle runs MediaWriter rather than only a
  full system FFmpeg. What that leaves is named in M16: SVT-AV1 and libopus
  are still absent, so the writer's AV1 and Opus paths have no encoder to
  reach outside a system build.

- **M13 -- GPU encode (DONE, 2026-06-30).** MediaWriter drives a hardware
  encoder on the GPU without a new class: a named encoder ("h264_vaapi",
  "hevc_vaapi") is recognized through avcodec_get_hw_config -- the first config
  that takes an AVHWFramesContext gives the surface format and device type, the
  SAME detection for VAAPI, QSV or an NVENC cuda pool, so a future backend
  needs only its encoder enabled in the build, no code here. For a hw encoder
  the writer opens the device (av_hwdevice_ctx_create, an optional
  VideoEncodeConfig `device` names a VAAPI render node), builds a surface pool
  (av_hwframe_ctx_alloc/init, sw_format NV12, a fixed initial_pool_size), sets
  the codec's pix_fmt to the hw surface and attaches hw_frames_ctx. Each frame
  reverse-swscales RGBA -> NV12 into a staging frame, draws a fresh surface
  (av_hwframe_get_buffer), uploads it (av_hwframe_transfer_data) and encodes
  that; the software path is unchanged (RGBA -> YUV420P, sent directly). Hw
  encode is fail-closed -- a device that will not open or an upload the driver
  refuses throws, NO silent software fallback (the codec name is the explicit
  request); teardown unrefs the pool and device after the codec drops its own
  refs and frees the GPU frame. New bindings: av_hwframe_ctx_alloc/init,
  av_hwframe_get_buffer and a named-device av_hwdevice_ctx_create, reusing
  M11's device/transfer/get_hw_config surface. ABI from a re-run of
  tools/layout-oracle.c: AVCodecContext.hw_frames_ctx, the AVHWFramesContext
  struct (format/sw_format/width/height/initial_pool_size), AVBufferRef.data,
  AV_PIX_FMT_NV12 and the HW_FRAMES_CTX method bit. Licensing: the VAAPI
  encoder lives in the GPU driver (Mesa/iHD), so it is LGPL -- build-natives.sh
  decouples the GPL flip from "has any encoder" so only x264/x265 set it, and a
  new `enc-vaapi` feature enables h264_vaapi/hevc_vaapi (plus the muxers and
  native aac/flac) with NO --enable-gpl. It rides the decode and full tiers
  (Linux only; a no-op elsewhere -- NVENC/QSV/AMF/VideoToolbox need extra SDKs
  and stay a follow-up), giving GPU H.264/HEVC output in an LGPL bundle.
  Validated end to end on real VAAPI (an Intel Iris Xe dev box): RGBA frames ->
  h264_vaapi/mp4 decoded back, solid colour within tolerance (MediaWriterHwTest,
  gated behind SKINEMA_TEST_HWENC=1 so a GPU-less CI cannot run it); a
  fail-closed test (an unusable render node throws, no file left) runs without a
  GPU. The trimmed enc-vaapi bundle builds LGPL-clean (CONFIG_GPL 0, the
  h264_vaapi/hevc_vaapi encoders in libavcodec, only the LGPL licence text
  shipped). PENDING: like M11's hwaccel, the natives.yml enc-vaapi build is
  UNVALIDATED on a real GPU in CI (no GPU runners, issue #29); the other
  backends are a build-flag-plus-verification round each on their hardware.

- **M14 -- transcode (DONE, 2026-08-20; recording NOT built).** `Transcoder`
  joins the two halves that had existed separately -- it reads a file through
  the decode side and writes another through `MediaWriter`. It is a class
  here rather than a snippet in the guide because the join carries two traps
  a consumer cannot see from outside, and both are timing. ONE ORIGIN: the
  writer times video by the timestamp it is handed and audio by a running
  sample count from the first sample pushed, so a source whose sound starts
  after its picture would come out with the tracks shifted apart; the gap is
  padded with silence. ONE CADENCE: the muxer interleaves by timestamp and
  holds one stream's packets until the other catches up, so pushing a whole
  track and then the other queues the first in native memory without bound --
  both go in timestamp order, a chunk at a time, audio leading. Geometry
  comes from the source rather than the config (this converts a file, it does
  not resize one) and a source that changes geometry mid-stream is refused
  rather than written into a stream opened at the first frame's size.
  Rotation is APPLIED rather than carried, because the writer has no
  orientation tag and a silently sideways file is the worse answer. What it
  deliberately does NOT do is copy streams: every frame leaves the decoder as
  RGBA and enters the encoder as RGBA, two swscale passes and a chroma
  generation apiece. That is stated in the class and the guide rather than
  hidden -- a caller who already has the codec they want is better served by
  not decoding at all, and that is the thing this cannot do. `cancel()` stops
  at the next frame and still writes the trailer, so a cancelled run leaves a
  shorter file that plays rather than a broken one. The other half of M10's
  fourth step, RECORDING, is not built: nothing here captures a live source,
  and `--disable-network` plus the absent capture devices (`--disable-avdevice`)
  mean it would be a new subsystem rather than a wiring job.

- **M15 -- custom AVIO input (streaming primitive, 2026-06-23).** VideoDecoder
  gains `open(MediaSource)` beside `open(Path)`: a public `MediaSource`
  (read/seek/size) lets a consumer feed bytes -- segments, a download, memory
  -- and the demuxer pulls them through a custom AVIOContext. read/seek are FFM
  upcalls bound per-source (`AvioSource`), the project's second and third
  logic-bearing upcalls after get_format; the av_malloc'd bounce buffer is
  freed by hand after avformat_close_input (avio_context_free leaves it). The
  open path was refactored to share its tail (`openVideo`) between the Path and
  MediaSource entries. `--disable-network` is untouched: skinema still performs
  no I/O of its own; every byte comes from the consumer. This is the primitive
  the streaming companion needs -- ABR/HLS/DASH live in that separate library,
  not the decoder. Validated by decoding a file through a MediaSource (matching
  the path decode frame for frame) on both a seekable in-memory source and a
  forward-only one (the live-stream shape). AudioDecoder gained the same seam
  (`openOrNull(MediaSource)`), so audio-only streams -- a music-radio feed --
  play through it, validated the same way (chunk grid match, plus the
  no-audio null path still releasing the avio context). A VideoPlayer entry
  point is the remaining piece and a deeper design: a player runs SEPARATE
  video and audio demuxers, so one byte stream cannot feed both -- it needs a
  `() -> MediaSource` factory (a fresh reader per decoder; fine for a
  buffered/seekable source, not a forkable live one) or a single unified
  demux. That decision belongs with the streaming consumer.

- **M16 -- GPL encode bundle (DONE; the series closed in M18, 2026-06-23).** The trimmed natives flip to `--enable-gpl` and gain the x264
  H.264 encoder (static, folded in like dav1d/libvpx -- no runtime
  dependency), the libx264/aac/flac encoders and the mov/mp4/matroska/webm
  muxers, so a SHIPPED bundle runs MediaWriter, not only a full system
  FFmpeg. README and section 10 are rewritten for the GPL bundle (skinema
  stays Apache; a decode-only consumer can ship its own LGPL FFmpeg). The
  series is incremental by cross-build risk: x264 is autotools + nasm (no
  cmake); the cmake encoders (x265, SVT-AV1) and libopus follow, each its own
  round (and x265/SVT-AV1 add cmake to the CI image). The MediaWriter encode
  test stops skipping once the bundle carries libx264, so the natives
  acceptance suite exercises encode on metal.

  x265 landed on every platform once issue #22 was answered: x265 4.1 sets
  pre-3.5 cmake policies to OLD, which cmake 4.x refuses outright, so the
  build patches those policy lines out of x265's own CMakeLists before
  configuring rather than pinning an old cmake into three CI images.

- **M17 -- natives on their own version line (2026-07-15).** `skinema-natives`
  stops riding the library's version and publishes as `<ffmpeg>-<revision>`
  (`nativesVersion` in gradle.properties, first cut `8.1.1-1`); a library
  release runs `publishLibraries` and ships core/skiko/compose only -- the bare
  `publishToMavenCentral` swept the bundles in with them. Forced by Maven
  Central's publishing limits: free publishing is capped per organization at
  ~78 MB of release size a month (plus ~1167 files and 7 releases -- the 90th
  percentile of all publishers), hard-enforced from 2026-08-11. dev.hivens ran
  287.9 MiB in June and 317.8 MiB in the first six days of July: 369% and 407%
  of the size cap, June also over on files (1990). The overage is one artifact.
  The 24 tier/platform bundles are ~211 MiB per release; everything else in the
  namespace -- core, skiko, compose, libtray, libnotify, libvault -- is ~0.4 MiB
  together. Nearly all of it bought nothing: versioning the bundles with the
  library republished all 18 even when the bytes were identical. 0.6.0 -> 0.6.1
  changed 0 of 18 (a swscale fix that lives in Kotlin; 158 MiB re-uploaded for
  nothing) and 0.6.1 -> 0.6.2 changed 6 of 18 (the Windows liblzma repack -- the
  12 linux/macos jars were byte-identical), both checkable against the `.sha1`
  files on repo1. Decoupled, a release that leaves the bundles alone costs
  ~0.4 MiB. What this does NOT fix: a release that does change them still costs
  ~211 MiB that month, ~2x the free cap, because the matrix is real -- 8
  platforms x 3 licence tiers, and trimming it means dropping platforms or the
  LGPL-only tiers, which is what those tiers exist for. That residue is the
  exemption request to central-support, and it is the whole ask: ~0 most months,
  ~211 MiB when FFmpeg is re-rolled. Granularity considered and rejected:
  per-platform artifactIds (`skinema-natives-linux-x64` and friends, each on its
  own version) would have made the Windows-only repack cost 66 MiB and slipped
  under the cap, but it splits the namespace into eight artifacts whose versions
  drift apart in the consumer's build file, and a new FFmpeg pin -- the ordinary
  reason bundles change -- touches every platform and costs the same 211 MiB
  either way.

- **M18 -- the LGPL tier learns to write (2026-08-27).** The licence line was
  drawn in the wrong place. `full` was "the tier that encodes" and `decode`
  "the tier that plays", so a consumer who needed to write a file took on GPL
  whether or not the codec they actually wanted was GPL. Two BSD encoders move
  the line to where it belongs: SVT-AV1 (`enc-av1`) and libopus (`enc-opus`)
  are built static and folded in like x264, add no GPL surface, and therefore
  ride `decode` as well as `full` -- the argument `enc-vaapi` already made in
  M13. What `full` buys is now specifically SOFTWARE H.264 and HEVC, which is
  what x264 and x265 are.

  The tier placement was decided on a measurement rather than an estimate,
  because the estimate was wrong: a decode bundle grows **+3.4 MiB compressed**
  (3.2 -> 6.6 on the comparison build), +7.9 MiB unpacked, libavcodec 4.5 ->
  12.0 MiB. The first guess had been +5 to +7 compressed.

  Both dependencies are pinned from two hosts, and the two cases differ.
  libopus publishes a release tarball that is byte-identical from xiph and from
  the GitHub release, so one digest covers both. SVT-AV1 publishes no release
  tarball at all -- only GitLab's on-demand archive, the same shape that took
  dav1d and x264 down mid-matrix -- so the second source is GitHub's mirror,
  both hosts GENERATE an archive, and the two differ in bytes and root name
  while extracting to the same 1292 files. Both digests are recorded and the
  directory is normalised after unpacking. FFmpeg 9.0.1's wrapper carries
  explicit `SVT_AV1_CHECK_VERSION(4, 0, 0)` branches, so 4.x is the line it was
  written against; configure's `>= 0.9.0` is not the version to read that from.

  The muxers gained the three audio-only containers, and `MediaWriter` gained a
  second entry point to fill them: `open(path, audio)` beside
  `open(path, video, audio)`. An overload rather than a nullable video
  parameter, so a writer with no streams at all cannot be asked for -- each
  overload requires one and `open(path, null)` does not compile. `writeFrame`
  on an audio-only writer is refused by name, the way `writeAudio` already
  refuses on one opened without sound.

  The write surface is advertised in a table of its own now and checked against
  the bundle's manifest by the same script the read side uses, which took the
  section and the claim floor as arguments. The reason is the one that produced
  the read-side check: an encoder can go missing exactly as quietly as the
  mov_text decoder did.

  One lesson worth keeping, because it cost a dispatch: the feature list per
  tier was declared in `build-natives.sh` AND in `natives.yml`. Adding the two
  encoders to the script alone failed all eight platforms in fifteen seconds --
  the script refuses when a FEATURES it is handed disagrees with its TIER. The
  guard worked; the second declaration should not have existed, and the
  workflow now passes TIER and lets the script decide.

- **M19 -- the borrow rule, and the things the library did without saying so
  (2026-08-29).** One defect class and one audit, and they turned out to be the
  same subject: a library that behaves correctly and does not say what it is
  doing is a library a consumer builds the wrong thing against.

  `VideoFrameImage` retired every superseded Skia image into a queue that only
  `reclaim` drained. A caller who never called it got no error, no ceiling and
  no signal beyond process size -- the queue holds a strong reference, so the
  images were neither freed nor collectable and a heap profiler shows none of
  it. Measured at 1080p: two hundred frames took resident memory from 250 MB to
  1796 MB, and one `reclaim` returned it to 245. A warning was added first and
  was not enough, which is what issue #65 said: it made the mistake visible
  rather than impossible.

  What could not be closed eagerly was real -- the drawing thread may still be
  painting and only it knows when it is done -- but it can only be painting
  with the one it took most recently. Reading `image` records that one, and a
  publish frees every superseded image except it. **One live borrow per side**,
  and native memory bounded whatever the caller does. `reclaim` stays and stops
  being a correctness requirement.

  Two things about the shape are worth keeping. The issue proposed keying this
  on the getter alone, which is unsound in the direction the tests already
  covered: `update` returns the image too, so a single-threaded caller holds one
  the getter never issued. And recording the publish as well, the other way
  round, is redundant rather than harmless -- the value `update` returns IS the
  current image and the current image is never among the retired ones, so the
  bound is ONE superseded frame, not two. That was found by re-deriving the
  invariant rather than by testing, and it removed a field.

  `SubtitleOverlayImage` closed on the spot, which quietly made its `update` the
  drawing thread's alone -- while the compose guide tells you, correctly, to
  raster FRAMES off that thread. A consumer generalising from one sibling to the
  other frees overlay pixels under a draw: a native crash, not a wrong picture.
  It answers the same way now. Its `close` deliberately does not follow: on the
  frame holder that is a teardown, here it is also how subtitles are turned off,
  so it frees what is held and leaves the object usable and a re-selection
  publishes again. The Compose surface relies on exactly that.

  The API gained the two openings a consumer could not build from outside,
  because both live in the window between the constructor returning and the
  player getting going. `startPaused` settles Paused directly, never through
  Playing -- the pacer is running by then, so the transient would be observable
  -- and commits the first frame FORCED, the way a landing is, because a paused
  start that showed nothing is a black rectangle. `volume` is on the line before
  the first sample: the audio thread opens the device and writes its first chunk
  on its own schedule, so a `setVolume` after the constructor has already lost.

  Its other half was a defect. A line starts at whatever gain the device gives
  it and nothing put the volume back, so a muted player came back at FULL the
  moment anything reopened one -- a track switch, or a device-loss recovery,
  which is the worse of the two because nobody asked for it. The pipeline holds
  the value now and applies it inside `openLine`. Kept there rather than in the
  sink because the sink is a seam a consumer implements, and remembering a value
  across a reopen it does not control is not a rule worth handing them.

  `setSubtitleCanvasSize` was not idempotent, whatever its unchanged arguments
  suggested: it queued unconditionally and the size was compared on the subtitle
  thread, after that thread had been woken to read the command. A consumer
  posting from its draw loop -- the natural place, since the displayed rect is
  known there -- handed an unbounded queue sixty announcements a second, and a
  pump that reads a non-empty queue as work pending refills a packet at a time
  and never reaches its own render cadence. The guard moved to the caller's
  side, where every caller gets it; the surface's private copy of the same rule
  went with it, because a second place holding one rule is the place a consumer
  cannot see.

  Device-loss recovery gained the shutter the writes already keep. It reads the
  close flag once per attempt and a decoder seek sits between that read and the
  reopen, so on a slow source the window is milliseconds: long enough to open a
  sink the caller had already been told it had back. Opening is not a write, but
  it reaches into the same object and `open()` starts it by contract.

  The audit found two documented claims that were FALSE, which is worse than
  silence. "The pump does not stop for a consumer that stops polling" had been
  true until the player learned to notice an unread mailbox, so the behaviour
  contract promised a burned core behind a hidden window. And the overlay holder
  claimed to follow the frame holder's discipline while following the opposite
  one. The silences were ordinary and expensive: the `sink` parameter carried no
  documentation at all despite being the seam a consumer's own audio stack
  arrives through (and is ignored entirely with `audio = false`);
  `WhenUnwatched.Freeze` stops the timeline with a real pause, so `state` reads
  Paused without anyone having asked; `positionNanos()` is deliberately not
  clamped to `durationNanos`, so a progress bar can read past its own end;
  encode geometry must be EVEN and the argument checks raise
  `IllegalArgumentException`, which the `catch (e: LibavException)` the guide
  recommends does not see; and the threading note listed the wrong methods as
  the ones that skip the decode thread.

  The lesson worth carrying: every one of those was found by reading the code
  against its own documentation rather than by a test failing. A test asserts
  what someone thought to assert. A document that has drifted from the code
  asserts nothing at all, and a consumer believes it.

Adoption bar (the primary consumer): the launcher takes skinema as a
normal published dependency once 0.x is on Maven Central with bundled
natives for its official platforms, the background harness has survived
a long soak without RSS growth, and the API has gone a full milestone
without breaking changes.

All three clauses are now answered. The first two by the consumer itself
(section 13, the Coil question): it takes compose/skiko/natives from Central
as ordinary dependencies, on the `decode` tier.

The soak clause is measured, 2026-08-28: two hours of looping 1080p30 through
the published full-tier bundle with GPU decode, 214692 frames at 29.8 fps,
`hardwareActive` true throughout. What it took to answer honestly is worth
recording, because two shorter runs answered it wrongly first. An instantaneous
RSS is a sawtooth whose teeth depend on where the collector happened to be, so
the tool reports the LOW-WATER MARK -- and even that needs a long enough run:
warm-up here lasts twenty-five minutes, which is why a thirty-minute run's
windows all contain climb and reported drift that was not there.

Over two hours the floor climbs to 334 and holds nine samples, to 366 and
holds, then to 391 where it sits for THIRTY consecutive samples -- half an
hour flat. It reaches 408, and then falls: 402, 397, 397, 397, 348, 291. A
hundred and seventeen megabytes handed back to the operating system, after
which it climbs gently again.

That drop is the evidence, and it is stronger than a flat line would have
been: a leak cannot return memory. What the series shows is a runtime taking
and releasing, not a pipeline accumulating.

The frame rate in that line says the run kept up on the machine it ran on. It
is not a benchmark and must not be quoted as one: the same machine has since
been found to degrade over its own uptime for reasons outside this project, so
a throughput number from it means "did not fall behind" and nothing more. The
FLOOR is the measurement, and a floor is what it is whether the run is fast or
slow -- which is why that is the quantity the tool reports.

### What that run did NOT cover, and the gate that follows

`SoakMain`'s only consumer-side call was `acquireFrame`, so the run exercised
decode, pacing and the mailbox and stopped there. It never built a Skia image.
That left `VideoFrameImage` -- the one component whose whole job is holding
native memory, and the memory a heap profiler cannot account for -- outside
the run that exists to prove native memory does not grow. The run also predates
the rewrite of that class and of its overlay sibling.

So the soak now carries `-PsoakImages=true`, which puts frames through a real
`VideoFrameImage` in the shape a consumer uses it: the loop rasters, because it
holds the frames, and a second thread draws -- reclaiming and reading at a
screen's cadence. That is the Compose surface's split with the roles named the
other way round, and it is what puts the borrow across a thread boundary rather
than leaving it a single-threaded exercise.

**This is a pre-release gate, not a pre-merge one.** The unit tests and their
mutation checks answer what closes and when; they run for milliseconds over
four-by-four images and cannot answer whether a native allocator accumulates
over hours of eight-megabyte rasters. Only a long run does, and only the floor
in it. Two runs are owed before a release: images with sound off, comparable
with the series above, and images with sound on, which is the consumer's own
shape and the only thing that exercises the audio thread, its watchdog and a
device handle held for hours.

Run it with `-Phardware=OFF`. The question is whether the image holder
accumulates, and that does not depend on where the frame came from -- while GPU
decode puts the frame download on the same memory bus the compositor uses,
which makes the run intrusive on a desktop for no gain in what it measures.

## 12. Version pins (2026-06)

| Component  | Version        | Note                                   |
|------------|----------------|----------------------------------------|
| JDK floor  | 22             | java.lang.foreign final                |
| Toolchain  | 25 (LTS)       | foojay resolver fetches if absent      |
| Kotlin     | 2.4.0          | matches the primary consumer           |
| Compose    | 1.11.0         | matches the primary consumer           |
| Skiko      | 0.144.6        | what Compose 1.11.0 ships; compileOnly |
| Gradle     | 9.5.1 (wrapper)|                                        |
| FFmpeg     | n9.0.x         | sonames in section 4                   |

## 13. Open questions

- ~~Core API shape~~ resolved in M1: poll-based, no coroutines in core;
  skinema-compose owns the adaptation.
- ~~dav1d in trimmed builds: build it ourselves or take BtbN's?~~ settled
  by M3 and everything after it: every source dependency is built here,
  static, from a pinned tarball with a recorded sha256 -- dav1d and libvpx
  included. BtbN builds no macOS at all, which decided it, and taking a
  third party's binary would put a component in the bundle whose contents
  no check in this repo can state.
- ~~Windows/macOS arena + library unloading behavior on session close~~
  answered by construction, not by a platform test: every `libraryLookup`
  and both upcall stubs take `Arena.global()`, so the libraries load once
  and stay for the life of the process. There is no unload path whose
  semantics could differ per OS. That is the choice rather than an
  oversight -- a decode thread outlives any single player, and an upcall
  stub freed while FFmpeg still holds the pointer is a crash rather than an
  error. What IS per-OS is LOADING, and each half of it has its own gate:
  the Windows preload list and import closure (tools/check-windows-bundle.sh),
  the `$ORIGIN` and `@loader_path` rewrites the bundle build asserts, and
  the host-dependency surface declared in tools/bundle-surface.txt.
- ~~The intermittent post-seek freeze~~ root-caused and fixed 2026-06-11
  (the awaitClockWrap park hole -- M5 section). The extrapolation
  question is decided against (M5 section); the ~one-buffer post-seek
  hold stays by choice. ~~A read-ahead frame queue~~ shipped in M6
  (`readAheadFrames` and the pacer-thread architecture; reasoning in
  the M6 milestone entry). The catch-up residue is resolved
  (2026-06-11): runs ride the convert=false drop-run behind a pure
  publish policy -- frames later than 250 ms drop unconverted, a
  starvation guard surfaces one per 150 ms, so a chase costs bare
  decode and an overloaded machine degrades to a slideshow instead of
  a freeze.
- ~~Whether Nexira's existing background "animated" path (Coil) migrates to
  skinema or stays separate until skinema proves itself~~ settled by the
  consumer rather than by this file, and settled the way it hoped: the two
  libraries split by whether the picture moves. Coil keeps static images --
  it fetches over the network and decodes through Skia, which is what it is
  good at -- and everything that moves goes through skinema, which is stated
  in the consumer's own build file. What forced the split is not a
  preference: `coil-gif` is Android-only and does not resolve on desktop at
  all, so there was no animated path in Coil to keep.

  It is a normal published dependency, not a source include: `skinema-compose`
  and `skinema-skiko` at 0.7.0, `skinema-natives` at 8.1.1-1, and the `decode`
  tier -- the same one M18 taught to write. Only the HOST classifier ships,
  because bundling all of them put ~35 MB of other platforms' libraries into
  every package. Eleven files use it, audio among them.

  That closes the first two clauses of the adoption bar below: on Central with
  bundled natives for its platforms, and adopted as an artifact. The third --
  a milestone without breaking changes -- is the one still running.
- HDR: PQ and HLG are tone-mapped to SDR in software on the RGBA path
  (ToneMap.kt) -- detected by color_trc, swscaled to 16-bit RGBA, then
  inverse-EOTF -> extended-Reinhard knee against BT.2408 diffuse white
  -> BT.2020->709 -> sRGB, all LUT-driven so the hot loop carries no
  per-pixel pow. The avfilter+tonemap route (zimg/libplacebo) was
  rejected on bundle size and the no-GPU rule; the BT.2020 matrix has
  been honored since M8. The two aesthetic knobs are the diffuse-white
  and assumed-peak nits in ToneMap.kt. Future: BT.2446 Method A and
  hue-preserving gamut compression for fidelity. Native-HDR passthrough
  needs Wayland colour-management plus a 10-bit Compose/Skia path and
  stays out of scope.
