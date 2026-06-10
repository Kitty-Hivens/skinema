package dev.hivens.skinema.player

import dev.hivens.skinema.audio.AudioPipeline
import dev.hivens.skinema.audio.JavaSoundSink
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.core.PlaybackClock
import dev.hivens.skinema.core.TripleBuffer
import dev.hivens.skinema.libav.FrameSource
import dev.hivens.skinema.libav.FrameSources
import dev.hivens.skinema.libav.VideoDecoder
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays one video file on a dedicated decode thread, publishing frames
 * into a tear-free [TripleBuffer] paced by each frame's pts.
 *
 * Core stays dependency-free by design (ROADMAP.md section 3): no
 * coroutines, no UI types. The consumer polls [acquireFrame] on its own
 * cadence (a Compose frame clock, a render loop) -- null means "nothing
 * newer than what you already hold" -- and reads [state] for lifecycle.
 *
 * Everything libav happens on the decode thread, open and close
 * included: the decoder's arena is confined to it. Open failures
 * therefore surface as [State.Failed] rather than a constructor throw --
 * the fail-closed path (ROADMAP.md section 2) a consumer answers with a
 * static fallback.
 */
class VideoPlayer(
    private val path: Path,
    private val loop: Boolean = true,
    /**
     * Decode and play the file's audio stream. The audio sink then
     * masters the player's clock (ROADMAP.md section 3); files without
     * an audio stream -- and machines without an audio device -- degrade
     * to silent wall-clock playback. Audio-only files play frameless:
     * [acquireFrame] stays null while [state] runs the usual lifecycle.
     */
    private val audio: Boolean = false,
    /** Overrides the clock entirely; with audio on, prefer not to. */
    private val explicitClock: MediaClock? = null,
    sink: PcmSink? = null,
) : AutoCloseable {

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
     * next [acquireFrame] call; the decode thread never writes into it
     * during that window.
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
        data class Seek(val ptsNanos: Long) : Command
        data object Close : Command
    }

    @Volatile
    var state: State = State.Opening
        private set

    @Volatile
    private var buffer: TripleBuffer<FrameSlot>? = null
    private val commands = LinkedBlockingQueue<Command>()
    private var seekGeneration = 0

    // The intended playhead: the frame on screen during normal playback,
    // the accumulating destination during a seek burst. [seekBy] adds to
    // this rather than to the live clock, so presses faster than a landing
    // takes still sum to the final destination -- the clock stands at the
    // old anchor mid-landing, and reading it (or worse, resetting to -1 on
    // each landing) made bursts resolve to the wrong place. Always valid.
    @Volatile
    private var intendedPositionNanos = 0L

    // True between issuing a seek and its landing; while set, the decode
    // loop must not overwrite [intendedPositionNanos] with the frame it
    // publishes (the accumulated burst target wins).
    @Volatile
    private var seekInFlight = false
    private val stateBeforeSeek = java.util.concurrent.atomic.AtomicReference<State?>(null)
    private val audioPipeline: AudioPipeline? =
        if (audio) AudioPipeline(path, sink ?: JavaSoundSink(), loop) else null
    private lateinit var clock: MediaClock

    // When audio masters the clock, video never re-anchors it: seeks and
    // loop wraps are anchored by the audio thread at its actual landing.
    private var ownsClock = true
    private var lastPublishedPts = 0L

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

    /** Jumps to [ptsNanos] (frame-precise); revives an [State.Ended] player. */
    fun seek(ptsNanos: Long) {
        val target = ptsNanos.coerceAtLeast(0)
        seekInFlight = true
        intendedPositionNanos = target
        commands.put(Command.Seek(target))
    }

    /**
     * Seeks [deltaNanos] relative to the intended playhead -- the right
     * primitive for +N/-N buttons. Rapid presses accumulate to one
     * destination regardless of how far behind the clock's anchor lags
     * during a landing.
     */
    fun seekBy(deltaNanos: Long) = seek(intendedPositionNanos + deltaNanos)

    /** Linear 0..1 volume; no-op for silent playback. */
    fun setVolume(volume: Float) {
        audioPipeline?.setVolume(volume)
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

        val decoder = try {
            FrameSources.open(path)
        } catch (t: Throwable) {
            if (audioClock != null) {
                // No video stream but the audio plays: frameless mode.
                framelessLoop()
                state = State.Closed
            } else {
                state = State.Failed(t)
            }
            audioPipeline?.close()
            return
        }
        try {
            if (ownsClock) clock.start(0)
            state = State.Playing
            decodeLoop(decoder)
            state = State.Closed
        } catch (t: Throwable) {
            state = State.Failed(t)
        } finally {
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

            val frame = decoder.nextFrame(buffer?.writing?.rgba)
            if (frame == null) {
                if (loop) {
                    decoder.seekTo(0)
                    if (ownsClock) {
                        clock.seek(0)
                    } else if (!awaitClockWrap()) {
                        return
                    }
                } else {
                    state = State.Ended
                }
                continue
            }

            // Pace: sleep until the frame is due, waking early for commands.
            val generation = seekGeneration
            while (state is State.Playing) {
                val wait = clock.nanosUntilDue(frame.ptsNanos)
                if (wait <= 0) break
                val c = commands.poll(wait, TimeUnit.NANOSECONDS) ?: continue
                if (!handle(c, decoder)) return
                if (seekGeneration != generation) break
            }
            // A seek published its own target frame and moved the decoder;
            // the in-flight frame is stale. A pause mid-pace drops the frame
            // too -- one missing frame around a pause is invisible.
            if (seekGeneration != generation || state !is State.Playing) continue

            publish(frame)
        }
    }

    private fun handle(cmd: Command, decoder: FrameSource?): Boolean = when (cmd) {
        Command.Close -> false
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
        is Command.Seek -> {
            audioPipeline?.seek(cmd.ptsNanos)
            val keepRunning = if (decoder != null) {
                performSeek(decoder, cmd.ptsNanos)
            } else {
                // Frameless (audio-only): no landing to wait for.
                seekInFlight = false
                if (state is State.Ended) state = State.Playing
                true
            }
            // Sound stays frozen at the anchor until the landing is done;
            // released here even when the landing ended the stream.
            audioPipeline?.videoLanded()
            keepRunning
        }
    }

    /**
     * The audio side wraps the clock on ITS end-of-stream; video parks
     * here after its own EOF until time restarts, staying responsive to
     * commands. Returns false on Close.
     */
    private fun awaitClockWrap(): Boolean {
        val wrapped = lastPublishedPts / 2
        while (state is State.Playing && lastPublishedPts > 0 && clock.mediaNanos() > wrapped) {
            val cmd = commands.poll(20, TimeUnit.MILLISECONDS) ?: continue
            if (!handle(cmd, decoder = null)) return false
            if (state !is State.Playing) break
        }
        return true
    }

    /**
     * Frame-precise seek: the demuxer lands on the keyframe at-or-before
     * the target, then frames are decoded (and dropped) forward until the
     * target is reached; that frame is published immediately.
     *
     * The decode-forward run can span seconds of footage (keyframes are
     * sparse), so newer seeks queued meanwhile supersede the landing in
     * progress -- rapid presses cost one landing at the final target, not
     * a landing each. Returns false when a Close arrived mid-landing.
     */
    private fun performSeek(decoder: FrameSource, targetNanos: Long): Boolean {
        seekGeneration++
        // Remember what to return to (Playing/Paused/Ended) and advertise
        // the landing so a consumer can show a loading affordance.
        stateBeforeSeek.compareAndSet(null, state.takeIf { it != State.Seeking } ?: State.Playing)
        state = State.Seeking
        var target = targetNanos
        decoder.seekTo(target)
        val debugStart = if (DEBUG_SEEK) System.nanoTime() else 0L
        var dropped = 0
        var landedFromKeyframe = Long.MIN_VALUE
        while (true) {
            when (val next = commands.peek()) {
                is Command.Seek -> {
                    commands.poll()
                    target = next.ptsNanos
                    audioPipeline?.seek(target)
                    decoder.seekTo(target)
                    dropped = 0
                    landedFromKeyframe = Long.MIN_VALUE
                    continue
                }
                Command.Close -> return false
                else -> {}
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
            if (f.ptsNanos >= target) {
                if (ownsClock) clock.seek(f.ptsNanos)
                publish(decoder.convertLast(buffer?.writing?.rgba))
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

    private fun publish(frame: VideoDecoder.RgbaFrame) {
        val buf = buffer
        if (buf == null || buf.writing.rgba.size != frame.rgba.size) {
            // First frame, or a mid-stream geometry change: rebuild the
            // slots around the new size and copy this one frame over.
            val fresh = TripleBuffer(
                FrameSlot(frame.rgba.size),
                FrameSlot(frame.rgba.size),
                FrameSlot(frame.rgba.size),
            )
            frame.rgba.copyInto(fresh.writing.rgba)
            stamp(fresh.writing, frame)
            fresh.publish()
            buffer = fresh
            return
        }
        // Sizes match, so the decoder wrote straight into buf.writing.rgba.
        stamp(buf.writing, frame)
        buf.publish()
    }

    private fun stamp(slot: FrameSlot, frame: VideoDecoder.RgbaFrame) {
        slot.width = frame.width
        slot.height = frame.height
        slot.ptsNanos = frame.ptsNanos
        lastPublishedPts = frame.ptsNanos
        // During normal playback the intended playhead tracks the screen;
        // during a seek burst it is the accumulating target, which the
        // landing's own publish must not clobber.
        if (!seekInFlight) intendedPositionNanos = frame.ptsNanos
    }

    private companion object {
        val DEBUG_SEEK = System.getenv("SKINEMA_DEBUG_SEEK") != null
    }
}
