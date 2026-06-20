# Threading and clocks

This is the runtime: the threads of a `VideoPlayer`, how frames move
between them, and the clock that decides when a frame is due. It is the
part most worth understanding before changing anything, because the
bugs here are subtle and were expensive to find.

## The threads

A player has up to four threads, each owning a confined arena where it
touches native memory:

| Thread                | Created when         | Owns                                         |
|-----------------------|----------------------|----------------------------------------------|
| decode                | always               | the `FrameSource` (decoder arena)            |
| pacer                 | always (spawned by decode) | nothing native -- heap arrays, clock, mailbox |
| audio                 | `audio = true`       | `AudioDecoder`, the sink, the `TempoFilter`  |
| audio watchdog        | with the audio thread| read-only: sink position and clock           |
| subtitle              | on first `selectSubtitleTrack` | its own format context, libass renderer |

The decode thread and the pacer thread are the spine.

### Decode never presents

The decode thread only fills the `FrameQueue`; the pacer thread is the
sole publisher. This was a deliberate correction. A single-threaded
design (fill and release interleaved on one thread) was built first and
rejected on review: a stalled decode *is* the publisher, so inventory
could never present during a stall -- which is the entire point of
read-ahead. Moving presentation to its own thread means a slow frame,
disk hiccup or GC pause stops costing the screen while queued inventory
lasts. The pacer touches only heap arrays, the clock and the mailbox;
the FFM arena stays confined to the decode thread.

## FrameQueue: decode -> pacer

`FrameQueue` is a lock-based ring of `depth = readAheadFrames` cells.
The producer (decode) grabs a free cell, fills it, and commits; the
consumer (pacer) peeks the head and polls it. The poll is a zero-copy
handoff -- it swaps the cell's pixel array with the array the pacer
hands in:

```kotlin
fun poll(replacement: ByteArray): Frame? = synchronized(lock) {
    if (count == 0) return null
    val cell = cells[head]
    val out = Frame(cell.rgba, cell.width, cell.height, cell.ptsNanos, forcedFlags[head])
    cell.rgba = replacement          // array swap: no memcpy
    head = (head + 1) % cells.size
    count--; changes++
    lock.notifyAll()
    out
}
```

Mechanisms layered on the ring:

- **Forced frames.** A committed cell can be `forced`. Forced frames
  (seek previews, seek landings, chase guards) publish immediately,
  past the pacer's state gate and late policy. The decode side already
  decided they should show.
- **Change tick + awaitChange.** Every mutation bumps a `changes`
  counter and notifies. `awaitChange(sinceTick, timeoutNanos)` sleeps
  until the counter moves or the timeout elapses, so both sides park
  cheaply and wake on real events.
- **RoomFreed token.** When the queue is full, the decode side would
  otherwise discover a freed cell only when its command poll times out
  -- which capped production below 60fps and turned high-rate content
  into a guard-frame slideshow. The pacer now drops a `RoomFreed` no-op
  into the command queue after each publish, so the fill side wakes the
  moment a cell frees. The lesson, written into the design: a third
  actor's hand-offs need wake signals in both directions, and any
  timeout-discovered transition puts its whole timeout on the
  steady-state path.

## The pacer loop

The pacer waits until the head cell's pts is due against the clock,
then publishes by swapping the cell's array into the mailbox writing
slot. Two constants shape it:

- `PACE_RECHECK_NANOS = 50ms` caps every pace sleep. The audio thread
  re-anchors the clock asynchronously (it reads commands only between
  blocking writes), so a sleep computed against a stale reading could
  freeze the picture for the whole seek distance; the cap bounds that
  window to one period.
- The late policy, `shouldPublishLateFrame`: a frame more than
  `CHASE_DROP_NANOS = 250ms` late is dropped **unless** at least
  `CHASE_PUBLISH_INTERVAL_NANOS = 150ms` has passed since the last
  publish. So a catch-up run after a clock jump costs bare decode
  (frames drop unconverted) but still surfaces ~1 frame per 150ms, and
  an overloaded machine degrades to a slideshow instead of a freeze.

EOF, loop wrap and Ended all wait for the pacer to drain the queue tail
first (`eofPending`). A backward clock jump without a seek is read as a
loop wrap (by direction and a small `CLOCK_NOISE_NANOS` epsilon, not by
magnitude, so sub-second loops do not strand their tail): a pre-wrap
tail still in the queue is slammed out at the wrap rather than a lap
later.

## TripleBuffer: pacer -> consumer

The mailbox between the pacer and the consumer is a `TripleBuffer`:
three slots rotate roles so the producer always mutates a private slot,
`publish` swaps it with the pending slot, and `acquire` hands the
consumer the freshest published slot untouched until its next acquire.
Neither side mutates a slot the other holds, so big pixel buffers move
with zero copies and no torn reads, and a slow consumer simply skips
intermediate frames -- the drop-late policy made structural. It is
upstream-distinct from `FrameQueue`: the queue is the decode/pacer
handoff (depth = inventory), the TripleBuffer is the pacer/consumer
handoff (always three slots). The mailbox is rebuilt when frame
geometry changes.

## The clock hierarchy

Pacing depends only on `MediaClock`:

```kotlin
interface MediaClock {
    val isPaused: Boolean
    fun start(atMediaNanos: Long = 0L)
    fun pause(); fun resume()
    fun seek(mediaNanos: Long)
    fun mediaNanos(): Long
    fun nanosUntilDue(ptsNanos: Long): Long = ptsNanos - mediaNanos()
}
```

Two implementations:

- **PlaybackClock** -- wall time. Media position is the anchor plus
  `(now - anchorWall) * rate`. Used for silent playback. The video owns
  this clock and re-anchors it on seeks and loops.
- **AudioClock** -- the device. Media position is the anchor plus
  `(framesConsumed - baseFrames) * tempo` converted by the sample rate.
  A DAC plays samples at a fixed rate, so this is the honest position.
  When sound is present, the audio clock masters.

The player picks the clock once at startup:

```kotlin
clock = explicitClock ?: audioClock ?: PlaybackClock()
ownsClock = explicitClock != null || audioClock == null
```

`ownsClock` is the invariant that keeps the two clocks from fighting.
When audio masters (`ownsClock == false`), the video side **never**
calls `clock.seek` -- the audio thread re-anchors at its actual
landing, on seeks and on loop wraps. The video parks
(`awaitClockWrap`) until the audio restarts time. (The original
intermittent post-seek freeze was exactly a park that handled a seek
command with `decoder = null`, re-anchoring audio but never moving the
video; the fix runs park commands against the real decoder.)

AudioClock's three disciplined operations:

- **rebase(mediaNanos, sampleRate)** -- the one synchronized point a
  rate or track change can re-anchor *and* re-scale, atomically under
  the lock, so a new sample rate applies only forward and never
  rescales history.
- **detachToWallTime()** -- the failure hatch. If the device dies, the
  clock switches to extrapolating from wall time so the picture keeps
  moving.
- **monotonic clamp** -- `mediaNanos` never returns a value below the
  last one it returned (some backends reconcile `framePosition`
  non-monotonically around a flush/restart); the floor is cleared on a
  deliberate re-anchor.

## Seeks: the freeze-first handshake

A seek with sound is a coordination, not a jump:

1. The video side marks the seek in flight and tells the audio and
   subtitle pipelines (incrementing their `pendingSeeks`).
2. The audio thread **freezes first** -- `sink.stop()` then
   `sink.flush()` -- and only then reads the playhead and crops to the
   sample-precise anchor, re-anchoring the clock there. Freezing after
   reading would let the line play its buffered tail and step the
   mastered clock backward, which the pacer's invariants forbid.
3. The video lands by decoding forward to the target, publishing the
   keyframe as a forced preview while it works.
4. The video side calls `videoLanded()`; the audio thread restarts the
   sink against the now-standing clock.

Bursts coalesce: a run of seeks supersedes the in-flight landing to one
landing at the final target, and `pendingSeeks` counts how many are
still owed.

### The phantom chase

`pendingSeeks` also guards the late policy. The audio thread processes
seek commands only between blocking writes, so after a burst it can owe
several flush+anchor passes; during that backlog the clock still reads
pre-seek (higher) positions while the video has landed low. The fill
side would see frames "seconds late" and chase the decoder forward past
the real position, leaving the queue head stranded ahead of sound.
`clockSettling()` (true while `!ownsClock` and `pendingSeeks > 0`)
makes the fill skip the chase branch and the pacer hold deep-late heads
until the audio acknowledges its backlog. The lesson: pts alone cannot
tell a phantom chase from a real one; the only honest signal is the
audio side admitting what it still owes.

## AudioPipeline

The audio thread's blocking write to the sink **is** the pacing -- the
write returns when the device has consumed the samples, so there is no
separate frame-rate loop. PCM flows through `TempoFilter` on that one
write path (bypassed at rate 1.0). Looping is drain-then-wrap: flush
the stretcher, let the buffered tail play out, then seek to zero and
re-anchor. Seeks crop the decoder output to the sample at the target.

The **device-death watchdog** is a separate thread that polls the
sink's frame position while a write is in flight; if the position
freezes past a wall deadline, it calls `detachToWallTime` so a silently
dead device cannot freeze the whole pipeline (a blocking JavaSound
write on a vanished device raises nothing).

## Pts math

`Pts.kt` converts between nanoseconds and stream pts. `ptsToNanos`
splits into whole seconds plus remainder so the remainder term cannot
overflow `Long`. `nanosToPts` goes through `BigInteger` 128-bit math,
because `nanos * den` overflows for large positions and splitting the
computation reintroduces sub-unit truncation -- a 1001/30000 (NTSC)
base puts whole seconds off the unit grid. It runs per seek, not per
frame, so the cost is irrelevant.

## Subtitle pipeline

`SubtitlePipeline` is the audio pipeline's shape over the third stream
type: its own thread, confined arena, format context, command queue and
`pendingSeeks` handshake. Two rules, both found by adversarial review
before code:

- **The demux refill gates on ANY stream's pts**, not the subtitle
  stream's. Subtitle packets are sparse; gating on them alone reads
  unbounded interleaved data and goes deaf to commands.
- **The libass flush policy is per codec.** Native ASS packets carry
  stable ReadOrders that libass dedups across replays, so the track
  never flushes. Converted codecs (subrip, mov_text) re-number from a
  decoder counter that resets on flush, so their track flushes on every
  reposition and the preroll replays the visible state. The trap: a
  forward seek past the fed window re-numbers the landing cue into a
  ReadOrder collision and dedup eats the new event -- which is why
  converted codecs flush.

Bitmap tracks convert to premultiplied patches once at decode time and
live in a window schedule (a window closes at its own end, the packet
duration, or the next event; `num_rects == 0` is the PGS clear). The
overlay reaches the consumer through a generation-gated latest-wins
mailbox, same drop-late discipline as video.
