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
        data object Close : Command
    }

    private val commands = LinkedBlockingQueue<Command>()
    private var clock: AudioClock? = null
    private var paused = false

    private val thread = Thread(::run, "skinema-audio").apply {
        isDaemon = true
        start()
    }

    fun pause() = commands.put(Command.Pause)
    fun resume() = commands.put(Command.Resume)
    fun seek(ptsNanos: Long) = commands.put(Command.Seek(ptsNanos))
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
            if (paused || isEnded) {
                val idle = commands.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (!handle(idle, decoder, theClock)) return
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
                sink.start()
                clock.resume()
                paused = false
            }
            true
        }
        is Command.Seek -> {
            performSeek(decoder, clock, cmd.ptsNanos)
            true
        }
    }

    /**
     * Sample-precise seek: land on the chunk covering the target, crop the
     * leading samples, re-anchor the clock at the exact crop point, and
     * write the remainder. Revives an ended pipeline.
     */
    private fun performSeek(decoder: AudioDecoder, clock: AudioClock, targetNanos: Long) {
        decoder.seekTo(targetNanos)
        sink.flush()
        isEnded = false
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
            clock.seek(chunk.ptsNanos + skipSamples * 1_000_000_000L / chunk.sampleRate)
            sink.write(chunk.pcm, skipSamples * BYTES_PER_FRAME, chunk.byteCount - skipSamples * BYTES_PER_FRAME)
            return
        }
    }

    private companion object {
        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        const val BYTES_PER_FRAME = 4
    }
}
