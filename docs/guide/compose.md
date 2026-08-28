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
    background: Color? = null,
)
```

```kotlin
VideoSurface(player, Modifier.fillMaxSize(), scale = VideoScale.Cover)
```

It polls `acquireFrame` on every Compose frame (`withFrameNanos`), so a
hidden or detached window stops polling for free -- Compose runs no
frame clock for it. The player notices the mailbox going unread and stops
decoding for it, on the policy its `WhenUnwatched` names; say the moment
exactly with `player.setPresenting(...)` if you would rather not wait for
it to be noticed. It draws pixels and
nothing else -- no spinner, no error glyph. Before the first frame and
while the player is `Failed`, it draws nothing; put your own loading and
fallback visuals around it, driven by `rememberPlayerState` (below).

**One surface per player.** The mailbox hands each published frame to
whichever reader polls first -- that single-reader rule is what makes the
handoff copy-free -- so two surfaces drawing one player take turns instead
of both seeing everything: each gets part of the frames, neither gets them
all, and the two show different pictures. Nothing fails, so it reads as
choppy video rather than as a mistake; the second surface says so on
stderr. Two views of one file means two players.

`VideoSurface` handles two things a raw frame draw would miss:

- **Rotation.** It reads `player.rotationDegrees` and rotates the
  picture at draw time (a canvas transform, no pixel work), with the
  `Cover`/`Fit` math computed on the displayed dimensions.
- **Subtitles.** When a subtitle track is selected it composites the
  overlay in the video's own coordinate space -- glued to the picture,
  upright through any rotation -- and reports the on-screen size back to
  the player so libass rasterizes glyphs at display resolution.
- **Letterbox.** `background` paints the bounds under the picture, so
  `Fit`'s bars are a colour you choose rather than whatever is composed
  behind the surface. It is painted with the frame, never before one:
  until the first frame arrives, and on a failed player, the surface still
  draws nothing at all, so your own fallback shows through.

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
// wherever you raster -- it does not have to be the drawing thread:
player.acquireFrame()?.let { f ->
    frameImage.update(f.width, f.height, f.rgba)
}
// on the thread that draws:
frameImage.reclaim()                 // optional: gives back the last draw's image
frameImage.image?.let { canvas.drawImage(it, x, y) }
// on teardown:
frameImage.close()
```

The raster copy is the expensive part -- eight megabytes a frame at
1080p, four times that at 4K -- so `update` is built to run off the
thread that draws, which is where `VideoSurface` runs it. That is why a
replaced image is not closed the moment it is replaced: the drawing
thread may still be painting with it.

**You get one live image per side, and the class frees the rest.**

- What `update` returns is yours until your next `update` publishes over it.
- What `image` returns is yours until your next read of `image`.

Everything older than those two is unreachable -- you have no way to name it
again -- so `VideoFrameImage` closes it itself as it publishes. Native memory
is therefore bounded at one superseded frame no matter what the caller does,
and `close` frees what is left at teardown.

The one rule that leaves: read `image` **once** per draw and do not keep the
result. A drawer that reads twice and holds both can have the older one closed
under it. One read per draw is what a painter does anyway.

`reclaim` is now optional. It gives the drawing side's image back at the start
of a draw rather than at the next publish -- one frame of native memory held
for one frame less -- so it is worth calling in a loop that knows where its
draw begins, and nothing leaks if you never call it. `VideoSurface` calls it.

This is the fix for the failure the class used to allow: retiring into a queue
that only `reclaim` drained gave a caller who forgot it no error, no ceiling
and no signal beyond process size, because the queue held a strong reference
and a heap profiler shows none of it. Measured on such a caller at 1080p: two
hundred frames took resident memory from 250 MB to 1796 MB, and one `reclaim`
put it back to 245. `pending` reports what is still spoken for, and is one at
most.

`update` answers `null` once `close` has run, which is what a raster
already in flight when the surface goes away comes back with.

For subtitle overlays drawn this way, `SubtitleOverlayImage` turns the
positioned patches from `player.acquireSubtitles()` into placed images. It
does **not** follow the borrow rule above -- its `update` closes what it
replaces on the spot, so it belongs on the thread that draws. An overlay is a
handful of small patches, where a frame is eight megabytes; the borrow exists
to get that copy off the drawing thread, and there is nothing here to get off
it. See [subtitles.md](subtitles.md).
