package dev.hivens.skinema.player

import dev.hivens.skinema.audio.AudioPipeline
import dev.hivens.skinema.audio.JavaSoundSink
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.core.PlaybackClock
import dev.hivens.skinema.core.TripleBuffer
import dev.hivens.skinema.libav.AudioTrack
import dev.hivens.skinema.libav.FrameSource
import dev.hivens.skinema.libav.FrameSources
import dev.hivens.skinema.libav.VideoDecoder
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays one video file: a dedicated decode thread keeps a small queue
 * stocked with converted frames, and a pacer thread publishes each one
 * into a tear-free [TripleBuffer] at its pts. Presentation living on
 * its own thread is what makes the queue worth having -- a decode stall
 * cannot stall the screen while inventory lasts.
 *
 * Core stays dependency-free by design (ROADMAP.md section 3): no
 * coroutines, no UI types. The consumer polls [acquireFrame] on its own
 * cadence (a Compose frame clock, a render loop) -- null means "nothing
 * newer than what you already hold" -- and reads [state] for lifecycle.
 *
 * Everything libav happens on the decode thread, open and close
 * included: the decoder's arena is confined to it (the pacer touches
 * only heap arrays, the clock, and the mailbox). Open failures
 * therefore surface as [State.Failed] rather than a constructor throw --
 * the fail-closed path (ROADMAP.md section 2) a consumer answers with a
 * static fallback.
 */
class VideoPlayer internal constructor(
    private val path: Path,
    private val loop: Boolean,
    private val audio: Boolean,
    private val explicitClock: MediaClock?,
    sink: PcmSink?,
    readAheadFrames: Int,
    audioTrack: Int?,
    // The test seam: a Path is the only public way in, so deterministic
    // sources (scripted hiccups) enter here. No defaults on this
    // constructor -- the test source set sees internal members, and a
    // second defaulted overload would make every call ambiguous.
    private val frameSourceFactory: (Path) -> FrameSource,
) : AutoCloseable {

    constructor(
        path: Path,
        loop: Boolean = true,
        /**
         * Decode and play the file's audio stream. The audio sink then
         * masters the player's clock (ROADMAP.md section 3); files without
         * an audio stream -- and machines without an audio device -- degrade
         * to silent wall-clock playback. Audio-only files play frameless:
         * [acquireFrame] stays null while [state] runs the usual lifecycle.
         */
        audio: Boolean = false,
        /** Overrides the clock entirely; with audio on, prefer not to. */
        explicitClock: MediaClock? = null,
        sink: PcmSink? = null,
        /**
         * How many decoded frames the player holds ahead of the clock,
         * 1..8. At 1 (the default) decode runs a single frame ahead --
         * the right footprint for backgrounds; 3-5 lets a player scenario
         * ride out decode stalls up to that many frame periods. Each step
         * of depth costs one full RGBA frame of memory (8.3 MB at 1080p)
         * on top of the mailbox's three slots.
         */
        readAheadFrames: Int = 1,
        /**
         * Stream index of the audio track to open (one of [audioTracks]);
         * null takes the demuxer's best pick. Switch later with
         * [selectAudioTrack].
         */
        audioTrack: Int? = null,
    ) : this(path, loop, audio, explicitClock, sink, readAheadFrames, audioTrack, FrameSources::open)

    sealed interface State {
        data object Opening : State
        data object Playing : State
        data object Paused : State

        /** Non-looping playback ran out of frames; a seek revives it. */
        data object Ended : State

        /**
         * A seek is landing: the demuxer jumped to a keyframe and is
         * decoding forward to the target. The last frame stays on screen;
         * a consumer may show a loading affordance. Resolves back to
         * Playing / Paused / Ended when the target frame lands.
         */
        data object Seeking : State

        /** Open or decode failed; playback stopped for good. Fall back. */
        data class Failed(val cause: Throwable) : State
        data object Closed : State
    }

    /**
     * One presented frame. The consumer owns the returned slot until its
     * next [acquireFrame] call; the player never writes into it during
     * that window.
     */
    class FrameSlot internal constructor(size: Int = 0) {
        var width = 0
            internal set
        var height = 0
            internal set
        var ptsNanos = 0L
            internal set
        var rgba = ByteArray(size)
            internal set
    }

    private sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        data class Seek(val ptsNanos: Long, val exact: Boolean) : Command
        data class SeekBy(val deltaNanos: Long, val exact: Boolean) : Command
        data object Close : Command

        /**
         * The pacer freed a queue cell. Without this token the fill side
         * discovers room only when its command poll times out, putting
         * dead time on every frame of full-queue (steady-state) playback
         * -- which caps production below the frame rate of 60 fps
         * content. Handled as a no-op; its arrival is the point.
         */
        data object RoomFreed : Command
    }

    @Volatile
    var state: State = State.Opening
        private set

    /**
     * Total duration of one lap, as the container reports it; null while
     * [State.Opening] and for sources that cannot know (animated webp).
     * With [positionNanos] this is the timeline.
     */
    @Volatile
    var durationNanos: Long? = null
        private set

    /**
     * The file's audio tracks. Empty while [State.Opening], without
     * `audio = true`, and when the audio device failed to open -- a dead
     * pipeline must not advertise a working selector.
     */
    @Volatile
    var audioTracks: List<AudioTrack> = emptyList()
        private set

    /** Stream index of the track playing; null when no live audio. */
    val activeAudioTrack: Int?
        get() = if (audioTracks.isEmpty()) null else audioPipeline?.activeAudioTrack

    @Volatile
    private var buffer: TripleBuffer<FrameSlot>? = null
    private val queue = FrameQueue(readAheadFrames.coerceIn(1, 8))
    private val commands = LinkedBlockingQueue<Command>()

    // The intended playhead while a seek burst accumulates. SeekBy adds to
    // this rather than to the live clock, so presses faster than a landing
    // takes still sum to the final destination -- the clock stands at the
    // old anchor mid-landing, and reading it (or worse, resetting to -1 on
    // each landing) made bursts resolve to the wrong place. Outside a
    // burst relative seeks base on [lastPublishedPts] (the frame on
    // screen); frameless players, which never publish, keep their last
    // target here. Owned by the decode thread.
    private var intendedPositionNanos = 0L

    // True between issuing a seek and its landing; a SeekBy mid-burst
    // accumulates on [intendedPositionNanos] instead of the on-screen pts.
    private var seekInFlight = false
    private val stateBeforeSeek = java.util.concurrent.atomic.AtomicReference<State?>(null)
    private val audioPipeline: AudioPipeline? =
        if (audio) AudioPipeline(path, sink ?: JavaSoundSink(), loop, audioTrack) else null
    private lateinit var clock: MediaClock

    // When audio masters the clock, video never re-anchors it: seeks and
    // loop wraps are anchored by the audio thread at its actual landing.
    private var ownsClock = true

    // The pts on screen. Written by the pacer's publish; the decode
    // thread reads it for the resume re-anchor, the loop-wrap park, and
    // relative-seek bases.
    @Volatile
    private var lastPublishedPts = 0L

    // Wall time of the last publish; feeds the late-frame starvation
    // guard on both threads.
    @Volatile
    private var lastPublishWallNanos = 0L

    // Decode ran out of stream but the pacer may still hold the tail;
    // the EOF actions (loop wrap, park, Ended) wait for that drain.
    private var eofPending = false

    private val thread = Thread(::run, "skinema-decode").apply {
        isDaemon = true
        start()
    }

    /**
     * The freshest published frame, or null when nothing new arrived since
     * the previous call (keep showing what you have).
     */
    fun acquireFrame(): FrameSlot? = buffer?.acquire()

    /** Freezes playback; the surface keeps showing the last frame. */
    fun pause() = commands.put(Command.Pause)

    /** Continues from where [pause] froze, without a frame jump. */
    fun resume() = commands.put(Command.Resume)

    /**
     * Jumps to [ptsNanos]; revives an [State.Ended] player.
     *
     * Exact seeks are frame-precise: the decoder runs forward from the
     * keyframe at-or-before the target, which on sparse-keyframe content
     * costs up to a keyframe interval of bare decode -- the keyframe
     * itself publishes immediately as a preview while the landing runs.
     * With [exact] false the seek LANDS on that keyframe instead: picture
     * and sound arrive at once, the position is only as precise as the
     * file's keyframe spacing. The right trade for skip buttons; keep
     * exact for timeline scrubbing.
     */
    fun seek(ptsNanos: Long, exact: Boolean = true) =
        commands.put(Command.Seek(ptsNanos.coerceAtLeast(0), exact))

    /**
     * Seeks [deltaNanos] relative to the intended playhead -- the right
     * primitive for +N/-N buttons. Rapid presses accumulate to one
     * destination regardless of how far behind the clock's anchor lags
     * during a landing. The delta resolves on the decode thread, against
     * its own playhead state -- resolving here would race the publish
     * loop's bookkeeping. [exact] as in [seek]; inexact presses
     * accumulate against the position actually landed.
     */
    fun seekBy(deltaNanos: Long, exact: Boolean = true) =
        commands.put(Command.SeekBy(deltaNanos, exact))

    /** Linear 0..1 volume; no-op for silent playback. */
    fun setVolume(volume: Float) {
        audioPipeline?.setVolume(volume)
    }

    /**
     * Switches the sound to another of [audioTracks], in place: the
     * picture keeps playing, the sound re-anchors at the playhead. A
     * track that cannot open or that ends before the playhead is
     * refused and the current one plays on.
     */
    fun selectAudioTrack(streamIndex: Int) {
        audioPipeline?.selectTrack(streamIndex)
    }

    /** Current media position in nanoseconds; zero until playback starts. */
    fun positionNanos(): Long = if (::clock.isInitialized) clock.mediaNanos() else 0L

    override fun close() {
        commands.put(Command.Close)
        thread.join(5_000)
    }

    // -- Decode thread --------------------------------------------------------

    private fun run() {
        // The audio thread reports whether this file has sound; that
        // decides whose clock rules before any pacing starts.
        val audioClock = audioPipeline?.let {
            runCatching { it.clockFuture.get(5, TimeUnit.SECONDS) }.getOrNull()
        }
        clock = explicitClock ?: audioClock ?: PlaybackClock()
        ownsClock = explicitClock != null || audioClock == null
        // Tracks publish only over a LIVE pipeline (the no-device path
        // enumerated them too, but nothing would serve a switch), and
        // before the video open so the frameless branch sees them.
        if (audioClock != null) {
            audioTracks = audioPipeline?.tracks ?: emptyList()
        }

        val decoder = try {
            frameSourceFactory(path)
        } catch (t: Throwable) {
            if (audioClock != null) {
                // No video stream but the audio plays: frameless mode.
                durationNanos = audioPipeline?.durationNanos
                framelessLoop()
                state = State.Closed
            } else {
                state = State.Failed(t)
            }
            audioPipeline?.close()
            return
        }
        durationNanos = decoder.durationNanos()
        val pacer = Thread(::paceLoop, "skinema-pace").apply {
            isDaemon = true
            start()
        }
        try {
            if (ownsClock) clock.start(0)
            state = State.Playing
            decodeLoop(decoder)
            state = State.Closed
        } catch (t: Throwable) {
            state = State.Failed(t)
        } finally {
            queue.close()
            pacer.join(1_000)
            runCatching { decoder.close() }
            runCatching { audioPipeline?.close() }
        }
    }

    /** Audio-only playback: commands and lifecycle, no frames. */
    private fun framelessLoop() {
        state = State.Playing
        while (true) {
            val cmd = commands.poll(100, TimeUnit.MILLISECONDS)
            if (cmd != null && !handle(cmd, decoder = null)) return
            if (state is State.Playing && !loop && audioPipeline?.isEnded == true) {
                state = State.Ended
            }
        }
    }

    /**
     * The fill side: keeps the queue stocked with converted frames while
     * the pacer presents them. One decode per pass, commands drained at
     * the top, so a command never waits behind more than a single frame.
     */
    private fun decodeLoop(decoder: FrameSource) {
        while (true) {
            var cmd = commands.poll()
            while (cmd != null) {
                if (!handle(cmd, decoder)) return
                cmd = commands.poll()
            }

            if (state !is State.Playing) {
                // Paused or Ended: idle until the next command.
                val idle = commands.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (!handle(idle, decoder)) return
                continue
            }

            if (eofPending) {
                if (!queue.isEmpty) {
                    // The pacer is still presenting the tail; stay on the
                    // command queue while it drains.
                    val c = commands.poll(20, TimeUnit.MILLISECONDS) ?: continue
                    if (!handle(c, decoder)) return
                    continue
                }
                eofPending = false
                if (loop) {
                    decoder.seekTo(0)
                    if (ownsClock) {
                        clock.seek(0)
                    } else if (!awaitClockWrap(decoder)) {
                        return
                    }
                } else {
                    state = State.Ended
                }
                continue
            }

            if (!queue.hasRoom) {
                // Inventory full; room appears as the pacer publishes.
                val c = commands.poll(20, TimeUnit.MILLISECONDS) ?: continue
                if (!handle(c, decoder)) return
                continue
            }

            val frame = decoder.nextFrame(convert = false)
            if (frame == null) {
                eofPending = true
                continue
            }

            val lateNanos = -clock.nanosUntilDue(frame.ptsNanos)
            if (lateNanos > CHASE_DROP_NANOS && !clockSettling()) {
                // Catch-up run (the clock jumped past this frame -- a loop
                // wrap, an audio re-anchor). Converting frames the policy
                // would drop costs several times their bare decode; one
                // converted guard per interval keeps the run reading as
                // motion. The guard ships forced so the pacer does not
                // re-judge -- deciding twice double-drops.
                if (!shouldPublishLateFrame(lateNanos, System.nanoTime() - lastPublishWallNanos)) continue
                enqueue(decoder, frame, forced = true)
                continue
            }
            enqueue(decoder, frame, forced = false)
        }
    }

    /** Converts the decoder's current frame into a queue cell. */
    private fun enqueue(decoder: FrameSource, raw: VideoDecoder.RgbaFrame, forced: Boolean) {
        // The seek path commits a preview and a landing back to back; at
        // depth 1 the landing must wait out the pacer's pop of the
        // preview (forced frames pop within microseconds). Normal fill
        // checks hasRoom before decoding and never waits here.
        while (true) {
            val tick = queue.changeTick()
            if (queue.hasRoom) break
            if (queue.isClosed) return
            queue.awaitChange(tick, PACE_RECHECK_NANOS)
        }
        val cell = queue.writeCell()
        val bytes = raw.width * raw.height * 4
        if (cell.rgba.size != bytes) cell.rgba = ByteArray(bytes)
        val converted = decoder.convertLast(cell.rgba)
        // On a size mismatch convertLast falls back to its internal reused
        // buffer, which must never be enqueued -- the next decode would
        // overwrite it. The pre-sizing above makes this branch dead in
        // practice; the copy keeps it correct if a source ever disagrees.
        if (converted.rgba !== cell.rgba) cell.rgba = converted.rgba.copyOf()
        cell.width = converted.width
        cell.height = converted.height
        cell.ptsNanos = converted.ptsNanos
        queue.commit(forced)
    }

    /**
     * The audio thread anchors the clock from its own seek handling, and
     * it reads commands only between blocking writes -- after a seek
     * burst it can owe dozens of anchors while the clock still reads a
     * pre-seek position. Lateness computed against that reading is
     * fiction: a backward burst looks like a multi-second forward chase
     * and burns the decoder past the real position (the picture then
     * stands until the clock walks there). While seeks are owed, the
     * fill must not chase and the pacer must not drop.
     */
    private fun clockSettling(): Boolean =
        !ownsClock && (audioPipeline?.pendingSeeks?.get() ?: 0) > 0

    private fun handle(cmd: Command, decoder: FrameSource?): Boolean = when (cmd) {
        Command.Close -> false
        Command.RoomFreed -> true
        Command.Pause -> {
            if (state is State.Playing) {
                audioPipeline?.pause()
                clock.pause()
                state = State.Paused
            }
            true
        }
        Command.Resume -> {
            if (state is State.Paused) {
                // The sink's buffered tail keeps sounding (and advancing the
                // device clock) for a beat after a pause lands; resuming
                // re-anchors sound to the frame actually on screen,
                // sample-precise. Frameless players have no frame to anchor
                // to and just resume.
                if (audioPipeline != null && decoder != null) {
                    audioPipeline.seek(lastPublishedPts)
                    audioPipeline.videoLanded()
                }
                audioPipeline?.resume()
                clock.resume()
                state = State.Playing
            }
            true
        }
        is Command.Seek -> handleSeek(cmd.ptsNanos, cmd.exact, decoder)
        is Command.SeekBy -> {
            // Mid-burst the accumulated target wins (the clock stands at
            // the old anchor and must not be consulted). Outside a burst
            // the playhead is the frame on screen OR the just-landed
            // target, whichever is further -- a landing publishes
            // asynchronously (and a preview publishes the keyframe first),
            // so lastPublishedPts alone can briefly lag the intent.
            // Frameless players never publish; their intent carries alone.
            val base = if (seekInFlight) {
                intendedPositionNanos
            } else {
                maxOf(intendedPositionNanos, lastPublishedPts)
            }
            handleSeek((base + cmd.deltaNanos).coerceAtLeast(0), cmd.exact, decoder)
        }
    }

    private fun handleSeek(targetNanos: Long, exact: Boolean, decoder: FrameSource?): Boolean {
        seekInFlight = true
        intendedPositionNanos = targetNanos
        audioPipeline?.seek(targetNanos)
        val keepRunning = if (decoder != null) {
            performSeek(decoder, targetNanos, exact)
        } else {
            // Frameless (audio-only): no landing to wait for.
            seekInFlight = false
            if (state is State.Ended) state = State.Playing
            true
        }
        // Sound stays frozen at the anchor until the landing is done;
        // released here even when the landing ended the stream.
        audioPipeline?.videoLanded()
        return keepRunning
    }

    /**
     * The audio side wraps the clock on ITS end-of-stream; video parks
     * here after its own EOF until time restarts, staying responsive to
     * commands. Commands are handled with the real decoder -- a seek
     * handled decoder-less would re-anchor the audio and leave the video
     * frozen on its last frame until the wrap. A landed seek repositioned
     * the decoder, so it also ends the park. Returns false on Close.
     */
    private fun awaitClockWrap(decoder: FrameSource): Boolean {
        val wrapped = lastPublishedPts / 2
        while (state is State.Playing && lastPublishedPts > 0 && clock.mediaNanos() > wrapped) {
            val cmd = commands.poll(20, TimeUnit.MILLISECONDS) ?: continue
            val seeked = cmd is Command.Seek || cmd is Command.SeekBy
            if (!handle(cmd, decoder)) return false
            if (seeked || state !is State.Playing) break
        }
        return true
    }

    /**
     * Seek landing. The demuxer lands on the keyframe at-or-before the
     * target; an exact seek then decodes (and drops) forward until the
     * target is reached, an inexact one takes the keyframe as the
     * destination. Either way the first frame out publishes immediately:
     * for inexact it IS the landing, for exact it is a preview the run
     * then refines -- the screen answers the press in milliseconds while
     * a sparse-keyframe run costs its seconds in the background.
     *
     * The decode-forward run can span seconds of footage, so newer seeks
     * queued meanwhile supersede the landing in progress -- rapid presses
     * cost one landing at the final target, not a landing each. Returns
     * false when a Close arrived mid-landing.
     */
    private fun performSeek(decoder: FrameSource, targetNanos: Long, exact: Boolean): Boolean {
        // Whatever inventory was decoded toward the old position is stale;
        // the landing must be the next thing on screen. Repositioning the
        // decoder also voids a pending EOF.
        queue.clear()
        eofPending = false
        // Remember what to return to (Playing/Paused/Ended) and advertise
        // the landing so a consumer can show a loading affordance.
        stateBeforeSeek.compareAndSet(null, state.takeIf { it != State.Seeking } ?: State.Playing)
        state = State.Seeking
        var target = targetNanos
        var exactMode = exact
        decoder.seekTo(target)
        val debugStart = if (DEBUG_SEEK) System.nanoTime() else 0L
        var dropped = 0
        var atKeyframe = true
        var previewedPts = Long.MIN_VALUE
        var landedFromKeyframe = Long.MIN_VALUE
        while (true) {
            // Room tokens carry no payload and must not hide a queued
            // seek behind them.
            while (commands.peek() == Command.RoomFreed) commands.poll()
            val next = commands.peek()
            val superseded = when (next) {
                is Command.Seek -> next.ptsNanos
                is Command.SeekBy -> (intendedPositionNanos + next.deltaNanos).coerceAtLeast(0)
                Command.Close -> return false
                else -> null
            }
            if (superseded != null) {
                commands.poll()
                target = superseded
                exactMode = when (next) {
                    is Command.Seek -> next.exact
                    is Command.SeekBy -> next.exact
                    else -> exactMode
                }
                intendedPositionNanos = target
                audioPipeline?.seek(target)
                decoder.seekTo(target)
                dropped = 0
                atKeyframe = true
                landedFromKeyframe = Long.MIN_VALUE
                continue
            }
            // Bare decode while dropping: converting frames that are thrown
            // away costs several times the decode itself.
            val f = decoder.nextFrame(convert = false)
            if (DEBUG_SEEK && f != null) {
                if (landedFromKeyframe == Long.MIN_VALUE) landedFromKeyframe = f.ptsNanos
                dropped++
            }
            if (f == null) {
                // Seeked past the last frame: same treatment as EOF.
                if (loop) {
                    decoder.seekTo(0)
                    if (ownsClock) clock.seek(0)
                    finishSeek(State.Playing)
                } else {
                    finishSeek(State.Ended)
                }
                return true
            }
            if (!exactMode) {
                // The keyframe is the destination: re-anchor everything to
                // where the stream actually starts, sound included --
                // leaving the audio at the requested target would play it
                // up to a keyframe interval ahead of the picture.
                if (ownsClock) clock.seek(f.ptsNanos)
                intendedPositionNanos = f.ptsNanos
                audioPipeline?.seek(f.ptsNanos)
                enqueue(decoder, f, forced = true)
                if (DEBUG_SEEK) {
                    val ms = (System.nanoTime() - debugStart) / 1_000_000
                    System.err.println(
                        "[seek] target=${target / 1_000_000}ms landed=${f.ptsNanos / 1_000_000}ms (keyframe) in ${ms}ms",
                    )
                }
                finishSeek(State.Playing)
                return true
            }
            if (f.ptsNanos >= target) {
                if (ownsClock) clock.seek(f.ptsNanos)
                // Forced: the pacer publishes the landing immediately, even
                // while the player resolves back to Paused.
                enqueue(decoder, f, forced = true)
                if (DEBUG_SEEK) {
                    val ms = (System.nanoTime() - debugStart) / 1_000_000
                    val kfGapMs = (target - landedFromKeyframe) / 1_000_000
                    System.err.println(
                        "[seek] target=${target / 1_000_000}ms keyframeGap=${kfGapMs}ms decoded=$dropped frames in ${ms}ms",
                    )
                }
                finishSeek(State.Playing)
                return true
            }
            if (atKeyframe) {
                // Preview: show the keyframe while the run decodes toward
                // the exact target. A superseding burst within one keyframe
                // interval re-sees the same frame -- skip the re-convert.
                atKeyframe = false
                if (f.ptsNanos != previewedPts) {
                    previewedPts = f.ptsNanos
                    enqueue(decoder, f, forced = true)
                }
            }
        }
    }

    /**
     * Resolves [State.Seeking] back to the lifecycle. A seek that landed
     * resumes whatever was running before the burst started (a paused
     * player stays paused at the new frame); [ended]/[playing] only chooses
     * the fallback when there was no prior state to restore.
     */
    private fun finishSeek(landed: State) {
        seekInFlight = false
        val prior = stateBeforeSeek.getAndSet(null)
        state = when {
            prior == State.Paused && landed != State.Ended -> State.Paused
            else -> landed
        }
    }

    // -- Pacer thread ---------------------------------------------------------

    /**
     * Owns presentation: waits out each queued frame's pts against the
     * clock and publishes it into the mailbox. Living on its own thread
     * is the point of the queue -- a stalled decode is no longer the
     * publisher, so whatever inventory exists keeps presenting through
     * the stall. Exits when the decode thread closes the queue.
     */
    private fun paceLoop() {
        var lastFlushes = queue.flushCount
        var lastClockReading = Long.MIN_VALUE
        var starveWarnedPts = Long.MIN_VALUE
        while (!queue.isClosed) {
            // The tick reads before the peek: a mutation in between makes
            // the next wait return immediately instead of sleeping stale.
            val tick = queue.changeTick()
            val head = queue.peekHead()
            if (head == null) {
                queue.awaitChange(tick, PACE_RECHECK_NANOS)
                continue
            }
            if (head.forced) {
                // Seek landings and chase guards: the decode side already
                // decided these publish, state gate and late policy aside.
                publishFromQueue()
                continue
            }
            if (state !is State.Playing) {
                // Paused, or a landing resolving: hold the inventory.
                queue.awaitChange(tick, IDLE_RECHECK_NANOS)
                continue
            }

            val flushes = queue.flushCount
            if (flushes != lastFlushes) {
                lastFlushes = flushes
                lastClockReading = Long.MIN_VALUE
            }
            val clockNow = clock.mediaNanos()
            val regressed = lastClockReading != Long.MIN_VALUE &&
                clockNow < lastClockReading - REGRESSION_NANOS
            lastClockReading = clockNow
            if (regressed) {
                // The clock only jumps backward like this on a loop wrap (a
                // seek flushes the queue first, resetting the tracking
                // above): the queued tail belongs to the lap that just
                // ended -- show it now, not a lap later.
                while (true) {
                    val h = queue.peekHead() ?: break
                    if (h.forced || h.ptsNanos <= clockNow + REGRESSION_NANOS) break
                    publishFromQueue()
                }
                continue
            }

            val wait = head.ptsNanos - clockNow
            if (wait > 0) {
                // A frame standing more than a second ahead of the clock is
                // a frozen picture with running sound -- name both sides.
                if (DEBUG_SEEK && wait > 1_000_000_000L && starveWarnedPts != head.ptsNanos) {
                    starveWarnedPts = head.ptsNanos
                    System.err.println(
                        "[pace] frame=${head.ptsNanos / 1_000_000}ms waits ${wait / 1_000_000}ms for the clock (${clockNow / 1_000_000}ms)",
                    )
                }
                // Capped: the audio thread can re-anchor the clock at any
                // moment, and a sleep taken against a stale reading must
                // notice within one period. A queue mutation (a seek's
                // flush-and-landing) cuts the sleep short entirely.
                queue.awaitChange(tick, wait.coerceAtMost(PACE_RECHECK_NANOS))
                continue
            }

            if (-wait > CHASE_DROP_NANOS && clockSettling()) {
                // Deep lateness against a clock that still owes seek
                // anchors is fiction; hold the inventory until the audio
                // thread lands them.
                queue.awaitChange(tick, IDLE_RECHECK_NANOS)
                continue
            }
            if (!shouldPublishLateFrame(-wait, System.nanoTime() - lastPublishWallNanos)) {
                queue.dropHead()
                commands.put(Command.RoomFreed)
                continue
            }
            publishFromQueue()
        }
    }

    /**
     * Pops the queue head into the mailbox by swapping arrays with the
     * writing slot -- no pixel copy; the arrays cycle between the queue
     * and the mailbox. A geometry change rebuilds the mailbox around the
     * new size, as publish always has.
     */
    private fun publishFromQueue(): Boolean {
        val head = queue.peekHead() ?: return false
        val current = buffer
        val target = if (current == null || current.writing.rgba.size != head.byteCount) {
            TripleBuffer(
                FrameSlot(head.byteCount),
                FrameSlot(head.byteCount),
                FrameSlot(head.byteCount),
            )
        } else {
            current
        }
        val slot = target.writing
        val frame = queue.poll(slot.rgba) ?: return false
        slot.rgba = frame.rgba
        slot.width = frame.width
        slot.height = frame.height
        slot.ptsNanos = frame.ptsNanos
        lastPublishedPts = frame.ptsNanos
        lastPublishWallNanos = System.nanoTime()
        target.publish()
        if (target !== current) buffer = target
        commands.put(Command.RoomFreed)
        return true
    }

    private companion object {
        /**
         * Longest uninterrupted pace sleep. The wait is computed from one
         * clock reading, and the audio thread re-anchors the clock from
         * its own seek handling -- which can run AFTER a fast video
         * landing, since it only reads its command queue between blocking
         * writes. A sleep taken against the stale reading is a frozen
         * picture over running sound for the whole seek distance; capping
         * it bounds that to one re-check period. Frame-rate waits exceed
         * the cap only on low-fps content, where an extra wakeup per
         * period is noise.
         */
        const val PACE_RECHECK_NANOS = 50_000_000L

        /** The pacer's pause-hold re-check cadence. */
        const val IDLE_RECHECK_NANOS = 20_000_000L

        /**
         * A backward clock jump bigger than this, with no seek flush in
         * between, is a loop wrap. AudioClock clamps device-position
         * noise to monotonic, and a seek empties the queue before the
         * pacer can act on its re-anchor, so nothing else moves time
         * backward under live inventory.
         */
        const val REGRESSION_NANOS = 1_000_000_000L

        val DEBUG_SEEK = System.getenv("SKINEMA_DEBUG_SEEK") != null
    }
}

/**
 * Lateness past frame-rate jitter: a frame this overdue belongs to a
 * catch-up run, not to normal pacing slack.
 */
internal const val CHASE_DROP_NANOS = 250_000_000L

/**
 * The starvation guard's cadence: during a catch-up run one frame per
 * this interval still publishes, so the run reads as motion and an
 * overloaded machine degrades to a slideshow, not a freeze.
 */
internal const val CHASE_PUBLISH_INTERVAL_NANOS = 150_000_000L

/**
 * Whether a frame that missed its presentation time still converts and
 * publishes. Pure -- the policy half of the catch-up handling, applied
 * at decode time (convert or drop) and at pace time (publish or drop).
 */
internal fun shouldPublishLateFrame(lateNanos: Long, sincePublishNanos: Long): Boolean =
    lateNanos <= CHASE_DROP_NANOS || sincePublishNanos >= CHASE_PUBLISH_INTERVAL_NANOS
