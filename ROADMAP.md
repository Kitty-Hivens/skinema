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

In: local files; play, loop, seek-to-frame; RGBA software pipeline; HDR
tone-mapped down to SDR (detect transfer characteristics, apply one
tonemap, done); alpha preserved where the codec carries it (VP9/webm);
GIF / APNG / animated WebP through the same pipeline (covers the consumer's
"animated background" category for free).

Out (explicitly, revisit only with a consumer in hand): hardware decode
and GPU YUV shaders; network sources and custom AVIO; Dolby Vision
profile 5 (proprietary colorspace -- out of scope forever unless
licensing changes).

Audio is not in v0.1 but is no longer indefinite: the clock seam is in
place (section 3) and audio lands as milestone M5 with its own consumer,
at an unhurried pace.

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
- **M4 -- publish.** Maven Central under dev.hivens (the libtray release
  pipeline is the precedent); README compat-policy statement; v0.1.
- **M5 -- audio.** Audio stream decode + swresample to PCM, one
  javax.sound.sampled sink per player, the sink-backed MediaClock takes
  over pacing, A/V sync across seek/loop/underrun. Consumer: the
  music-player direction in the primary consumer's backlog. The seam
  already exists (section 3), so nothing inverts -- this is addition,
  not surgery.

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
- Whether Nexira's existing background "animated" path (Coil) migrates to
  skinema or stays separate until skinema proves itself.
