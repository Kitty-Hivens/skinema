# skinema

Video decode and playback for JVM desktop apps. FFmpeg through
hand-written Java FFM (Panama) bindings, frames out as raw RGBA or Skia
images, a Compose Desktop surface on top. No JNI wrapper stacks, no
embedded player engines, and no network access -- the bundled FFmpeg is
built with `--disable-network`, so the library physically cannot perform
I/O beyond the file you hand it.

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
exposed, sound intact.

## Dependencies

```kotlin
implementation("dev.hivens:skinema-compose:0.4.0")   // brings -core and -skiko
runtimeOnly("dev.hivens:skinema-natives:0.4.0:linux-x64")
runtimeOnly("dev.hivens:skinema-natives:0.4.0:windows-x64")
runtimeOnly("dev.hivens:skinema-natives:0.4.0:macos-arm64")
runtimeOnly("dev.hivens:skinema-natives:0.4.0:macos-x64")
```

The natives jars carry a trimmed FFmpeg (decode-only, LGPL, 2-5 MB per
platform) plus libwebp; on first use they unpack to a per-user cache.
Without a natives jar, skinema looks for matching system libraries --
fine for development, not what you ship.

## What it plays

|                 |                                                                                               |
|-----------------|-----------------------------------------------------------------------------------------------|
| Containers      | mp4/mov/m4a, webm/mkv, gif, apng, webp, ogg, mp3, flac, wav, raw ac3/eac3                     |
| Video           | H.264, HEVC, VP8, VP9 (incl. webm alpha), AV1 (dav1d), MJPEG                                  |
| Animated images | GIF, APNG, animated WebP -- the latter via libwebp, which plain FFmpeg cannot decode          |
| Audio           | AAC, AC-3/E-AC-3, ALAC, Opus, Vorbis, MP3, FLAC, WAV PCM -- the device clock masters A/V sync |
| Pixels out      | RGBA8888, straight alpha, exact-pts pacing                                                    |

Audio: pass `audio = true` to `VideoPlayer` -- aac, ac3/eac3, alac,
opus, vorbis, mp3, flac and WAV pcm (16/24/32-bit and float) decode
through the same bindings, multichannel downmixes to stereo, and the
audio device becomes the player's clock (video follows sound, never the
reverse). Audio-only files play frameless. Files with several audio
tracks expose them (`audioTracks`, language and title included) and
switch in place (`selectAudioTrack`) -- the picture keeps playing and
the sound re-anchors at the playhead. HDR content plays through a
naive SDR conversion for now -- washed out, not tone-mapped; proper
tone-mapping is a roadmap item.

## Behavior contract

- **Fail closed.** A file the pipeline cannot handle surfaces as
  `VideoPlayer.State.Failed` -- show your fallback. No partial recovery,
  no garbage frames, no hangs.
- **Drop late.** A slow consumer skips frames; the clock never lags.
- **Seeks answer immediately.** An exact seek previews its keyframe
  while the frame-precise landing decodes behind it; `exact = false`
  lands on the keyframe outright -- picture and sound at once, position
  as coarse as the file's keyframe spacing. Skip buttons want inexact;
  timeline scrubbing wants exact.
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
| `skinema-natives` | trimmed FFmpeg + libwebp, classifier jar per platform   | --                               |

ROADMAP.md is the project's working memory: every architectural
decision, with its reasoning, lives there.

## Compatibility policy

Decode correctness for well-formed files in the formats above is a bug
when broken. Exotic, damaged or adversarial files get the fail-closed
treatment by design; compatibility issues beyond that are triaged at the
maintainer's discretion.

## License

Apache-2.0 for skinema itself. FFmpeg is consumed as separate LGPL
shared libraries, dynamically linked, never statically embedded; license
texts ship inside every natives bundle. libwebp, libvpx and dav1d are
BSD-family.
