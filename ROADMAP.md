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

Pinned line: **FFmpeg n8.1.x, LGPL, shared** (pinned 2026-06).

| Library     | soname major (n8.1) |
|-------------|---------------------|
| avutil      | 60                  |
| swresample  | 6                   |
| swscale     | 9                   |
| avcodec     | 62                  |
| avformat    | 62                  |

Rules:

- Load by exact soname (`libavformat.so.62`), never the bare name -- majors
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
--disable-programs --disable-network`, then a whitelist: demuxers
mov/mp4, matroska/webm, gif, apng, image2; video decoders h264, hevc,
vp8, vp9, av1 (libdav1d -- the native decoder is too slow for 1080p),
mjpeg, png, webp; audio decoders aac, opus, vorbis, mp3, flac (cheap now,
needed when audio lands); libswscale + libswresample. Expected size:
8-12 MB per platform against ~70 MB for a full build.

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

## 6. Frame pipeline

```
decode thread:  av_read_frame -> send_packet -> receive_frame (YUV)
                -> sws_scale into a reused RGBA buffer -> publish
UI side:        latest-frame mailbox; present when pts <= now; drop late
```

- Decoded video is YUV; swscale converts to RGBA at one chokepoint. The
  whole format zoo collapses there.
- 1080p RGBA is ~8 MB/frame, ~250 MB/s at 30 fps -- trivial memcpy
  bandwidth, but **buffers must be reused** (double/triple buffering);
  allocating per frame churns the GC.
- On the Skiko side every previous `Image` must be closed when replaced --
  Skia objects hold native memory and waiting for the finalizer is a leak
  in practice.
- Variable frame rate is normal, not an edge case: pacing is by pts, never
  by an assumed 1/fps step.

## 7. v0.1 scope

In: local files; play, loop, seek-to-frame; RGBA software pipeline; alpha preserved where the codec carries it (VP9/webm);
GIF / APNG / animated WebP through the same pipeline (covers the consumer's
"animated background" category for free).

Out (explicitly, revisit only with a consumer in hand): hardware decode
and GPU YUV shaders; network sources and custom AVIO; Dolby Vision
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
- FFmpeg: LGPL build (decode-only needs no GPL components), **dynamically
  linked shared libraries only**, license texts shipped, source of the
  exact build referenced (BtbN tag or our CI artifact). Static linking is
  off the table -- it would change the licensing story.
- Natives packaging: per-OS/arch classifier jars (the lwjgl/skiko
  pattern) carrying the trimmed runtime plus an `index.txt`; NativeBundle
  deploys them to a fingerprint-keyed per-user cache (atomic, race-safe).
- Natives delivery is asynchronous by design: every platform build
  uploads independently to the rolling `natives-<ffmpeg version>` release
  the moment it passes its on-runner acceptance suite. A queued or broken
  platform delays only itself -- never a release, never the other
  platforms -- and a rebuild replaces just its own asset. Matches the
  platform tier model: a missing community-tier build degrades that
  platform, nothing else. macos-x64 is cross-compiled on the arm runner
  (GitHub's Intel macs queue for days to months) and ships without an
  on-metal acceptance run -- an arm JVM cannot load x86_64 dylibs --
  which is exactly what community tier means.

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
  profile. Run it: `./gradlew :skinema-core:spike -Pinput=<video>
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
- **M2 -- skiko + compose: modules DONE (2026-06-10); Nexira wiring
  pending.** VideoFrameImage raster-copies a frame into a Skia image and
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
  h264 was never suite-covered before); SKINEMA_REQUIRE_DECODE makes CI
  fail loudly instead of skip-faking green; the test matrix runs
  Linux/Windows (pinned BtbN via the dir override -- the same mechanism
  the bundles use) and macOS (brew, until our mac builds land), all
  three green; packaging decided and implemented -- per-platform
  classifier jars with an index.txt, deployed by NativeBundle into a
  fingerprint-keyed per-user cache (atomic rename, crash/race-safe,
  upgrades never overwrite mapped libraries), load precedence
  prop > env > bundle > system; the headless soak runner measures the
  adoption bar (2-minute smoke: exact 30 fps pacing, RSS flat after
  warm-up). The natives workflow is green on all four platforms --
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
  confirms both fixes: the intermittent freeze is gone. STILL OPEN, now
  cosmetic: the deterministic ~one-buffer hold after a seek (the device
  reports no progress until the line refills). The wall-time spin-up
  extrapolation in AudioClock would mask it at the cost of up to
  ~one-buffer picture-ahead-of-sound drift -- that decision still waits,
  and may no longer be worth taking now that both intermittent
  components had different, fixed roots. Nothing blocks 0.2.0 anymore.

Adoption bar (the primary consumer): the launcher takes skinema as a
normal published dependency once 0.x is on Maven Central with bundled
natives for its official platforms, the background harness has survived
a long soak without RSS growth, and the API has gone a full milestone
without breaking changes. Not before.

## 12. Version pins (2026-06)

| Component  | Version        | Note                                   |
|------------|----------------|----------------------------------------|
| JDK floor  | 22             | java.lang.foreign final                |
| Toolchain  | 25 (LTS)       | foojay resolver fetches if absent      |
| Kotlin     | 2.4.0          | matches the primary consumer           |
| Compose    | 1.11.0         | matches the primary consumer           |
| Skiko      | 0.144.6        | what Compose 1.11.0 ships; compileOnly |
| Gradle     | 9.5.1 (wrapper)|                                        |
| FFmpeg     | n8.1.x         | sonames in section 4                   |

## 13. Open questions

- ~~Core API shape~~ resolved in M1: poll-based, no coroutines in core;
  skinema-compose owns the adaptation.
- dav1d in trimmed builds: build it ourselves or take BtbN's? (Spike does
  not care; M3 does.) Same question now applies to libvpx, which the
  alpha path requires.
- Windows/macOS arena + library unloading behavior on session close --
  verify during M3, libraryLookup lifetime is tied to an Arena.
- ~~The intermittent post-seek freeze~~ root-caused and fixed 2026-06-11
  (the awaitClockWrap park hole -- M5 section). Left behind: the
  deterministic ~one-buffer post-seek hold and the extrapolation
  decision. A read-ahead frame queue (decode 3-5 frames instead of one)
  is a separate candidate: the player decodes one frame ahead today, so
  any decode hiccup is immediately visible; a small queue would absorb
  jitter. Not needed for backgrounds (the latest-frame mailbox is
  deliberate there), but it is the player-scenario knob. The catch-up
  residue is resolved (2026-06-11): runs ride the convert=false
  drop-run behind a pure publish policy -- frames later than 250 ms
  drop unconverted, a starvation guard surfaces one per 150 ms, so a
  chase costs bare decode and an overloaded machine degrades to a
  slideshow instead of a freeze.
- Whether Nexira's existing background "animated" path (Coil) migrates to
  skinema or stays separate until skinema proves itself.
- HDR: today's reality is a naive swscale conversion (PQ content plays
  washed out; README says so honestly). Proper tone-mapping means either
  enabling avfilter+tonemap in the trim (real, costs size) or a small
  software tonemap on the RGBA path. Decide when a consumer actually
  brings HDR files.
