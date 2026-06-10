package dev.hivens.skinema.player

import dev.hivens.skinema.core.PlaybackClock
import dev.hivens.skinema.core.TripleBuffer
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
    private val clock: PlaybackClock = PlaybackClock(),
) : AutoCloseable {

    sealed interface State {
        data object Opening : State
        data object Playing : State
        data object Paused : State

        /** Non-looping playback ran out of frames; a seek revives it. */
        data object Ended : State

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

    private val thread = Thread(::run, "skinema-decode").apply {
        isDaemon = true
        start()
    }

    /**
     * The freshest published frame, or null when nothing new arrived since
     * the previous call (keep showing what you have).
     */
    fun acquireFrame(): FrameSlot? = buffer?.acquire()

    fun pause() = commands.put(Command.Pause)
    fun resume() = commands.put(Command.Resume)

    /** Jumps to [ptsNanos] (frame-precise); revives an [State.Ended] player. */
    fun seek(ptsNanos: Long) = commands.put(Command.Seek(ptsNanos))

    override fun close() {
        commands.put(Command.Close)
        thread.join(5_000)
    }

    // -- Decode thread --------------------------------------------------------

    private fun run() {
        val decoder = try {
            VideoDecoder.open(path)
        } catch (t: Throwable) {
            state = State.Failed(t)
            return
        }
        try {
            clock.start(0)
            state = State.Playing
            decodeLoop(decoder)
            state = State.Closed
        } catch (t: Throwable) {
            state = State.Failed(t)
        } finally {
            runCatching { decoder.close() }
        }
    }

    private fun decodeLoop(decoder: VideoDecoder) {
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
                    clock.seek(0)
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

    private fun handle(cmd: Command, decoder: VideoDecoder): Boolean = when (cmd) {
        Command.Close -> false
        Command.Pause -> {
            if (state is State.Playing) {
                clock.pause()
                state = State.Paused
            }
            true
        }
        Command.Resume -> {
            if (state is State.Paused) {
                clock.resume()
                state = State.Playing
            }
            true
        }
        is Command.Seek -> {
            performSeek(decoder, cmd.ptsNanos)
            true
        }
    }

    /**
     * Frame-precise seek: the demuxer lands on the keyframe at-or-before
     * the target, then frames are decoded (and dropped) forward until the
     * target is reached; that frame is published immediately.
     */
    private fun performSeek(decoder: VideoDecoder, targetNanos: Long) {
        seekGeneration++
        decoder.seekTo(targetNanos)
        while (true) {
            val f = decoder.nextFrame(buffer?.writing?.rgba)
            if (f == null) {
                // Seeked past the last frame: same treatment as EOF.
                if (loop) {
                    decoder.seekTo(0)
                    clock.seek(0)
                    if (state is State.Ended) state = State.Playing
                } else {
                    state = State.Ended
                }
                return
            }
            if (f.ptsNanos >= targetNanos) {
                clock.seek(f.ptsNanos)
                if (state is State.Ended) state = State.Playing
                publish(f)
                return
            }
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
    }
}
