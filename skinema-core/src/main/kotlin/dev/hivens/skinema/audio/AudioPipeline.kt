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
        data class VideoLanded(val atNanos: Long) : Command
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

    // How much sound the line still holds, in its own frames: a running
    // balance, credited by what it plays and debited by what it is handed.
    // The EOF tail wait runs on this.
    //
    // It used to run on timestamps -- wait until media time reached the end
    // pts of the last chunk written. Those are two quantities that do not
    // meet: a chunk's pts comes off the container's grid (Matroska's is a
    // millisecond, so the last chunk's nominal end sits tens of microseconds
    // past the last sample that exists), while media time counts frames the
    // device consumed. The wait could never finish on its own condition and
    // ran to its stall deadline instead -- half a second of frozen picture
    // at the end of every lap of any normally muxed file.
    private var queuedFrames = 0L
    private var lastPositionSeen = 0L

    // When the blocking write in flight started, or 0 when none is. The
    // watchdog judges a stall by how long ONE write has been outstanding,
    // which is a question it can answer without touching the device.
    //
    // It used to poll the device's frame position instead and call it stuck
    // when that stopped advancing. The position is the one thing it cannot
    // ask for: a JavaSound line answers it under the same native monitor its
    // write holds, so on the dead device this exists to rescue, the watchdog
    // parked on the very lock it came to break -- the clock was never
    // detached, and the pacer, the decode thread and the consumer's render
    // loop went down behind it. A write outstanding this long is a stall
    // whatever the device would have said.
    @Volatile
    private var writeInFlightSince = 0L

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

    /** Whether the audio thread is still there to answer a seek. */
    @Volatile
    var alive = true
        private set

    /**
     * Whether the video side should still hold the end of a lap open for
     * this side. A device in the middle of an outage is not going to answer
     * that question: recovery retries for as long as the outage lasts, which
     * is unbounded by design, and a lap held for it froze the picture on its
     * last frame for the duration -- state reporting Playing, media time
     * running on the wall clock the watchdog handed it. Sound that comes
     * back rejoins the lap the picture chose.
     */
    val hasSoundLeft: Boolean
        get() = !isEnded && !deviceLost

    // Started last, after every field the thread it starts can touch.
    // Initializers run in declaration order, so a thread started ahead of
    // one raced the constructor for it: a file with no audio stream reaches
    // finish() within microseconds, and the `alive = true` that used to run
    // after this line overwrote the `alive = false` it had just set. A dead
    // pipeline then advertised itself as live for good, and every lap fed a
    // seek and a landing into a queue nobody would ever drain.
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

    /**
     * This side will say nothing more. Every exit of the audio thread goes
     * through here, not just the one where a track played out: a file with no
     * sound in it, a machine with no device, a device that dies, a throw on
     * the way up. The video side waits on [isEnded] before it ends or wraps,
     * and a thread that left without setting it parked the player forever --
     * picture frozen on its last frame, state still reporting Playing, in
     * exactly the configurations the player documents as degrading to silent
     * playback.
     */
    private fun finish() {
        alive = false
        isEnded = true
        clockFuture.complete(null)
    }

    /** Switches to another audio stream of the same file, in place. */
    fun selectTrack(streamIndex: Int) = commands.put(Command.SwitchTrack(streamIndex))

    /** Playback rate, pitch preserved; the caller clamps to atempo's range. */
    fun setTempo(tempo: Double) = commands.put(Command.SetTempo(tempo))

    /** The video side finished its seek landing; sound may run again. */
    fun videoLanded(atNanos: Long = -1L) = commands.put(Command.VideoLanded(atNanos))

    fun setVolume(volume: Float) = sink.setVolume(volume)

    fun close() {
        closing = true
        commands.put(Command.Close)
        thread.join(5_000)
    }

    // -- Audio thread ----------------------------------------------------------

    private fun run() {
        // Both exits are before the try/finally that owns the sink, so they
        // close it themselves. A consumer's own sink -- the seam exists for
        // one holding a device, a socket, a server connection -- was
        // otherwise leaked for the life of the process by the most ordinary
        // case there is: asking for audio on a file that has none.
        val opened = try {
            AudioDecoder.openOrNull(path, initialTrack)
        } catch (_: Throwable) {
            runCatching { sink.close() }
            finish()
            return
        }
        if (opened == null) {
            runCatching { sink.close() }
            finish()
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
            finish()
        }
    }

    private fun pump() {
        val first = decoder.nextChunk()
        if (first == null) {
            clockFuture.complete(null)
            return
        }
        val theClock = try {
            openLine(first.sampleRate)
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
                // That write is the only one whose return does not pass the
                // loop top's recovery check on its way to the tail wait, and
                // a device lost inside it leaves a closed line reporting no
                // position at all -- which reads as a tail that never plays.
                // Recovery first; the end of the track keeps until after it.
                if (deviceLost) continue
                // Let the buffered tail play out before deciding the time.
                when (awaitTailPlayedOut()) {
                    // The end of the track is the end of the track, laps or
                    // not. Wrapping here used to make the sound decide where a
                    // lap ends, and a track shorter than the picture then sawed
                    // media time back to zero while the picture still had
                    // seconds to run. The player owns the lap and restarts this
                    // side with the other one.
                    TailWait.PLAYED_OUT -> {
                        isEnded = true
                        // The sink is never fed again from here, so its frame
                        // position -- and media time with it -- is frozen. A
                        // file whose audio is shorter than its video would
                        // strand the picture there forever, still reporting
                        // Playing, because video waits on a clock that stopped.
                        // Hand the rest of the timeline to the wall clock: the
                        // same hatch a dead device takes, for the same reason,
                        // and there is no sound left to synchronise to.
                        checkNotNull(clock).detachToWallTime()
                    }
                    TailWait.INTERRUPTED -> {}
                    TailWait.CLOSE -> return
                }
                continue
            }
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
     * The blocking write, watched. [writeInFlightSince] tells [runWatchdog]
     * when this write started, so one that never returns detaches the clock;
     * off the write the device may legitimately sit still (pause, seek,
     * landing) and nothing is being waited on.
     */
    private fun guardedWrite(data: ByteArray, offset: Int, length: Int) {
        writeInFlightSince = System.nanoTime()
        try {
            sink.write(data, offset, length)
            queuedFrames += length / BYTES_PER_FRAME
        } finally {
            writeInFlightSince = 0L
        }
    }

    /**
     * Opens the line and starts the tail accounting on it -- a fresh line
     * restarts its frame position, so the count restarts with it. The one
     * way this pipeline opens a line: an open that skipped the anchor would
     * leave the tail wait owed a whole previous line's worth of frames.
     */
    private fun openLine(rate: Int) {
        sink.open(rate)
        anchorTail()
    }

    /**
     * Flushes the line and restates what it is still owed. The flush throws
     * away sound this side had already counted as handed over, and how much
     * is a question only the device could answer -- by then it has
     * forgotten. Every flush here re-anchors the clock too, so this is the
     * one way the pipeline flushes.
     */
    private fun flushLine() {
        sink.flush()
        anchorTail()
    }

    private fun anchorTail() {
        queuedFrames = 0
        lastPositionSeen = sink.framePosition()
    }

    /**
     * Frames handed to the line that it has not played yet, settled against
     * the device as of now.
     *
     * A running balance rather than a difference from one fixed anchor,
     * because the anchor cannot be trusted for the life of a track: around a
     * flush some backends reconcile their frame counter non-monotonically --
     * the same behaviour [AudioClock]'s monotonic floor exists for -- and one
     * that settles BELOW the anchor taken at that flush leaves the balance
     * permanently in credit. The wait could then never finish on its own
     * condition again, which is precisely the failure this accounting
     * replaced, re-entered through the device instead of the container.
     * Crediting only forward motion costs at worst a tail called played
     * early, which stops nothing and cannot compound.
     */
    private fun framesQueued(): Long {
        val position = sink.framePosition()
        val played = position - lastPositionSeen
        lastPositionSeen = position
        if (played > 0) queuedFrames = (queuedFrames - played).coerceAtLeast(0)
        return queuedFrames
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
     * A single write outstanding for [writeStallNanos] is the signal: the
     * clock detaches to wall time (video keeps moving) and the line is
     * closed so the stuck write returns -- which lets the audio thread run
     * [recover]. The watchdog stays alive across the outage to catch a
     * second loss once the device is back.
     *
     * It deliberately asks the device nothing. Every question a line can be
     * asked goes through the same native monitor its write holds, so a
     * watchdog that polled the frame position parked on the very lock it
     * came to break. How long its own write has been outstanding is the one
     * thing it can know without the device's help, and it is enough: a live
     * line takes at most its buffer's length to accept a chunk, and the
     * stall bound is an order of magnitude past that.
     */
    private fun runWatchdog() {
        val pollMs = (writeStallNanos / 4_000_000L).coerceIn(20L, 250L)
        while (!watchdogStop) {
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                return
            }
            val theClock = clock ?: continue
            // While recovery is in flight the audio thread is reopening the
            // line; do not fire a second time.
            if (deviceLost) continue
            // Nothing outstanding: the device may legitimately sit still.
            val since = writeInFlightSince
            if (since == 0L || System.nanoTime() - since < writeStallNanos) continue
            theClock.detachToWallTime(readDevice = false)
            deviceLost = true
            runCatching { sink.close() }.onFailure { Debug.trace("audio sink close on stall", it) }
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
     *
     * The wait between attempts is spent on the command queue rather than
     * asleep. An outage is unbounded by design -- a jack can come back in
     * seconds or never -- and a deaf wait made the whole of it dead time for
     * the player: seeks queued up unanswered, so the video side saw landings
     * it was owed and held every frame it had, and the state the reopen
     * restores was stale by exactly the commands nobody read.
     */
    private fun recover(): Boolean {
        while (!closing) {
            if (tryReopen()) return true
            val cmd = try {
                commands.poll(recoveryIntervalMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return false
            } ?: continue
            if (!handle(cmd)) return false
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
            openLine(sampleRate)
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
            var landedAt = -1L
            while (true) {
                when (val next = commands.peek()) {
                    is Command.Seek -> {
                        commands.poll()
                        target = next.ptsNanos
                        consumed++
                        landedAfter = false
                    }
                    is Command.VideoLanded -> {
                        commands.poll()
                        landedAfter = true
                        landedAt = next.atNanos
                    }
                    else -> break
                }
            }
            performSeek(target)
            pendingSeeks.addAndGet(-consumed)
            if (landedAfter) finishLanding(landedAt)
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
        is Command.VideoLanded -> {
            if (awaitingLanding) finishLanding(cmd.atNanos)
            true
        }
    }

    /**
     * The picture reported its landing. When this side has nothing left to
     * play, the picture's own pts is where the timeline belongs: the seek
     * that got here could not be cropped, so nothing anchored it.
     */
    private fun finishLanding(atNanos: Long) {
        awaitingLanding = false
        if (isEnded && atNanos >= 0) clock?.seek(atNanos)
        if (!paused && !isEnded) sink.start()
        if (DEBUG_SEEK) System.err.println("[audio-seek] landed posAtStart=${sink.framePosition()}")
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
        flushLine()
        if (DEBUG_SEEK) System.err.println("[audio-seek] target=${targetNanos / 1_000_000}ms posAtFlush=${sink.framePosition()}")
        pendingPcm = null
        // Whatever the stretcher buffered belongs to the old position.
        tempoFilter?.reset()
        awaitingLanding = true
        // Coming back from the end of the track: the clock was handed to the
        // wall when the sound ran out, and a seek that starts it again has to
        // hand it back to the device. Left detached it would pace the picture
        // off wall time while the DAC plays -- the two drift apart with
        // nothing to pull them together, which is the opposite of what an
        // audio-mastered clock is for.
        val wasEnded = isEnded
        isEnded = false
        val crop = cropAt(decoder, targetNanos)
        if (crop == null) {
            // Seeked past the last sample, laps or not: this side is done.
            // Wrapping the sound to zero here made the SOUND decide where a
            // lap ends -- the mistake the orderly end of the track was already
            // cured of. A seek into a file's picture-only tail restarted the
            // track from the beginning under a picture that had legitimately
            // jumped forward, sawing media time back to zero with it.
            //
            // It does NOT place the timeline either: the target can be any
            // distance past the end of the file, and anchoring there put the
            // position beyond the duration, three times it for a seek that
            // overshot by that much. Where a finished file rests is the
            // player's to say, and it says so through [finishLanding].
            // Handing the clock to the wall is the same thing the orderly end
            // of the track does, so a picture still running is not stranded
            // on a device that stopped.
            isEnded = true
            theClock.detachToWallTime()
            return
        }
        if (wasEnded) theClock.rebase(crop.anchorNanos, crop.sampleRate) else theClock.seek(crop.anchorNanos)
        pendingPcm = crop.remainder
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
     * side's invariants forbid), and OPEN-NEW-BEFORE-CLOSE-OLD, decoder and
     * line alike: a refused track leaves the old decoder and clock playing,
     * and a device that refuses the new rate degrades to the device-loss
     * path rather than ending this side.
     */
    private fun switchTrack(streamIndex: Int) {
        val theClock = clock ?: return
        if (streamIndex == decoder.streamIndex) return
        if (tracks.none { it.streamIndex == streamIndex }) return

        val wasAwaiting = awaitingLanding
        sink.stop()
        flushLine()
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

        // Open-new-before-close-old runs all the way down to the device. The
        // line is what fails here in practice -- a device that cannot take
        // the new track's rate -- and the throw used to travel out of the
        // command handler and end the audio thread for good: the file went
        // silent, and the video side read the dead pipeline as a track that
        // had finished. The fresh line also restarts its frame position and
        // may run at another rate, which is why the rebase below reads both
        // at one anchor; between open and rebase the old base makes raw
        // readings negative, and the not-yet-reset monotonic floor clamps
        // that window.
        val opened = runCatching { openLine(crop.sampleRate) }
        if (opened.isFailure) {
            Debug.trace("audio line open for track switch", opened.exceptionOrNull() ?: Throwable())
            runCatching { next.close() }
            // The failed open may have dropped the old line on its way out,
            // so the sink is not necessarily playable any more. Hand that to
            // the recovery path, which reopens at the old rate against the
            // decoder still in hand.
            theClock.detachToWallTime()
            deviceLost = true
            return
        }
        runCatching { decoder.close() }
        decoder = next
        durationNanos = next.durationNanos
        // The new track has samples at the playhead, so this side is not
        // finished any more -- left set, the pump never feeds the fresh line
        // and the rebase below pins the mastered clock to a stopped device.
        isEnded = false
        if (wasAwaiting || paused) sink.stop() // open() starts the device by contract
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
        flushLine()
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
        }
        if (!wasAwaiting && !paused && !isEnded) sink.start()
        if (DEBUG_SEEK) {
            System.err.println("[audio-tempo] tempo=$newTempo anchored=${pos / 1_000_000}ms")
        }
    }

    private class Crop(
        val anchorNanos: Long,
        val remainder: ByteArray,
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
                sampleRate = chunk.sampleRate,
            )
        }
    }

    private enum class TailWait { PLAYED_OUT, INTERRUPTED, CLOSE }

    /**
     * Waits until the line has no sound left in it, while staying on the
     * command queue. The old sink.drain() deafened this thread for the
     * whole buffered tail, so a seek pressed near a loop wrap waited the
     * tail out for nothing (its first act is flushing that tail). A wall
     * deadline bounds the wait against a stalled device; past it the tail
     * is declared played.
     *
     * The question is "is there sound left", not "has the pipeline finished
     * its own shutdown": the player holds the end of a lap open until this
     * says yes, and every microsecond it overstays is a frozen picture.
     */
    private fun awaitTailPlayedOut(): TailWait {
        // The queued frames are the line's own, already stretched, and they
        // leave it at the line's rate -- tempo does not enter the wall
        // estimate the way it did when the wait was denominated in media time.
        val deadline = System.nanoTime() + queuedWallNanos() + TAIL_GRACE_NANOS
        while (framesQueued() > 0) {
            if (System.nanoTime() >= deadline) break
            val cmd = commands.poll(20, TimeUnit.MILLISECONDS) ?: continue
            return if (handle(cmd)) TailWait.INTERRUPTED else TailWait.CLOSE
        }
        return TailWait.PLAYED_OUT
    }

    /** How long the queued tail takes to leave the line, at its rate. */
    private fun queuedWallNanos(): Long {
        val frames = framesQueued()
        val rate = sampleRate.coerceAtLeast(1).toLong()
        // Quotient plus remainder, never a 64-bit product: frames * 1e9
        // overflows past a few hours of one uninterrupted anchor.
        return (frames / rate) * 1_000_000_000L + (frames % rate) * 1_000_000_000L / rate
    }

    private companion object {
        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        const val BYTES_PER_FRAME = 4

        /**
         * Slack past the queued tail's own length before a device that
         * stopped consuming is declared finished. Only a stalled line ever
         * spends it: a live one empties on its own and ends the wait there.
         */
        const val TAIL_GRACE_NANOS = 500_000_000L

        /**
         * One write outstanding this long is a dead device; the watchdog
         * detaches the clock to wall time. A live line accepts a chunk
         * within its own buffer's length -- a fifth of a second here -- so
         * the bound is an order of magnitude clear of honest slowness.
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
