# Threading and clocks

This is the runtime: the threads of a `VideoPlayer`, how frames move
between them, and the clock that decides when a frame is due. It is the
part most worth understanding before changing anything, because the
bugs here are subtle and were expensive to find.

## The threads

A player has up to five threads, each owning a confined arena where it
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
audioMastered = explicitClock == null && audioClock != null
```

`ownsClock` is derived from that, and asked every time rather than
settled once:

```kotlin
private val ownsClock: Boolean
    get() = !audioMastered || audioPipeline?.alive != true
```

The re-evaluation is load-bearing: the audio side can leave mid-file --
a device that dies, a track switch onto a rate the machine refuses --
and a player that went on deferring to a thread no longer there stopped
re-anchoring on its own seeks.

`ownsClock` says which side supplies media time -- the audio device or
the wall -- and no longer says who may move it. The rule that kept the
two clocks from fighting is now about WHEN rather than WHO: the clock is
re-anchored only at points where nothing is in flight on either side,
and the decode thread is the one that picks those points because it is
the one that knows when a lap or a landing is complete.

Media time is PLACED only by the decode thread, and only where it knows
nothing is in flight:

- the end of playback -- stopped first, then placed on the duration;
- a lap -- after the queue has drained and the file's own time is up,
  restarting the sound with the same call;
- a seek landing, including the one that lands past the end of the
  footage: neither side sets the clock there, so the target is placed
  against the duration explicitly, or the EOF path waits the lap out at
  wall speed against the reading the press came FROM;
- a frame step, which anchors on the frame it stopped at so a resume
  continues from it;
- a paused start, where the first frame is decoded, published forced and
  the clock stopped on it.

The audio thread re-anchors at its own landing, and the watchdog hands the
clock to the wall when the device dies. Nothing parks waiting for another side to
move time -- the earlier arrangement, where the sound wrapped the clock
at its own end and the picture waited for that, could not describe a
file whose track is shorter than its picture: the timeline sawed back
to zero while the picture still had seconds to run.

AudioClock's four disciplined operations:

- **rebase(mediaNanos, sampleRate)** -- the one synchronized point a
  rate or track change can re-anchor *and* re-scale, atomically under
  the lock, so a new sample rate applies only forward and never
  rescales history.
- **detachToWallTime(readDevice = true)** -- the failure hatch. If the
  device dies, the clock switches to extrapolating from wall time so the
  picture keeps moving. The watchdog passes `readDevice = false`: it is
  detaching precisely because the device will not answer, and asking it
  one last time would park the rescue on the lock it came to break.
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

**The volume belongs to this side, not to the line.** A fresh line starts
at whatever gain the device gives it, so `AudioPipeline` holds the last
value asked for and applies it inside `openLine`, before the first write.
Without that a muted player came back at full on the other side of a
track switch or a recovery -- and the reopen is the case that matters,
because nobody asked for it. Keeping it here rather than in the sink is
deliberate: the sink is a seam a consumer implements, and remembering a
value across a reopen it does not control is not a rule worth handing
them. It is also what makes an initial volume possible at all, since the
first chunk is written by this thread the moment the device opens.

The watchdog deliberately asks the device nothing, because the answer
would be worthless. It used to poll the frame position and call the device stuck
when that stopped advancing, which cannot work: a frozen position is
also what a paused line reports, and the answer comes from the device
being judged. How long its own write has been outstanding is a fact this
side owns, and it is enough.

The stronger reason once written here -- that the poll would park on the
lock it came to break -- is not true, and the correction is worth
keeping because the shape recurs. `nGetBytePosition` and `nWrite` do
share `lockNative` in openjdk's `DirectAudioDevice.java`, but `write` is
a Java polling loop: `while (!flushing) { synchronized (lockNative) {
nWrite(...) } ... synchronized (lock) { lock.wait(waitTime) } }`. The
monitor is held for one non-blocking native write at a time and the
waiting happens on a different monitor, so a position query is delayed
by one iteration -- measured at 3 to 31 ms against writes blocking for
154 ms and for two seconds alike. Had the claim been true the rescue
could not work either: `close()` starts with `stop()`, which takes
`lockNative` as well. `AudioClock` still samples the line outside its
own lock, and not at all once detached.

## Teardown

`close()` publishes one deadline -- `CLOSE_BUDGET_NANOS`, a second --
and every join inside the teardown spends that one budget rather than
taking it each. The old shape took it each: the caller waited five
seconds for the decode thread, whose own shutdown then joined the pacer
for one and the audio and subtitle pipelines for five apiece, so eleven
seconds of patience sat behind a five-second promise. Thirty players
closed in a row is the case that makes it obvious.

Two rules make one second enough.

**Announce, then join.** Every side is told to go before any of them is
joined. The three are independent -- the audio thread does not need the
pacer, the subtitle thread does not need the audio -- so told together
their exits overlap, and the teardown costs the slowest side instead of
the sum of all of them. Each pipeline splits its close to allow it:
`announceClose()` queues the Close and returns, `awaitExit(deadline)`
waits inside whatever is left of the budget.

**The shutter.** Waiting is no longer what keeps a caller's own
resources safe. Once a close is announced `AudioPipeline` starts no new
write, and one already inside the sink is broken out of by the
watchdog -- the same rescue it performs for a dead device, from the same
thread `PcmSink` already names for it, so a consumer's own sink never
sees a new caller. Device-loss recovery keeps the same shutter one step
earlier: it reads `closing` once per attempt and a seek sits between that
read and the reopen, so the reopen asks again rather than opening a sink
the caller has already been told it has back. Opening is not a write, but
it reaches into the same object and `open()` starts it by contract. A sink that is not draining is the ordinary worst
case rather than an exotic one (every paused device, every line whose
consumer went away), and it used to put the watchdog's whole stall bound
on an ordinary close.

What is left to wait for is native memory, which the daemon threads free
whether or not anyone watches. `closeAsync()` is the same teardown
without the wait, for a caller that cannot block at all.

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
- **The canvas announcement is deduplicated on the CALLER's thread.**
  The handler compares too, and by then the command is on the queue and
  this thread has been woken to read it -- and a pump that reads a
  non-empty queue as work pending refills a packet at a time and never
  reaches its own render cadence. The displayed rect is known in a draw
  scope, so a consumer posts it per frame; `setCanvasSize` therefore
  returns early on an unchanged size before it queues anything. A fresh
  pipeline is not deduplicated against the old one's value, which is what
  lets the player re-state the size it was told while none existed.

### The caption mode, where the packets come from the video

A closed-caption pipeline demuxes nothing. There is no stream: the decode
thread lifts A53 payload off each frame and hands it over through
`submitCaptions`, so the pipeline opens no format context, seeks no demuxer
and has no read-ahead horizon -- the video paces it by construction. It is
recognised by its track's codec name, and everything after the decoder is the
text path unchanged, because cc_dec emits ASS like the converted codecs do.

Three details carry it.

The queue between the two threads is **bounded and dropping**. The producer is
the thread that paces the picture, so a subtitle side that fell behind must
never become back-pressure on it: a dropped payload costs a caption, a blocked
decode thread costs the video.

A reposition **flushes rather than seeks**. The video side has already moved;
what has to go is the state from before the jump -- payloads still queued, the
decoder's half-assembled row, and the ass track's cues, which a preroll will
not replay because captions are not re-read from a container.

The packets are **synthesised**: a scratch buffer this side owns, padded by
`AV_INPUT_BUFFER_PADDING_SIZE` because a decoder may read past the end of a
packet, with a microsecond time base chosen here rather than read off a
stream, since the payloads arrive already stamped on the player's timeline.

Bitmap tracks convert to premultiplied patches once at decode time and
live in a window schedule (a window closes at its own end, the packet
duration, or the next event; `num_rects == 0` is the PGS clear). The
overlay reaches the consumer through a generation-gated latest-wins
mailbox, same drop-late discipline as video.
