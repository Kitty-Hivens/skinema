package dev.hivens.skinema.audio

import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.AudioTrack
import dev.hivens.skinema.libav.Chapter
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    private val initialTrack: Int? = null,
) {

    val clockFuture = CompletableFuture<AudioClock?>()

    /** Non-looping playback ran out of samples (after the sink drained). */
    @Volatile
    var isEnded = false
        private set

    /**
     * The container's duration, for frameless (audio-only) playback; set
     * before [clockFuture] resolves, so a reader holding the clock sees it.
     */
    @Volatile
    var durationNanos: Long? = null
        private set

    /** Every audio stream of the container; set before [clockFuture] resolves. */
    @Volatile
    var tracks: List<AudioTrack> = emptyList()
        private set

    /** Format-level tags, chapters and cover art for frameless playback. */
    @Volatile
    var tags: Map<String, String> = emptyMap()
        private set

    @Volatile
    var chapters: List<Chapter> = emptyList()
        private set

    @Volatile
    var coverArt: ByteArray? = null
        private set

    /** The stream actually playing -- always a member of [tracks]. */
    @Volatile
    var activeAudioTrack: Int? = null
        private set

    private sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        data class Seek(val ptsNanos: Long) : Command
        data class SwitchTrack(val streamIndex: Int) : Command
        data object VideoLanded : Command
        data object Close : Command
    }

    private val commands = LinkedBlockingQueue<Command>()
    private var clock: AudioClock? = null
    private var paused = false

    // The decoder is a field, not a parameter: a track switch swaps it,
    // and a single access path keeps every seek and loop wrap on the
    // CURRENT decoder. Owned by the audio thread (confined arena).
    private lateinit var decoder: AudioDecoder

    /**
     * Seeks issued but not yet performed. While nonzero the clock's
     * readings are stale -- it stands at a pre-seek anchor -- and the
     * video side must not judge frame lateness against it: a backward
     * burst otherwise reads as a forward chase and burns the decoder
     * past the real position. Zeroed when the thread dies, so a dead
     * pipeline cannot hold the video side hostage.
     */
    val pendingSeeks = AtomicInteger(0)

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

    // End pts of the last PCM handed to the sink; the EOF tail wait runs
    // until the clock (= the device) reaches it.
    private var lastWrittenEndNanos = 0L

    private val thread = Thread(::run, "skinema-audio").apply {
        isDaemon = true
        start()
    }

    fun pause() = commands.put(Command.Pause)
    fun resume() = commands.put(Command.Resume)

    fun seek(ptsNanos: Long) {
        pendingSeeks.incrementAndGet()
        commands.put(Command.Seek(ptsNanos))
    }

    /** Switches to another audio stream of the same file, in place. */
    fun selectTrack(streamIndex: Int) = commands.put(Command.SwitchTrack(streamIndex))

    /** The video side finished its seek landing; sound may run again. */
    fun videoLanded() = commands.put(Command.VideoLanded)

    fun setVolume(volume: Float) = sink.setVolume(volume)

    fun close() {
        commands.put(Command.Close)
        thread.join(5_000)
    }

    // -- Audio thread ----------------------------------------------------------

    private fun run() {
        val opened = try {
            AudioDecoder.openOrNull(path, initialTrack)
        } catch (_: Throwable) {
            clockFuture.complete(null)
            return
        }
        if (opened == null) {
            clockFuture.complete(null)
            return
        }
        decoder = opened
        durationNanos = opened.durationNanos
        tracks = opened.tracks
        activeAudioTrack = opened.streamIndex
        tags = opened.tags
        chapters = opened.chapters
        coverArt = opened.coverArt
        try {
            pump()
        } catch (_: Throwable) {
            clock?.detachToWallTime()
        } finally {
            // The hostage guarantee comes first: a throwing close must not
            // leave the video side gated on seeks no one will perform.
            pendingSeeks.set(0)
            runCatching { decoder.close() }
            runCatching { sink.close() }
            // A pipeline that never produced a clock must still unblock the
            // waiting player.
            clockFuture.complete(null)
        }
    }

    private fun pump() {
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
        lastWrittenEndNanos = first.ptsNanos + (first.byteCount / BYTES_PER_FRAME) * 1_000_000_000L / first.sampleRate
        sink.write(first.pcm, 0, first.byteCount)

        while (true) {
            var cmd = commands.poll()
            while (cmd != null) {
                if (!handle(cmd)) return
                cmd = commands.poll()
            }
            if (paused || isEnded || awaitingLanding) {
                val idle = commands.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (!handle(idle)) return
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
                when (awaitTailPlayedOut()) {
                    TailWait.PLAYED_OUT -> if (loop) {
                        decoder.seekTo(0)
                        checkNotNull(clock).seek(0)
                    } else {
                        isEnded = true
                    }
                    TailWait.INTERRUPTED -> {}
                    TailWait.CLOSE -> return
                }
                continue
            }
            lastWrittenEndNanos = chunk.ptsNanos + (chunk.byteCount / BYTES_PER_FRAME) * 1_000_000_000L / chunk.sampleRate
            // Blocking write -- this IS the pacing.
            sink.write(chunk.pcm, 0, chunk.byteCount)
        }
    }

    private fun handle(cmd: Command): Boolean = when (cmd) {
        Command.Close -> false
        Command.Pause -> {
            if (!paused) {
                sink.stop()
                checkNotNull(clock).pause()
                paused = true
            }
            true
        }
        Command.Resume -> {
            if (paused) {
                // Mid-landing the sink must stay frozen; VideoLanded starts it.
                if (!awaitingLanding) sink.start()
                checkNotNull(clock).resume()
                paused = false
            }
            true
        }
        is Command.Seek -> {
            // Coalesce the backlog: a burst of seeks needs one landing at
            // the final target, not a flush-and-anchor per press -- the
            // intermediate anchors are stale clock readings the video
            // side would chase. Landings interleaved in the burst only
            // matter if one follows the LAST seek.
            var target = cmd.ptsNanos
            var consumed = 1
            var landedAfter = false
            while (true) {
                when (val next = commands.peek()) {
                    is Command.Seek -> {
                        commands.poll()
                        target = next.ptsNanos
                        consumed++
                        landedAfter = false
                    }
                    Command.VideoLanded -> {
                        commands.poll()
                        landedAfter = true
                    }
                    else -> break
                }
            }
            performSeek(target)
            pendingSeeks.addAndGet(-consumed)
            if (landedAfter) {
                awaitingLanding = false
                if (!paused && !isEnded) sink.start()
                if (DEBUG_SEEK) System.err.println("[audio-seek] landed posAtStart=${sink.framePosition()}")
            }
            true
        }
        is Command.SwitchTrack -> {
            switchTrack(cmd.streamIndex)
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
    private fun performSeek(targetNanos: Long) {
        val theClock = checkNotNull(clock)
        sink.stop()
        sink.flush()
        if (DEBUG_SEEK) System.err.println("[audio-seek] target=${targetNanos / 1_000_000}ms posAtFlush=${sink.framePosition()}")
        pendingPcm = null
        awaitingLanding = true
        isEnded = false
        val crop = cropAt(decoder, targetNanos)
        if (crop == null) {
            // Seeked past the last sample.
            if (loop) {
                decoder.seekTo(0)
                theClock.seek(0)
            } else {
                theClock.seek(targetNanos)
                isEnded = true
            }
            return
        }
        theClock.seek(crop.anchorNanos)
        pendingPcm = crop.remainder
        lastWrittenEndNanos = crop.chunkEndNanos
        if (DEBUG_SEEK) {
            System.err.println(
                "[audio-seek] anchored=${crop.anchorNanos / 1_000_000}ms posAtAnchor=${sink.framePosition()} pending=${crop.remainder.size}B",
            )
        }
    }

    /**
     * Switches to another audio stream in place. Two ordering rules carry
     * the correctness: FREEZE FIRST (the line keeps playing its buffered
     * tail through any slower path, and a position read before the freeze
     * would rebase the mastered clock backward -- the one move the video
     * side's invariants forbid), and OPEN-NEW-BEFORE-CLOSE-OLD (every
     * failure below leaves the old decoder, line and clock untouched).
     */
    private fun switchTrack(streamIndex: Int) {
        val theClock = clock ?: return
        if (streamIndex == decoder.streamIndex) return
        if (tracks.none { it.streamIndex == streamIndex }) return

        val wasAwaiting = awaitingLanding
        sink.stop()
        sink.flush()
        pendingPcm = null
        val pos = theClock.mediaNanos()

        val next = try {
            AudioDecoder.openOrNull(path, streamIndex)
        } catch (_: Throwable) {
            null
        }
        if (next == null) {
            if (!wasAwaiting && !paused && !isEnded) sink.start()
            return
        }
        val crop = cropAt(next, pos)
        if (crop == null) {
            // The new track ends before the playhead; refuse rather than
            // wrap the mastered clock mid-lap or strand a non-looping
            // player at a frozen anchor.
            runCatching { next.close() }
            if (!wasAwaiting && !paused && !isEnded) sink.start()
            return
        }

        runCatching { decoder.close() }
        decoder = next
        durationNanos = next.durationNanos
        // The fresh line starts at frame position zero and may run at a
        // different rate; rebase reads both at one anchor. Between open
        // and rebase the old base makes raw readings negative -- the
        // not-yet-reset monotonic floor clamps that window.
        sink.open(crop.sampleRate)
        if (wasAwaiting || paused || isEnded) sink.stop() // open() starts the device by contract
        theClock.rebase(crop.anchorNanos, crop.sampleRate)
        pendingPcm = crop.remainder
        lastWrittenEndNanos = crop.chunkEndNanos
        activeAudioTrack = next.streamIndex
        if (DEBUG_SEEK) {
            System.err.println(
                "[audio-switch] track=${next.streamIndex} anchored=${crop.anchorNanos / 1_000_000}ms rate=${crop.sampleRate}",
            )
        }
    }

    private class Crop(
        val anchorNanos: Long,
        val remainder: ByteArray,
        val chunkEndNanos: Long,
        val sampleRate: Int,
    )

    /**
     * Positions [d] at the chunk covering [targetNanos] and crops the
     * leading samples; null when the stream ends before the target.
     * Mutates no pipeline state -- the callers apply their own policy.
     */
    private fun cropAt(d: AudioDecoder, targetNanos: Long): Crop? {
        d.seekTo(targetNanos)
        while (true) {
            val chunk = d.nextChunk() ?: return null
            val samples = chunk.byteCount / BYTES_PER_FRAME
            val chunkEnd = chunk.ptsNanos + samples * 1_000_000_000L / chunk.sampleRate
            if (chunkEnd <= targetNanos) continue

            val skipSamples = ((targetNanos - chunk.ptsNanos).coerceAtLeast(0) * chunk.sampleRate / 1_000_000_000L)
                .toInt()
                .coerceAtMost(samples)
            val anchorNanos = chunk.ptsNanos + skipSamples * 1_000_000_000L / chunk.sampleRate
            // Copied out because the decoder reuses chunk.pcm.
            return Crop(
                anchorNanos = anchorNanos,
                remainder = chunk.pcm.copyOfRange(skipSamples * BYTES_PER_FRAME, chunk.byteCount),
                chunkEndNanos = chunkEnd,
                sampleRate = chunk.sampleRate,
            )
        }
    }

    private enum class TailWait { PLAYED_OUT, INTERRUPTED, CLOSE }

    /**
     * Waits until the clock -- and therefore the device -- reaches the end
     * of the last written chunk, while staying on the command queue. The
     * old sink.drain() deafened this thread for the whole buffered tail,
     * so a seek pressed near a loop wrap waited the tail out for nothing
     * (its first act is flushing that tail). A wall deadline bounds the
     * wait against a stalled device; past it the tail is declared played.
     */
    private fun awaitTailPlayedOut(): TailWait {
        val theClock = checkNotNull(clock)
        val deadline = System.nanoTime() +
            (lastWrittenEndNanos - theClock.mediaNanos()).coerceAtLeast(0) + TAIL_GRACE_NANOS
        while (theClock.mediaNanos() < lastWrittenEndNanos) {
            if (System.nanoTime() >= deadline) break
            val cmd = commands.poll(20, TimeUnit.MILLISECONDS) ?: continue
            return if (handle(cmd)) TailWait.INTERRUPTED else TailWait.CLOSE
        }
        return TailWait.PLAYED_OUT
    }

    private companion object {
        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        const val BYTES_PER_FRAME = 4

        /** Slack past the tail's nominal duration before giving up on the device. */
        const val TAIL_GRACE_NANOS = 500_000_000L

        // Same switch as VideoPlayer's landing diagnostics: the anchor
        // positions printed here are the forensics for the remaining
        // intermittent-freeze theory (device position reconciling around
        // a flush/restart).
        val DEBUG_SEEK = System.getenv("SKINEMA_DEBUG_SEEK") != null
    }
}
