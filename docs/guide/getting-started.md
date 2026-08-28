# Getting started

## Dependencies

skinema publishes to Maven Central under `dev.hivens`. Add the Compose
integration module (it brings `-core` and `-skiko` transitively) plus
the native runtime for every platform you ship:

```kotlin
implementation("dev.hivens:skinema-compose:0.7.0")   // brings -core and -skiko
runtimeOnly("dev.hivens:skinema-natives:8.1.1-1:decode-linux-x64")
runtimeOnly("dev.hivens:skinema-natives:8.1.1-1:decode-linux-arm64")
runtimeOnly("dev.hivens:skinema-natives:8.1.1-1:decode-windows-x64")
runtimeOnly("dev.hivens:skinema-natives:8.1.1-1:decode-windows-arm64")
runtimeOnly("dev.hivens:skinema-natives:8.1.1-1:decode-macos-arm64")
runtimeOnly("dev.hivens:skinema-natives:8.1.1-1:decode-macos-x64")
```

`skinema-natives` carries its own version -- the FFmpeg build in the bundles
plus a repack revision -- because the bundles change far less often than the
library does. It is not expected to match the library version; the release
notes name the pair.

The natives classifier is `<tier>-<platform>`: pick one tier per platform.
`decode` (used here) is the complete LGPL build -- the whole player, and every
encoder that adds no GPL surface; `core` trims to the modern decode essentials;
`full` adds the software H.264 and HEVC encoders and is GPL because they are.
See the README for what each tier carries and its license.

If you are not on Compose, depend on `skinema-core` alone (the player
and decoders) and optionally `skinema-skiko` (frames as
`org.jetbrains.skia.Image`). The module split:

| Module            | Contents                                                | Runtime floor                    |
|-------------------|---------------------------------------------------------|----------------------------------|
| `skinema-core`    | FFM bindings, demux/decode, pacing, `VideoPlayer`       | JDK 22                           |
| `skinema-skiko`   | `VideoFrameImage`/`SubtitleOverlayImage`: pixels as `org.jetbrains.skia.Image` | Skiko (provided by your Compose) |
| `skinema-compose` | `VideoSurface`, `rememberPlayerState`, `VideoScale`     | Compose Desktop                  |
| `skinema-natives` | trimmed FFmpeg in tiers, classifier jar per tier+platform| --                               |

The library is compiled to JVM 22 bytecode, because `java.lang.foreign`
(the FFM API) went final in 22. You run on any JDK 22 or newer.

## Native runtime

The `skinema-natives` classifier jars each carry a trimmed FFmpeg build
(shared libraries) for one tier and platform: `core` is the lean modern
decode set (LGPL); `decode` adds the libass subtitle stack, the broad
legacy/extended format set, and the encoders that carry no GPL surface --
AV1, Opus, AAC, FLAC and the VAAPI pair (LGPL); `full` adds libx264 and
libx265 and is GPL for them alone. On first use the matching jar unpacks
into a per-user cache keyed by a content fingerprint -- atomic and safe
across concurrent processes.

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

On NixOS the bundle needs one step, because loading by absolute path places
only the library itself -- the libraries IT needs still go through the normal
search, and the nix store is not on it. The `core` tier asks for nothing but
the C library and works untouched; the desktop tiers additionally want libva
(GPU decode) and fontconfig (subtitle text), so they need those store paths
reachable. The usual nixpkgs treatment for a prebuilt binary applies, because
the bundle now carries a RUNPATH to extend:

```nix
# In a derivation that unpacks the natives jar:
nativeBuildInputs = [ autoPatchelfHook ];
buildInputs = [ libva fontconfig zlib ];   # zlib only on pre-0.8 bundles
```

`autoPatchelfHook` rewrites each library's RUNPATH to the store paths it
needs, and everything loads. Without a derivation, the same effect comes from
putting those lib dirs on `LD_LIBRARY_PATH` (through `makeWrapper` on the
consuming app).

If you would rather use a system FFmpeg than the bundle, point
`SKINEMA_LIBAV_DIR` at a directory collecting the libraries (a `symlinkJoin`
of `ffmpeg` + `libass`). The soname is pinned, so the package must
match the pinned major -- with the n9.0 pin that is `ffmpeg_9`
(`libavcodec.so.63`); `ffmpeg_8` ships `.so.62` and will not resolve.

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
