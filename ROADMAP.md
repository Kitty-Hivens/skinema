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
| avfilter    | 11                  |

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
- On the Skiko side every previous `Image` must be closed when replaced --
  Skia objects hold native memory and waiting for the finalizer is a leak
  in practice.
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
  both seek modes in their intended roles. Known edge, documented not
  handled: nonzero start_time streams (TS captures) report positions
  offset against the container duration; no supported consumer format
  does this in practice.

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

- **M11 -- hardware decode (code DONE; bundle CI-pending, 2026-06-22).**
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
  AVCodecContext.get_format/hw_device_ctx and AVCodecHWConfig, from a re-run
  of tools/layout-oracle.c. Validated end to end on real VAAPI (a dev box):
  AUTO and REQUIRE engage, decode every frame, and stay pixel-faithful to
  software -- the acceptance suite (VideoDecoderHwTest) is gated behind
  SKINEMA_TEST_HWACCEL=1 so a GPU-less CI, and macOS's always-present
  VideoToolbox, are not silently exercised on a path this change cannot see.
  PENDING: the trimmed natives must enable the platform hwaccels
  (build-natives.sh carries the flags, UNVALIDATED until a natives.yml run;
  VAAPI adds a libva system dependency on Linux, like fontconfig) before a
  SHIPPED bundle decodes on the GPU -- system-FFmpeg development already
  does. NVDEC/NVENC/QSV/AMF and zero-copy GPU->Skiko interop are deferred.

- **M12 -- software encode + mux (video + audio DONE; bundle pending, 2026-06-22).**
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
  PENDING: the trimmed-bundle encoder build -- the GPL flip plus
  x264/x265/SVT-AV1/libopus in build-natives.sh (accepted 2026-06-22; the
  bundle grows ~3x toward ~40 MB) and the README/section-10 licence rewrite
  that ships with it -- then M13 GPU encode on the same MediaWriter.

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
  forward-only one (the live-stream shape). Follow-ups: the same seam on
  AudioDecoder (audio-only streams) and a VideoPlayer entry point.

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
- Whether Nexira's existing background "animated" path (Coil) migrates to
  skinema or stays separate until skinema proves itself.
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
