# Getting started

## Dependencies

skinema publishes to Maven Central under `dev.hivens`. Add the Compose
integration module (it brings `-core` and `-skiko` transitively) plus
the native runtime for every platform you ship:

```kotlin
implementation("dev.hivens:skinema-compose:0.6.1")   // brings -core and -skiko
runtimeOnly("dev.hivens:skinema-natives:0.6.1:decode-linux-x64")
runtimeOnly("dev.hivens:skinema-natives:0.6.1:decode-linux-arm64")
runtimeOnly("dev.hivens:skinema-natives:0.6.1:decode-windows-x64")
runtimeOnly("dev.hivens:skinema-natives:0.6.1:decode-macos-arm64")
runtimeOnly("dev.hivens:skinema-natives:0.6.1:decode-macos-x64")
```

The natives classifier is `<tier>-<platform>`: pick one tier per platform.
`decode` (used here) is the complete LGPL player; `core` trims to the modern
essentials, `full` adds GPL software encode. See the README for what each
tier carries and its license.

If you are not on Compose, depend on `skinema-core` alone (the player
and decoders) and optionally `skinema-skiko` (frames as
`org.jetbrains.skia.Image`). The module split:

| Module            | Contents                                                | Runtime floor                    |
|-------------------|---------------------------------------------------------|----------------------------------|
| `skinema-core`    | FFM bindings, demux/decode, pacing, `VideoPlayer`       | JDK 22                           |
| `skinema-skiko`   | `VideoFrameImage`: frames as `org.jetbrains.skia.Image` | Skiko (provided by your Compose) |
| `skinema-compose` | `VideoSurface`, `rememberPlayerState`, `VideoScale`     | Compose Desktop                  |
| `skinema-natives` | trimmed FFmpeg in tiers, classifier jar per tier+platform| --                               |

The library is compiled to JVM 22 bytecode, because `java.lang.foreign`
(the FFM API) went final in 22. You run on any JDK 22 or newer.

## Native runtime

The `skinema-natives` classifier jars each carry a trimmed FFmpeg build
(shared libraries) for one tier and platform: `core` is the lean modern
decode set (LGPL), `decode` adds the libass subtitle stack and the broad
legacy/extended format set (LGPL), and `full` adds software encode (GPL,
x264/x265). On first use the matching jar unpacks into a per-user cache
keyed by a content fingerprint -- atomic and safe across concurrent
processes.

Without a natives jar on the classpath, skinema falls back to the
system's FFmpeg libraries (matched by exact soname). That is convenient
for development on Linux but is **not** what you ship -- a user machine
has no guarantee of a matching FFmpeg. Always ship the classifier jars
for your target platforms.

Override the native search path when you need to point at a specific
build (a locally compiled bundle, a debugging copy):

- JVM flag: `-Dskinema.libav.dir=/path/to/bundle`
- environment: `SKINEMA_LIBAV_DIR=/path/to/bundle`

Precedence is property, then environment, then the unpacked bundle,
then the system loader.

On NixOS the bundled (or override) jars work as-is -- they load by absolute
path. Only the *system-loader* fallback needs help: skinema looks up the
bare soname (`libavcodec.so.62`) and the nix store is not on the default
search path. If you rely on a system FFmpeg there, either set
`SKINEMA_LIBAV_DIR` to a directory that collects the libraries (a
`symlinkJoin` of `ffmpeg` + `libass` + `libwebp`), or put their nix lib dirs
on `LD_LIBRARY_PATH` (e.g. through `makeWrapper`). The soname is pinned to
FFmpeg 8.x, so use the matching package -- `ffmpeg_7` ships `libavcodec.so.61`
and will not resolve.

## The native-access flag

skinema's FFM calls are restricted methods. On JDK 24 and newer the JVM
warns on every restricted call unless you grant access at launch. Add
one of:

```
--enable-native-access=ALL-UNNAMED
```

or grant your own named module. If you use Compose, Skiko needs the
same grant, so `ALL-UNNAMED` is the simple choice for an application
launcher.

## Your first player

```kotlin
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.compose.VideoSurface
import dev.hivens.skinema.compose.VideoScale
import java.nio.file.Path

val player = VideoPlayer(Path.of("background.webm"), loop = true)

// Compose Desktop:
VideoSurface(player, Modifier.fillMaxSize(), scale = VideoScale.Cover)
```

`VideoPlayer` opens the file on its own decode thread and never throws
from the constructor: an unplayable file surfaces as
`VideoPlayer.State.Failed`, not an exception. Watch `player.state` (see
[video-player.md](video-player.md)) and show your own fallback when it
fails. Close the player when you are done with it -- it holds a decode
thread and native memory:

```kotlin
player.close()
```

If you are not on Compose, poll frames from your own render loop instead
of using `VideoSurface`:

```kotlin
player.acquireFrame()?.let { frame ->
    // frame.rgba (RGBA8888, stride width*4), frame.width, frame.height, frame.ptsNanos
}
```

See [compose.md](compose.md) for the draw-it-yourself path in full, and
[formats-and-behavior.md](formats-and-behavior.md) for the contract the
player guarantees.

## Next steps

- Sound: [audio.md](audio.md) -- pass `audio = true`.
- Subtitles: [subtitles.md](subtitles.md) -- off by default, enabled
  per track.
- Seeking, rate, stepping, metadata: [video-player.md](video-player.md).
