package dev.hivens.skinema.audio

import dev.hivens.skinema.Debug
import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.AudioTrack
import dev.hivens.skinema.libav.Chapter
import dev.hivens.skinema.libav.TempoFilter
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
    private val writeStallNanos: Long = DEFAULT_WRITE_STALL_NANOS,
    private val recoveryIntervalMs: Long = DEFAULT_RECOVERY_INTERVAL_MS,
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
        data class SetTempo(val tempo: Double) : Command
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

    // Playback rate. At 1.0 the stretcher does not exist and PCM flows
    // through untouched; otherwise every write passes the atempo graph.
    // All three owned by the audio thread.
    private var tempo = 1.0
    private var tempoFilter: TempoFilter? = null
    private var sampleRate = 0

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

    // Set true around each blocking sink write so the watchdog can tell a
    // genuinely stuck device (the write never returns, the position stays
    // frozen) from a legitimately stopped one (pause, seek, landing).
    @Volatile
    private var writeInFlight = false

    @Volatile
    private var watchdogStop = false
    private var watchdog: Thread? = null

    // The watchdog sets this when the device stalls out: it closes the line
    // to free the stuck write, and the audio thread sees the flag and runs
    // recovery. Cleared once a fresh line is anchored (#19).
    @Volatile
    private var deviceLost = false

    // close() sets this so recovery stops retrying and the thread exits
    // rather than parking forever in the reopen loop on a gone device.
    @Volatile
    private var closing = false

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

    /** Playback rate, pitch preserved; the caller clamps to atempo's range. */
    fun setTempo(tempo: Double) = commands.put(Command.SetTempo(tempo))

    /** The video side finished its seek landing; sound may run again. */
    fun videoLanded() = commands.put(Command.VideoLanded)

    fun setVolume(volume: Float) = sink.setVolume(volume)

    fun close() {
        closing = true
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
            watchdogStop = true
            watchdog?.interrupt()
            // The hostage guarantee comes first: a throwing close must not
            // leave the video side gated on seeks no one will perform.
            pendingSeeks.set(0)
            runCatching { tempoFilter?.close() }
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
        if (closing) {
            // The player gave up waiting for the device (clockFuture timeout,
            // #17) and closed us; do not resolve a second clock or hold the
            // device it has stopped expecting.
            runCatching { sink.close() }
            clockFuture.complete(null)
            return
        }
        clock = theClock
        theClock.start(first.ptsNanos)
        clockFuture.complete(theClock)
        startWatchdog()
        sampleRate = first.sampleRate
        lastWrittenEndNanos = first.ptsNanos + (first.byteCount / BYTES_PER_FRAME) * 1_000_000_000L / first.sampleRate
        guardedWrite(first.pcm, 0, first.byteCount)

        while (true) {
            if (deviceLost && !recover()) return
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
                writeOut(it, it.size)
                continue
            }

            val chunk = decoder.nextChunk()
            if (chunk == null) {
                // The stretcher still holds part of the stream's tail;
                // surface it before the time decision, then start the
                // next lap (or nothing) from a clean graph.
                tempoFilter?.let { f ->
                    val n = f.flush()
                    if (n > 0) guardedWrite(f.output, 0, n)
                    f.reset()
                }
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
            writeOut(chunk.pcm, chunk.byteCount)
        }
    }

    /**
     * The one path PCM takes to the device: straight through at 1.0,
     * through the atempo graph otherwise. Output bytes shrink or grow by
     * 1/tempo; the blocking write paces either way.
     */
    private fun writeOut(pcm: ByteArray, byteCount: Int) {
        val filter = tempoFilter
        if (filter == null) {
            guardedWrite(pcm, 0, byteCount)
            return
        }
        val n = filter.process(pcm, byteCount)
        if (n > 0) guardedWrite(filter.output, 0, n)
    }

    /**
     * The blocking write, watched. [writeInFlight] tells [runWatchdog] a
     * write is in progress, so a stalled one detaches the clock; off the
     * write the device may legitimately sit still (pause, seek, landing).
     */
    private fun guardedWrite(data: ByteArray, offset: Int, length: Int) {
        writeInFlight = true
        try {
            sink.write(data, offset, length)
        } finally {
            writeInFlight = false
        }
    }

    private fun startWatchdog() {
        watchdog = Thread(::runWatchdog, "skinema-audio-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Frees video when the device dies silently and drives recovery. A
     * [PcmSink.write] that blocks without throwing -- a vanished ALSA sink,
     * a yanked USB DAC, a popped-out jack -- never reaches the catch in
     * [run], so a frozen frame position would freeze the picture with it.
     * While a write is in flight this watches the device's frame position;
     * if it stops advancing for [writeStallNanos] the clock detaches to
     * wall time (video keeps moving) and the line is closed so the stuck
     * write returns -- which lets the audio thread run [recover]. The
     * watchdog stays alive across the outage to catch a second loss once
     * the device is back.
     */
    private fun runWatchdog() {
        val pollMs = (writeStallNanos / 4_000_000L).coerceIn(20L, 250L)
        var lastFrames = Long.MIN_VALUE
        var lastProgressWall = System.nanoTime()
        while (!watchdogStop) {
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                return
            }
            val theClock = clock ?: continue
            // While recovery is in flight the audio thread is reopening the
            // line; do not read its position or fire a second time.
            if (deviceLost || !writeInFlight) {
                lastFrames = Long.MIN_VALUE
                continue
            }
            val pos = sink.framePosition()
            if (lastFrames == Long.MIN_VALUE || pos != lastFrames) {
                lastFrames = pos
                lastProgressWall = System.nanoTime()
                continue
            }
            if (System.nanoTime() - lastProgressWall >= writeStallNanos) {
                theClock.detachToWallTime()
                deviceLost = true
                runCatching { sink.close() }.onFailure { Debug.trace("audio sink close on stall", it) }
                lastFrames = Long.MIN_VALUE
            }
        }
    }

    /**
     * Device-loss recovery, on the audio thread. The watchdog has detached
     * the clock to wall time and closed the line, so video keeps moving and
     * the stuck write has returned. Reopen the device on a fixed cadence
     * ([recoveryIntervalMs], env SKINEMA_AUDIO_RECOVERY_MS) until it comes
     * back -- a popped-out jack or a re-routed sink can return seconds
     * later. On return, resync to where video advanced and rebase the clock
     * onto the fresh line so sound rejoins in step. Returns false when
     * close() intervenes, so the thread exits instead of parking in the
     * loop (#19).
     */
    private fun recover(): Boolean {
        while (!closing) {
            if (tryReopen()) return true
            try {
                Thread.sleep(recoveryIntervalMs)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun tryReopen(): Boolean {
        val theClock = clock ?: return false
        return runCatching {
            // Resync to where video advanced on the wall clock: the outage
            // audio is dropped, sound rejoins in step rather than lagging.
            val resumeAt = theClock.mediaNanos()
            decoder.seekTo(resumeAt)
            pendingPcm = null
            sink.open(sampleRate)
            theClock.rebase(resumeAt, sampleRate)
            // open() starts the device by contract; honour a pause, landing
            // or end-of-stream that began during the outage.
            if (paused || awaitingLanding || isEnded) sink.stop()
            deviceLost = false
            true
        }.getOrElse {
            Debug.trace("audio device reopen", it)
            false
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
        is Command.SetTempo -> {
            applyTempo(cmd.tempo)
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
        // Whatever the stretcher buffered belongs to the old position.
        tempoFilter?.reset()
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
        // The stretcher is rate-bound; the new track may run at another.
        val oldFilter = tempoFilter
        if (oldFilter != null && sampleRate != crop.sampleRate) {
            oldFilter.close()
            tempoFilter = TempoFilter(crop.sampleRate, tempo)
        } else {
            oldFilter?.reset()
        }
        sampleRate = crop.sampleRate
        pendingPcm = crop.remainder
        lastWrittenEndNanos = crop.chunkEndNanos
        activeAudioTrack = next.streamIndex
        if (DEBUG_SEEK) {
            System.err.println(
                "[audio-switch] track=${next.streamIndex} anchored=${crop.anchorNanos / 1_000_000}ms rate=${crop.sampleRate}",
            )
        }
    }

    /**
     * Playback-rate change. The sink's buffered tail was stretched at the
     * OLD tempo: re-anchoring the clock over it would leave a permanent
     * A/V offset of that tail's length times the tempo delta. So the
     * change is a mini-seek at the playhead -- freeze first, read the
     * position, rebuild the stretcher, re-scale the clock, re-crop the
     * stream sample-precise. Costs the same ~line-buffer hold as a seek.
     */
    private fun applyTempo(newTempo: Double) {
        if (newTempo == tempo) return
        val theClock = checkNotNull(clock)
        val wasAwaiting = awaitingLanding
        sink.stop()
        sink.flush()
        val pos = theClock.mediaNanos()

        // Open-new-before-close-old: a stretcher that cannot build leaves
        // tempo, clock and stream untouched.
        val next = if (newTempo == 1.0) {
            null
        } else {
            try {
                TempoFilter(sampleRate, newTempo)
            } catch (_: Throwable) {
                if (!wasAwaiting && !paused && !isEnded) sink.start()
                return
            }
        }
        tempoFilter?.close()
        tempoFilter = next
        tempo = newTempo
        theClock.setTempo(newTempo)

        pendingPcm = null
        val crop = cropAt(decoder, pos)
        if (crop == null) {
            // The playhead sits past the last sample; nothing to re-feed.
            theClock.seek(pos)
        } else {
            theClock.seek(crop.anchorNanos)
            pendingPcm = crop.remainder
            lastWrittenEndNanos = crop.chunkEndNanos
        }
        if (!wasAwaiting && !paused && !isEnded) sink.start()
        if (DEBUG_SEEK) {
            System.err.println("[audio-tempo] tempo=$newTempo anchored=${pos / 1_000_000}ms")
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
        // targetNanos and the chunk pts below are both on the zero-origin
        // timeline (AudioDecoder normalizes both directions), so no
        // start_time handling belongs here.
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
        // The remaining tail is media time; the wall pays it at 1/tempo.
        val deadline = System.nanoTime() +
            ((lastWrittenEndNanos - theClock.mediaNanos()).coerceAtLeast(0) / tempo).toLong() + TAIL_GRACE_NANOS
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

        /**
         * A write blocked this long with the device's frame position frozen
         * is a dead device; the watchdog detaches the clock to wall time.
         */
        const val DEFAULT_WRITE_STALL_NANOS = 3_000_000_000L

        /**
         * Cadence for retrying the device reopen after a mid-stream loss. A
         * val, not a const: SKINEMA_AUDIO_RECOVERY_MS overrides it so the
         * retry rate is tunable without a rebuild. Recovery keeps retrying
         * until the device returns or the player closes.
         */
        val DEFAULT_RECOVERY_INTERVAL_MS: Long =
            System.getenv("SKINEMA_AUDIO_RECOVERY_MS")?.toLongOrNull()?.coerceAtLeast(1L) ?: 400L

        // Same switch as VideoPlayer's landing diagnostics: the anchor
        // positions printed here are the forensics for the remaining
        // intermittent-freeze theory (device position reconciling around
        // a flush/restart).
        val DEBUG_SEEK = System.getenv("SKINEMA_DEBUG_SEEK") != null
    }
}
