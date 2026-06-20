# Compose and custom rendering

## VideoSurface

`dev.hivens.skinema.compose.VideoSurface` draws a player's frames in a
Compose Desktop layout.

```kotlin
@Composable
fun VideoSurface(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    scale: VideoScale = VideoScale.Cover,
)
```

```kotlin
VideoSurface(player, Modifier.fillMaxSize(), scale = VideoScale.Cover)
```

It polls `acquireFrame` on every Compose frame (`withFrameNanos`), so a
hidden or detached window stops the pump for free. It draws pixels and
nothing else -- no spinner, no error glyph. Before the first frame and
while the player is `Failed`, it draws nothing; put your own loading and
fallback visuals around it, driven by `rememberPlayerState` (below).

`VideoSurface` handles two things a raw frame draw would miss:

- **Rotation.** It reads `player.rotationDegrees` and rotates the
  picture at draw time (a canvas transform, no pixel work), with the
  `Cover`/`Fit` math computed on the displayed dimensions.
- **Subtitles.** When a subtitle track is selected it composites the
  overlay in the video's own coordinate space -- glued to the picture,
  upright through any rotation -- and reports the on-screen size back to
  the player so libass rasterizes glyphs at display resolution.

## VideoScale

```kotlin
enum class VideoScale { Cover, Fit }
```

- `Cover` -- fill the bounds completely, cropping the overflow. The
  default; what a background wants.
- `Fit` -- fit the whole frame inside the bounds, letterboxing the
  remainder. What a preview wants.

Both scale uniformly and center the result.

## rememberPlayerState

```kotlin
@Composable
fun rememberPlayerState(player: VideoPlayer): VideoPlayer.State
```

`VideoPlayer.state` is a plain volatile with no listeners, invisible to
composition. `rememberPlayerState` polls it on the frame clock (one
volatile read per UI frame) and recomposes only when it changes. Use it
to gate your overlays:

```kotlin
val state = rememberPlayerState(player)
Box {
    VideoSurface(player, Modifier.fillMaxSize())
    when (state) {
        is VideoPlayer.State.Opening, is VideoPlayer.State.Seeking -> Spinner()
        is VideoPlayer.State.Failed -> FallbackImage()
        else -> {}
    }
}
```

## Drawing frames without Compose

`skinema-core` has no UI dependency. To render from your own loop
(LWJGL, AWT, a game engine), poll the player and hand the bytes to your
texture path:

```kotlin
player.acquireFrame()?.let { frame ->
    upload(frame.rgba, frame.width, frame.height)  // RGBA8888, stride width*4, straight alpha
}
```

`acquireFrame` returns `null` when there is nothing newer -- keep the
previous texture. If your content can be rotated (phone footage), apply
`player.rotationDegrees` yourself; `VideoSurface` is the only thing that
does it automatically.

### skinema-skiko

If you render through Skia but not Compose, `skinema-skiko` turns frame
bytes into a `org.jetbrains.skia.Image`:

```kotlin
val frameImage = VideoFrameImage()   // AutoCloseable
// per frame, on the thread that draws:
player.acquireFrame()?.let { f ->
    val image: Image = frameImage.update(f.width, f.height, f.rgba)
    canvas.drawImage(image, x, y)
}
// on teardown:
frameImage.close()
```

`VideoFrameImage` is single-threaded by contract -- call `update` from
the same thread that draws. Each `update` raster-copies the pixels into
a fresh `Image` and closes the previous one; Skia images hold native
memory, so you must close the last one yourself rather than wait for a
finalizer.

For subtitle overlays drawn this way, `SubtitleOverlayImage` does the
same for the positioned subtitle patches returned by
`player.acquireSubtitles()` -- see [subtitles.md](subtitles.md).
