package dev.hivens.skinema.audio

import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.libav.AudioDecoder
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The audio half of a player: its own thread owns an [AudioDecoder]
 * (confined arena) and pushes PCM into the [sink]; the sink's blocking
 * writes are the pacing. The sink's frame position drives the
 * [AudioClock] that masters the whole player's time.
 *
 * [clockFuture] resolves once the thread knows the truth: an [AudioClock]
 * when the file has a decodable audio stream and the device opened, null
 * otherwise -- the player then falls back to silent wall-clock playback.
 * A mid-stream failure detaches the clock to wall time so video keeps
 * moving (without sound there is nothing left to sync to).
 */
internal class AudioPipeline(
    private val path: Path,
    private val sink: PcmSink,
    private val loop: Boolean,
) {

    val clockFuture = CompletableFuture<AudioClock?>()

    /** Non-looping playback ran out of samples (after the sink drained). */
    @Volatile
    var isEnded = false
        private set

    private sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        data class Seek(val ptsNanos: Long) : Command
        data object VideoLanded : Command
        data object Close : Command
    }

    private val commands = LinkedBlockingQueue<Command>()
    private var clock: AudioClock? = null
    private var paused = false

    // A seek freezes the sink at the target anchor until the video side
    // lands there: video seeks ride a keyframe jump plus a decode-forward
    // run that can take seconds, and audio running ahead through that gap
    // is exactly the "freeze, then fast-forward chase" artifact.
    private var awaitingLanding = false

    // The seek's cropped remainder, held until the sink runs again. It must
    // not be written while the sink is stopped: a blocking write only
    // returns as the device drains the buffer, a stopped device never
    // drains, and the start() that would revive it lives on this same
    // thread -- a chunk larger than the line's buffer would deadlock the
    // pipeline for good.
    private var pendingPcm: ByteArray? = null

    private val thread = Thread(::run, "skinema-audio").apply {
        isDaemon = true
        start()
    }

    fun pause() = commands.put(Command.Pause)
    fun resume() = commands.put(Command.Resume)
    fun seek(ptsNanos: Long) = commands.put(Command.Seek(ptsNanos))

    /** The video side finished its seek landing; sound may run again. */
    fun videoLanded() = commands.put(Command.VideoLanded)

    fun setVolume(volume: Float) = sink.setVolume(volume)

    fun close() {
        commands.put(Command.Close)
        thread.join(5_000)
    }

    // -- Audio thread ----------------------------------------------------------

    private fun run() {
        val decoder = try {
            AudioDecoder.openOrNull(path)
        } catch (_: Throwable) {
            clockFuture.complete(null)
            return
        }
        if (decoder == null) {
            clockFuture.complete(null)
            return
        }
        try {
            decoder.use { pump(it) }
        } catch (_: Throwable) {
            clock?.detachToWallTime()
        } finally {
            runCatching { sink.close() }
            // A pipeline that never produced a clock must still unblock the
            // waiting player.
            clockFuture.complete(null)
        }
    }

    private fun pump(decoder: AudioDecoder) {
        val first = decoder.nextChunk()
        if (first == null) {
            clockFuture.complete(null)
            return
        }
        val theClock = try {
            sink.open(first.sampleRate)
            AudioClock(first.sampleRate) { sink.framePosition() }
        } catch (_: Throwable) {
            // No audio device: silent playback on the player's wall clock.
            clockFuture.complete(null)
            return
        }
        clock = theClock
        theClock.start(first.ptsNanos)
        clockFuture.complete(theClock)
        sink.write(first.pcm, 0, first.byteCount)

        while (true) {
            var cmd = commands.poll()
            while (cmd != null) {
                if (!handle(cmd, decoder, theClock)) return
                cmd = commands.poll()
            }
            if (paused || isEnded || awaitingLanding) {
                val idle = commands.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (!handle(idle, decoder, theClock)) return
                continue
            }

            pendingPcm?.let {
                pendingPcm = null
                sink.write(it, 0, it.size)
                continue
            }

            val chunk = decoder.nextChunk()
            if (chunk == null) {
                // Let the buffered tail play out before deciding the time.
                sink.drain()
                if (loop) {
                    decoder.seekTo(0)
                    theClock.seek(0)
                } else {
                    isEnded = true
                }
                continue
            }
            // Blocking write -- this IS the pacing.
            sink.write(chunk.pcm, 0, chunk.byteCount)
        }
    }

    private fun handle(cmd: Command, decoder: AudioDecoder, clock: AudioClock): Boolean = when (cmd) {
        Command.Close -> false
        Command.Pause -> {
            if (!paused) {
                sink.stop()
                clock.pause()
                paused = true
            }
            true
        }
        Command.Resume -> {
            if (paused) {
                // Mid-landing the sink must stay frozen; VideoLanded starts it.
                if (!awaitingLanding) sink.start()
                clock.resume()
                paused = false
            }
            true
        }
        is Command.Seek -> {
            performSeek(decoder, clock, cmd.ptsNanos)
            true
        }
        Command.VideoLanded -> {
            if (awaitingLanding) {
                awaitingLanding = false
                if (!paused && !isEnded) sink.start()
                if (DEBUG_SEEK) System.err.println("[audio-seek] landed posAtStart=${sink.framePosition()}")
            }
            true
        }
    }

    /**
     * Sample-precise seek: freeze the sink, land on the chunk covering the
     * target, crop the leading samples, re-anchor the clock at the exact
     * crop point, and hold the remainder as [pendingPcm] for the first
     * write after the sink runs again. Sound resumes when the video side
     * reports its own landing ([videoLanded]) -- the frozen sink freezes
     * the clock, so video lands against a standing target instead of
     * chasing a running one. Revives an ended pipeline.
     */
    private fun performSeek(decoder: AudioDecoder, clock: AudioClock, targetNanos: Long) {
        sink.stop()
        sink.flush()
        if (DEBUG_SEEK) System.err.println("[audio-seek] target=${targetNanos / 1_000_000}ms posAtFlush=${sink.framePosition()}")
        pendingPcm = null
        awaitingLanding = true
        isEnded = false
        decoder.seekTo(targetNanos)
        while (true) {
            val chunk = decoder.nextChunk()
            if (chunk == null) {
                // Seeked past the last sample.
                if (loop) {
                    decoder.seekTo(0)
                    clock.seek(0)
                } else {
                    clock.seek(targetNanos)
                    isEnded = true
                }
                return
            }
            val samples = chunk.byteCount / BYTES_PER_FRAME
            val chunkEnd = chunk.ptsNanos + samples * 1_000_000_000L / chunk.sampleRate
            if (chunkEnd <= targetNanos) continue

            val skipSamples = ((targetNanos - chunk.ptsNanos).coerceAtLeast(0) * chunk.sampleRate / 1_000_000_000L)
                .toInt()
                .coerceAtMost(samples)
            val anchorNanos = chunk.ptsNanos + skipSamples * 1_000_000_000L / chunk.sampleRate
            clock.seek(anchorNanos)
            // Copied out because the decoder reuses chunk.pcm.
            pendingPcm = chunk.pcm.copyOfRange(skipSamples * BYTES_PER_FRAME, chunk.byteCount)
            if (DEBUG_SEEK) {
                System.err.println(
                    "[audio-seek] anchored=${anchorNanos / 1_000_000}ms posAtAnchor=${sink.framePosition()} pending=${chunk.byteCount - skipSamples * BYTES_PER_FRAME}B",
                )
            }
            return
        }
    }

    private companion object {
        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        const val BYTES_PER_FRAME = 4

        // Same switch as VideoPlayer's landing diagnostics: the anchor
        // positions printed here are the forensics for the remaining
        // intermittent-freeze theory (device position reconciling around
        // a flush/restart).
        val DEBUG_SEEK = System.getenv("SKINEMA_DEBUG_SEEK") != null
    }
}
