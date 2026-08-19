# VideoPlayer

`dev.hivens.skinema.player.VideoPlayer` is the whole player for one
file. It owns a decode thread and a pacer thread (and, with sound, an
audio thread and its watchdog; a selected subtitle track adds a fifth),
opens the file asynchronously, and exposes its progress through volatile
fields you read on any thread. It is `AutoCloseable`.

## Constructing

```kotlin
VideoPlayer(
    path: Path,
    loop: Boolean = true,
    audio: Boolean = false,
    explicitClock: MediaClock? = null,
    sink: PcmSink? = null,
    readAheadFrames: Int = 1,
    audioTrack: Int? = null,
    hardware: HwAccel = HwAccel.OFF,
)
```

- `path` -- the file to play.
- `loop` -- restart at EOF instead of ending. Default `true` (skinema's
  first job is looping backgrounds).
- `audio` -- decode and play sound. Default `false`. See
  [audio.md](audio.md).
- `explicitClock`, `sink` -- test and advanced seams; leave them at the
  defaults for normal use. `explicitClock` forces a `MediaClock`
  (otherwise the player picks the audio clock when sound is present,
  the wall clock otherwise); `sink` substitutes the audio output.
- `readAheadFrames` -- decoded-frame inventory depth, clamped to 1..8.
  Default 1. See the read-ahead note in
  [formats-and-behavior.md](formats-and-behavior.md).
- `audioTrack` -- start on a specific audio stream index instead of the
  container default.
- `hardware` -- GPU decode policy. `HwAccel.OFF` (default) is pure
  software decode, the historical behaviour. `HwAccel.AUTO` uses the
  platform's GPU decoder when one is present and falls back to software
  per file otherwise; `HwAccel.REQUIRE` fails the open (`Failed`) when
  hardware decode cannot be set up. The RGBA frame contract is identical
  on every path -- frames still come back through system memory, so this
  buys decode cost, not a zero-copy path.

Whether the GPU actually took the stream is only knowable once a frame
has come back from it, so read it rather than assume it:

```kotlin
val hardwareActive: Boolean   // @Volatile
```

It answers false for a file the GPU opened and then fell back on -- a
4:4:4 H.264 stream, say, which a device advertises and refuses in
practice.

The constructor returns immediately; the file opens on the decode
thread. It never throws for a bad file -- watch `state`.

## Lifecycle and state

```kotlin
val state: VideoPlayer.State   // @Volatile, read anywhere
```

`State` is a sealed interface:

| State        | Meaning                                                      |
|--------------|-------------------------------------------------------------|
| `Opening`    | Initial; the decode thread is opening the file.             |
| `Playing`    | Frames are advancing.                                       |
| `Paused`     | Frozen; the last frame stays on screen.                     |
| `Seeking`    | A landing is in flight (a loading affordance can show).     |
| `Ended`      | EOF with nothing left to play -- see below.                 |
| `Failed`     | `Failed(cause: Throwable)` -- open or mid-decode error.     |
| `Closed`     | `close()` has run.                                          |

`Ended` is not only for non-looping files: a looping player ends too
when a lap produces no frames at all, because the next lap has nothing
to produce either. `Failed` is terminal -- the decode thread is gone, so
a later `close()` finds nothing to join and the state stays `Failed`.

On Compose, wrap this in observable state with `rememberPlayerState`
(see [compose.md](compose.md)) instead of polling the volatile
yourself.

Control playback:

```kotlin
fun pause()
fun resume()
fun positionNanos(): Long   // current media position; 0 until playback starts
```

`pause` freezes on the current frame; `resume` continues without a
jump. `close()` tears the player down -- it stops the threads and frees
native memory, waiting up to five seconds for the decode thread to exit.

That wait is a bound on `close()`, not on the teardown: the decode
thread's own shutdown joins the pacer, the audio pipeline and the
subtitle pipeline in turn, and a device in the middle of an outage can
hold the audio join for seconds. So `close()` can return while the last
of that is still running. The state settles `Closed` once it finishes --
and stays `Failed` if the player had already failed, since a failure is
the more useful thing to have been told.

## Frames

```kotlin
fun acquireFrame(): FrameSlot?
```

Returns the newest frame, or `null` meaning "nothing newer than what
you already have" -- keep showing the last one. You own the returned
`FrameSlot` until your next `acquireFrame` call; do not hold it across
calls. The slot:

```kotlin
class FrameSlot {
    val width: Int
    val height: Int
    val ptsNanos: Long
    val rgba: ByteArray   // RGBA8888, straight alpha, stride = width*4
}
```

`VideoSurface` calls `acquireFrame` for you on the Compose frame clock.
Drive it yourself only when you render outside Compose (see
[compose.md](compose.md)).

## Seeking

```kotlin
fun seek(ptsNanos: Long, exact: Boolean = true)
fun seekBy(deltaNanos: Long, exact: Boolean = true)
```

- `exact = true` (default) is frame-precise: the player shows the
  target keyframe as a preview immediately, then lands on the exact
  frame as it decodes forward. Use this for timeline scrubbing.
- `exact = false` lands directly on the keyframe at or before the
  target -- picture and sound together, position as coarse as the
  file's keyframe spacing. Use this for skip buttons.

`seekBy` accumulates: rapid presses coalesce into one landing at the
final destination rather than one landing per press. Both resolve on
the decode thread.

## Frame stepping

```kotlin
fun stepForward()
fun stepBackward()
```

Both leave the player paused on the stepped frame with time (and sound,
if any) re-anchored there. `stepForward` is cheap. `stepBackward` is
honest about variable frame rate: on sparse-keyframe content it costs a
decode run from the previous keyframe to learn the predecessor frame;
the run is memoized, so a repeated backstep does not pay twice.

## Playback rate

```kotlin
fun setRate(rate: Float)        // clamped to [0.5, 4.0]
val rate: Float                 // @Volatile, current rate
```

Plays at 0.5x to 4x with the pitch preserved (FFmpeg's atempo). The
rate survives seeks, pauses and track switches. See [audio.md](audio.md)
for how it interacts with the audio clock.

## Volume

```kotlin
fun setVolume(volume: Float)    // linear 0..1
```

A no-op for a silent player. See [audio.md](audio.md).

## Metadata

Read these volatile fields after the file opens (they are populated as
the decode thread learns them; empty/`null`/`0` while `Opening`):

```kotlin
val durationNanos: Long?            // one lap; null for animated webp (declares none)
val tags: Map<String, String>      // format-level tags (title, artist, ...)
val chapters: List<Chapter>        // start/end/title markers
val coverArt: ByteArray?           // encoded png/jpeg bytes, as stored
val rotationDegrees: Int           // 0/90/180/270, clockwise, from phone orientation
```

`Chapter` carries `startNanos`, `endNanos`, `title`. `coverArt` is the
container's embedded art as the stored bytes -- decode it with your own
image stack. A file whose only video stream is the cover plays
frameless (no `acquireFrame` frames), with `coverArt` exposed and sound
intact.

`rotationDegrees` is the clockwise rotation a renderer must apply.
`VideoSurface` applies it for you; if you draw frames yourself, you
must rotate by it (see [compose.md](compose.md)).

## Audio tracks and subtitles

Those surfaces have their own pages:

- audio track enumeration and live switching: [audio.md](audio.md)
- subtitle enumeration, selection and external files:
  [subtitles.md](subtitles.md)

## Threading note

Every field above is `@Volatile` and safe to read from any thread;
every method is safe to call from any thread (commands are marshalled
onto the decode thread internally). You do not synchronize around the
player. The only rule is the `FrameSlot` ownership window: the slot
from `acquireFrame` is yours only until the next `acquireFrame`.
