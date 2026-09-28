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

It redraws when the player has something new -- a frame, a subtitle
overlay, a change of state -- and at no other time, waiting on
`player.awaitChange` rather than polling on every Compose frame. So a 24 fps
file draws twenty-four times a second whatever the display's refresh rate,
and a paused player draws nothing at all.

It also tells the player whether anyone can see its window, through
`player.reportVisible`, and the player stops decoding for a hidden one on the
policy its `WhenUnwatched` names. By default a player you can hear plays on
without its picture, and a silent one pauses.

Nothing the toolkit reports says a window is hidden, short of minimising it,
which the surface takes from the Compose lifecycle. The rest it reads off
the frames. A window on screen answers for a frame within a refresh or two,
and a hidden one answers slowly or not at all. Measured under XWayland on
Hyprland, a window on a workspace that is not on screen, one behind a
fullscreen window and one in a hidden special workspace all answer about
once a second, while their lifecycle and their X11 state stay as they were
(focus is no guide either: a window moved to an unseen workspace keeps it).
The whole round trip, player stopping and coming back, was measured on the
unseen workspace. Skiko's macOS renderer, by its source, waits up to 300 ms
between frames of a covered window, so a covered macOS window is expected
to read the same way, though that has not been measured.

The rule: an unbroken run of at least three slow answers lasting two
seconds is a hidden window, a single request left unanswered for four
seconds is one too, and three quick answers in a row bring it back. One
long answer on its own (a renderer setting up, a collection on the UI
thread) is not enough. What it cannot tell from a hidden window is a
visible one that stays slower than 200 ms a frame for seconds on end.

What this cannot see is a window that keeps drawing at full speed behind
another. Skiko's Windows renderers ask only whether the component is
showing, going by their source, so a covered window there most likely draws
on as usual and the player keeps decoding for it. If you know when your
picture is out of sight, say so with `player.setPresenting(false)` and
`setPresenting(true)`, which is exact on every platform and outranks what the
surface reports. Saying it once takes both the surface's reports and the
mailbox notice out of play for good, so say both directions from then on.

It draws pixels and nothing else -- no spinner, no error glyph. Before the
first frame and while the player is `Failed`, it draws nothing; put your own
loading and fallback visuals around it, driven by `rememberPlayerState`
(below).

**One surface per player.** The mailbox hands each published frame to
whichever reader polls first -- that single-reader rule is what makes the
handoff copy-free -- so two surfaces drawing one player take turns instead
of both seeing everything: each gets part of the frames, neither gets them
all, and the two show different pictures. Nothing fails, so it reads as
choppy video rather than as a mistake; the second surface says so on
stderr. Two views of one file means two players. The two surfaces also
both report what they can see to the one player, so a surface in a hidden
window can stop the player while the other surface is on screen.

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
composition. `rememberPlayerState` waits on `player.awaitChange` and
recomposes only when the state changes; it asks for no frames of its own,
so a spinner's worth of state does not keep the window redrawing. Use it
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

To redraw only when there is something new rather than on every refresh
of your display, wait on the player's change count between draws:

```kotlin
var seen = player.changeCount
while (running) {
    player.acquireFrame()?.let { upload(it.rgba, it.width, it.height); redraw() }
    seen = player.awaitChange(seen, 1_000_000_000L)  // blocks, returns early on any change
}
```

The count moves when a frame or a subtitle overlay is published, when a
subtitle track is selected or dropped, when `state` changes, and when a
`setSource` is refused. Read it
before you look at the player and wait with that reading, and nothing that
happens in between is missed.

A loop like this that stops when its window stops drawing is noticed by the
player on its own: a mailbox that was being read and is not any more. If your
window keeps drawing while nobody can see it, and you can tell, say so with
`player.reportVisible(visible)` the way `VideoSurface` does, or with
`setPresenting` if the decision is the application's rather than the
renderer's.

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
positioned patches from `player.acquireSubtitles()` into placed images, and it
keeps the same borrow rule: `update` may run wherever your raster runs, and
what `images` hands the drawing thread stays alive until that thread reads it
again. One read per draw, same as above.

Its `close` is the one thing that differs. On the frame holder it is a
teardown and shuts the door; here it frees what is held and leaves the object
usable, because turning subtitles off is a reason to drop the pixels while the
surface lives on and a re-selection has to be able to publish again. Stop
whatever calls `update` before tearing down, the way `VideoSurface` joins its
raster thread. See [subtitles.md](subtitles.md).
