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
    unwatched: WhenUnwatched = WhenUnwatched.Freeze,
    startPaused: Boolean = false,
    volume: Float = 1f,
)
```

- `path` -- the file to play.
- `loop` -- restart at EOF instead of ending. Default `true` (skinema's
  first job is looping backgrounds).

  Turn it off for a still image. A single PNG or JPEG is a legal input and
  decodes like anything else, but it declares no duration, so the player
  observes one from the first lap -- one frame at the container's default
  rate, 40 ms -- and then honours it. Measured: about thirty laps a second,
  each reopening the demuxer, for one percent of a core and a picture that
  never changes. With `loop = false` the same frame stands on screen, the
  state reads `Ended`, and the cost is nothing at all. If you hand the player
  whatever a user dropped on it, pick `loop` from what arrived rather than
  taking the default.
- `audio` -- decode and play sound. Default `false`. See
  [audio.md](audio.md).
- `explicitClock` -- forces a `MediaClock`; leave it at the default for
  normal use, where the player picks the audio clock when sound is present
  and the wall clock otherwise.
- `sink` -- where the sound goes. The default opens a platform line; an
  implementation of `PcmSink` takes the PCM instead, which is how a consumer
  plays through its own audio stack. Ignored entirely with `audio = false`.
  See [audio.md](audio.md).
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
- `unwatched` -- what the timeline does while nobody is taking the
  picture. See the `WhenUnwatched` discussion below.
- `startPaused` -- open onto the first frame and stay on it. `state`
  settles `Paused` rather than `Playing`, and `resume()` is what starts
  the file; the picture is up before that, because the first frame
  publishes the way a seek landing does. With sound, nothing reaches the
  line at all: the audio side is told at construction rather than paused
  by a command, which would arrive after the device had already started
  -- the sink opens on the audio thread's own schedule, while the decode
  thread is still inside the video open. It is a caller's pause, so
  `WhenUnwatched` never lifts it. Default `false`.
- `volume` -- linear 0..1 from the first sample onward, rather than from
  whenever a `setVolume` call gets through: the sink opens and takes its
  first chunk on the audio thread's own schedule, so there is no moment
  after the constructor that beats it. Applied to every line the player
  opens, so a track switch or a device-loss recovery comes back at the
  volume you asked for. Clamped; `NaN` leaves the default standing.
  Nothing at all without `audio = true`. Default `1f`.

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
| `Paused`     | Frozen; the last frame stays on screen. Also how a `Freeze` player reads while nobody takes the picture -- see below. |
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

```kotlin
fun setPresenting(presenting: Boolean)
```

`pause` freezes on the current frame; `resume` continues without a
jump. `setPresenting` says whether anyone is taking the picture -- a
window minimised, a tab switched away from, a wallpaper behind a
maximised app. A player nobody reads is not free: it decodes, converts
and paces pictures into a mailbox nothing empties.

What stopping costs the timeline is the `unwatched` constructor
parameter's to say. `WhenUnwatched.Freeze` (the default) stops time with
the picture and carries on from there, which is what a background wants;
`WhenUnwatched.KeepTime` lets time run on and rejoins the picture where
it got to, which is what a live source wants.

`Freeze` stops time with a real pause, so `state` reads `Paused` for as
long as it lasts -- a pause you never asked for, lifted by the next
`acquireFrame`. One you *did* ask for is never lifted that way: it
outlives the picture being wanted again. Calling `resume()` on a player
that paused itself is the same thing in reverse -- it takes the automatic
lift out of play and reports `Playing`, while nothing is decoded until
frames are actually being taken again.

Saying nothing is allowed. A mailbox that was being read and stops being
read is noticed on its own after a couple of seconds, and the next
`acquireFrame` undoes it -- so a consumer that never thinks about this
still stops burning a core behind a hidden window. Saying it once takes
the automatic notice out of play: a player told to stop presenting is not
revived by the polling its consumer does for some other reason.

`close()` tears the player down -- it stops the threads and frees native
memory -- and it is bounded: one second for the whole teardown, not one
second per side.

Every side is told to go before any of them is joined, so their exits
overlap instead of queueing, and a write sitting in the sink is broken
out of rather than waited out. That is what makes so short a bound safe:
by the time `close()` returns, nothing is writing into a sink you lent
the player, whether or not the last of the native teardown has finished.
What the budget buys on top of that is the certainty that the native
memory has gone too; past it the threads are daemons and finish on their
own. The state settles `Closed` when they do -- and stays `Failed` if the
player had already failed, since a failure is the more useful thing to
have been told.

```kotlin
fun closeAsync()
```

The same teardown without the wait, for a caller that cannot block at
all -- a dispose on a UI thread. It tells every side to go and returns.
The sink comes back on the same terms as `close()`; what is given up is
only the certainty that the native memory went with it.

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

`positionNanos()` and `durationNanos` are the timeline together, and the
position is deliberately **not** clamped to the duration. A container's
declared duration is not a number libav guarantees in either direction, and
clamping made a file that understates itself pin its position while the
picture went on playing -- measured at 500 ms reported against 2900 ms of
frames actually shown. The overshoot is the smaller lie: at the end of a lap
the position can read tens of milliseconds past `durationNanos`. Clamp it in
your own progress bar if that matters there.

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

Every field above is `@Volatile` and safe to read from any thread, and
every method is safe to call from any thread. You do not synchronize
around the player.

Most methods work by queueing a command for the decode thread, which is
why order is preserved among them and nothing races. Four do not. All are
safe; the distinction only matters when you reason about when an effect
lands, or about what an effect is ordered against.

- `setVolume` runs on your own thread, straight through to the sink.
- `setSubtitleCanvasSize` hands the subtitle thread a command from your
  thread, without going past the decode thread at all -- and only when the
  size differs from the last one announced, so a draw loop may post it
  every frame.
- `addExternalSubtitles` probes the file on your thread and has published
  the tracks by the time it returns, which is why the list it hands back
  is usable at once.
- `selectAudioTrack` queues -- onto the audio thread's own queue, not the
  decode thread's. It is therefore ordered against other audio work and
  not against a `seek` you issued a moment earlier, which reaches the
  audio side only when the decode thread gets to it.

The one rule is the ownership window: the `FrameSlot` from
`acquireFrame` is yours only until the next `acquireFrame`, and the same
holds for the overlay from `acquireSubtitles`.
