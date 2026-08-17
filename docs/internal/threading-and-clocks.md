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

### What the device actually reports

Worth knowing exactly, because several of this clock's mechanisms exist
only because of it, and because "we ask the device" sounds more precise
than it is.

A JavaSound line does not read a hardware register. The ALSA backend
computes `handed over - still queued`
(`estimatePositionFromAvail` in openjdk's
`PLATFORM_API_LinuxOS_ALSA_PCM.c`, whose own comment calls it "not an
elegant solution"). That is the same quantity ffplay estimates; the
difference is that ffplay guesses the queued term as two driver periods
while a line measures it. It is `snd_pcm_avail`, so it covers the ring
buffer and not the latency downstream of it. Measured against wall time
over 30 s the reading drifts by -24 ppm, which is to say not at all --
whatever the device clock buys, it is not drift correction.

Two properties of it shape the code:

- **It refreshes once per device period and is a constant in between.**
  Measured on a USB DAC through PipeWire: 21.3 ms still, then a 21.3 ms
  jump. Read raw, media time is a staircase, and a staircase step longer
  than a frame period paces video in bursts -- 60 fps content delivered
  48.4 distinct frames a second to a consumer, the rest overwritten in
  the mailbox before anything could take them. `AudioClock` therefore
  fills the gaps with wall time, bounded by the device's own last step
  and by a 60 ms ceiling; the same measurement then delivers 60.0.
  `MasteredClockPacingTest` holds it, over a real device.
- **A flush destroys the "still queued" term**, so afterwards the
  backend reports the whole of what was handed over as played -- a
  forward step of the discarded tail, measured at 130 and 177 ms on a
  200 ms line. `JavaSoundSink` subtracts it off, because
  `PcmSink.framePosition` promises frames PLAYED and the mastered clock
  anchors on it. `PcmSinkContract` holds every sink to that rule.

The player picks the clock once at startup:

```kotlin
clock = explicitClock ?: audioClock ?: PlaybackClock()
ownsClock = explicitClock != null || audioClock == null
```

`ownsClock` says which side supplies media time -- the audio device or
the wall -- and no longer says who may move it. The rule that kept the
two clocks from fighting is now about WHEN rather than WHO: the clock is
re-anchored only at points where nothing is in flight on either side,
and the decode thread is the one that picks those points because it is
the one that knows when a lap or a landing is complete.

It re-anchors in three places, all of them on the decode thread: at the
end of playback (stopped, then placed on the duration), at a lap (after
the queue has drained and the file's own time is up, restarting the
sound with the same call), and at a seek landing. The audio thread
re-anchors at its own landing, and the watchdog hands the clock to the
wall when the device dies. Nothing parks waiting for another side to
move time -- the earlier arrangement, where the sound wrapped the clock
at its own end and the picture waited for that, could not describe a
file whose track is shorter than its picture: the timeline sawed back
to zero while the picture still had seconds to run.

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
- **setDeviceRunning(running)** -- whether the line is consuming, which
  only the pipeline knows: `isPaused` covers the player's pause and none
  of the seeks, switches and rate changes that also stop the line. It
  gates the gap fill, because a stopped line plays nothing and every
  nanosecond added past the stop is time the following re-anchor would
  have to take back. `AudioPipeline` funnels every `stop`/`start`
  through `freezeSink`/`runSink` so the two cannot drift apart.

## Seeks: the freeze-first handshake

A seek with sound is a coordination, not a jump:

1. The video side marks the seek in flight and tells the audio and
   subtitle pipelines (incrementing their `pendingSeeks`).
2. The audio thread **freezes first** -- `sink.stop()` -- and only then
   reads the playhead, crops to the sample-precise anchor and
   re-anchors the clock there. Freezing after reading would let the line
   play its buffered tail and step the mastered clock backward, which
   the pacer's invariants forbid. The read goes BETWEEN the stop and the
   flush: the freeze is what makes it safe to take, and the flush is
   what destroys the evidence (see "What the device actually reports").
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

The **device-death watchdog** is a separate thread that judges a stall
by how long one write has been outstanding; past a wall deadline it
calls `detachToWallTime` and closes the line, so a silently dead device
cannot freeze the whole pipeline (a blocking JavaSound write on a
vanished device raises nothing) and the stuck write returns into
recovery.

It deliberately asks the device nothing. It used to poll the frame
position and call the device stuck when that stopped advancing, which
cannot work: `nGetBytePosition` and `nWrite` are taken under the same
native monitor (`lockNative` in openjdk's `DirectAudioDevice.java`), so
on the dead device this exists to rescue, the watchdog parked on the
very lock it came to break -- taking the pacer, the decode thread and
the consumer's render loop down behind it, since they all read this
clock. How long its own write has been outstanding is the one thing it
can know without the device's help, and it is enough. The same monitor
is why `AudioClock` samples the line outside its own lock, and not at
all once detached.

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
