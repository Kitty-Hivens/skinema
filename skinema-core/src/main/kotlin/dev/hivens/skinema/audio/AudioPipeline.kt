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

    // Write failures since the last write that finished. Owned by the audio
    // thread. See [guardedWrite]: a device that reopens cleanly and then
    // refuses every write is not an outage to wait out.
    private var writeFailures = 0

    // When the blocking write in flight started, or 0 when none is. The
    // watchdog judges a stall by how long ONE write has been outstanding,
    // which is a question it can answer without touching the device.
    //
    // It used to poll the device's frame position instead and call it stuck
    // when that stopped advancing, which is the wrong question twice over. A
    // frozen position is what a PAUSED line reports too, and the watchdog
    // cannot tell those apart from outside; and the answer comes from the
    // device this exists to rescue, so on one that has stopped answering
    // there is no answer to judge. How long one write has been outstanding is
    // a fact this side owns, and it is enough: a live line takes at most its
    // buffer's length to accept a chunk, and the stall bound is an order of
    // magnitude past that.
    @Volatile
    private var writeInFlightSince = 0L

    @Volatile
    private var watchdogStop = false

    // Volatile: started on the audio thread, interrupted from whatever thread
    // announces the close.
    @Volatile
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

    // Nobody is left to drain the queue once the thread has gone, and the
    // player keeps announcing to it: the video side calls seek() and
    // videoLanded() on every press without asking whether this side is still
    // there, so a scrubbed timeline grew two nodes per press, unbounded, for
    // as long as the file stayed open. The loop-wrap path already asked
    // (VideoPlayer takeIf { it.alive }); these are the entry points that did
    // not. Close is deliberately not guarded -- it is how the thread is told
    // to go, and a second one costs one node.
    fun pause() {
        if (!alive) return
        commands.put(Command.Pause)
    }

    fun resume() {
        if (!alive) return
        commands.put(Command.Resume)
    }

    fun seek(ptsNanos: Long) {
        if (!alive) return
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
    fun selectTrack(streamIndex: Int) {
        if (!alive) return
        commands.put(Command.SwitchTrack(streamIndex))
    }

    /** Playback rate, pitch preserved; the caller clamps to atempo's range. */
    fun setTempo(tempo: Double) {
        if (!alive) return
        commands.put(Command.SetTempo(tempo))
    }

    /** The video side finished its seek landing; sound may run again. */
    fun videoLanded(atNanos: Long = -1L) {
        if (!alive) return
        commands.put(Command.VideoLanded(atNanos))
    }

    fun setVolume(volume: Float) = sink.setVolume(volume)

    /**
     * Tells this side to go, without waiting for it.
     *
     * Announcing and waiting are separate because the player has three sides
     * to shut down and none of them depends on the others: told one at a
     * time and joined in turn, their waits summed instead of overlapping.
     *
     * This is also where the sink stops being the player's. [guardedWrite]
     * starts no write once [closing] stands, and one already inside the sink
     * is broken out of by the watchdog -- the caller [PcmSink.close] already
     * names for exactly that, so a consumer's own sink sees no new one.
     */
    fun announceClose() {
        closing = true
        commands.put(Command.Close)
        watchdog?.interrupt()
    }

    /** Waits for the thread to go, and never past [deadlineNanos]. */
    fun awaitExit(deadlineNanos: Long) {
        val ms = (deadlineNanos - System.nanoTime()) / 1_000_000
        if (ms <= 0) return
        thread.join(ms)
    }

    fun close() {
        announceClose()
        awaitExit(System.nanoTime() + DEFAULT_CLOSE_BUDGET_NANOS)
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
        guardedWrite(first.pcm, first.byteCount)

        while (true) {
            if (deviceLost && !recover()) return
            var cmd = commands.poll()
            while (cmd != null) {
                if (!handle(cmd)) return
                cmd = commands.poll()
            }
            // A command can lose the device. A track switch whose line refuses
            // the new track's rate leaves that line stopped and hands the
            // reopen to recovery -- and the check at the top of the loop ran
            // before the command was read, while the writes below never ask.
            // So the pump handed a chunk to a line it had just stopped itself:
            // a stopped line never drains, and the start() that would revive it
            // is on this very thread. On a real device that write either blocks
            // for good or throws its way out of the pump and ends this side,
            // which is the outcome the refusal path exists to avoid.
            if (deviceLost) continue
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
                    if (n > 0) guardedWrite(f.output, n)
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
            guardedWrite(pcm, byteCount)
            return
        }
        val n = filter.process(pcm, byteCount)
        if (n > 0) guardedWrite(filter.output, n)
    }

    /**
     * The blocking write, watched. [writeInFlightSince] tells [runWatchdog]
     * when this write started, so one that never returns detaches the clock;
     * off the write the device may legitimately sit still (pause, seek,
     * landing) and nothing is being waited on.
     */
    private fun guardedWrite(data: ByteArray, length: Int) {
        // The shutter. Once a close has been announced the sink belongs to
        // whoever lent it, and a write started here would be reaching into it
        // after close() had already returned.
        if (closing) return
        writeInFlightSince = System.nanoTime()
        try {
            sink.write(data, 0, length)
            queuedFrames += length / BYTES_PER_FRAME
            writeFailures = 0
        } catch (t: Throwable) {
            // A write that did not finish is a device that stopped taking
            // sound, and this pipeline has a path for exactly that. Two ways
            // in: the watchdog's rescue closes the line out from under a
            // write that will not return, and a line whose device vanished
            // reports a short write of its own accord. Neither is the end of
            // the audio side -- left to travel, the throw reached run()'s
            // handler and finished this side for good, so a device that came
            // back found a [recover] that was never going to run.
            //
            // [deviceLost] already standing means the watchdog got there
            // first and has detached the clock; otherwise this is the first
            // notice and the detach belongs here, or the picture would wait
            // on a device that stopped.
            if (deviceLost) {
                Debug.trace("audio write released by the device-loss rescue", t)
            } else {
                writeFailures++
                // Bounded, because recovery answers an OUTAGE and this may not
                // be one. A device that reopens cleanly and then refuses the
                // very next write would be retried forever, and every round
                // rebases the mastered clock onto a line that plays nothing --
                // so the picture stops at the landing while this side keeps
                // reporting itself alive. A few rounds, then the throw travels
                // as it used to and the player runs the rest on wall time,
                // which is what it documents for a device that will not come
                // back.
                if (writeFailures > MAX_WRITE_FAILURES) throw t
                Debug.trace("audio write did not finish; treating the device as lost", t)
                clock?.detachToWallTime(readDevice = false)
                deviceLost = true
            }
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
        // open() starts the device by contract, so the clock may fill the
        // gaps between its position refreshes again. Null on the very first
        // open, which happens before the clock exists.
        clock?.setDeviceRunning(true)
        anchorTail()
    }

    /**
     * Freezes the line, and says so, because the two are one act. The clock
     * fills the gaps between the device's position refreshes with wall time,
     * and a line that is not consuming has no gaps to fill -- every
     * nanosecond added past this call is sound that was never played. Every
     * freeze here outlives its own statement: a seek's lasts until the
     * picture lands, a switch's until the new decoder is cropped.
     */
    private fun freezeSink() {
        // Declared BEFORE the line stops, which is the opposite order to
        // [runSink] and deliberately so. Between the two statements the line
        // has already stopped while the clock still believes it is
        // consuming, so any reader in that window fills the gap with wall
        // time the device never played -- and the monotonic floor latches it,
        // so the playhead the switch or the rate change then reads is ahead
        // of the sound by as much as the fill's ceiling. Declaring first
        // costs the mirror case: a reader in the window under-fills a line
        // that is still draining, which the next device refresh corrects.
        clock?.setDeviceRunning(false)
        sink.stop()
    }

    /** The other half of [freezeSink]. */
    private fun runSink() {
        sink.start()
        clock?.setDeviceRunning(true)
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
     * It deliberately asks the device nothing, and the reason is that the
     * answer would be worthless rather than that the question would hang.
     * (It was written up as the latter: a line does answer position queries
     * under the same native monitor its write takes, but the write is a Java
     * polling loop that holds that monitor for one non-blocking native call
     * at a time and waits out the rest elsewhere, so a query is delayed by
     * an iteration and not by the write.) The device this exists to rescue
     * is one that has stopped answering honestly, and a frozen position
     * reads identically to a line that is merely paused. How long its own
     * write has been outstanding is a fact this side owns outright, and it
     * is enough: a live line takes at most its buffer's length to accept a
     * chunk, and the stall bound is an order of magnitude past that.
     */
    private fun runWatchdog() {
        val pollMs = (writeStallNanos / 4_000_000L).coerceIn(20L, 250L)
        while (!watchdogStop) {
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                // A wake, not the exit. An announced close wants the rescue
                // below while the write is still in the sink; the teardown
                // that does mean exit raises watchdogStop before it
                // interrupts, and the check below reads it.
            }
            if (watchdogStop) return
            val theClock = clock ?: continue
            // While recovery is in flight the audio thread is reopening the
            // line; do not fire a second time.
            if (deviceLost) continue
            // Nothing outstanding: the device may legitimately sit still.
            val since = writeInFlightSince
            if (since == 0L) continue
            // Two reasons to break a write out of the sink, and they are one
            // emergency: a device that stopped consuming, and a close
            // announced while the write sits in a sink the consumer is about
            // to take back. Waiting out the stall bound for the second would
            // put three seconds on an ordinary close.
            if (!closing && System.nanoTime() - since < writeStallNanos) continue
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
            if (paused || awaitingLanding || isEnded) freezeSink()
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
                freezeSink()
                checkNotNull(clock).pause()
                paused = true
            }
            true
        }
        Command.Resume -> {
            if (paused) {
                // Mid-landing the sink must stay frozen; VideoLanded starts it.
                if (!awaitingLanding) runSink()
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
        if (!paused && !isEnded) runSink()
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
        freezeSink()
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
        freezeSink()
        // Read the playhead between the freeze and the flush. The freeze is
        // what makes the reading safe to take; the flush is what destroys
        // the evidence, because a line that has dropped its queue can no
        // longer say how much of it was played and reports the lot. Taken
        // after it, this landed up to a whole line buffer ahead of the sound
        // the listener was on, and the new track was cropped that far in.
        val pos = theClock.mediaNanos()
        flushLine()
        pendingPcm = null

        val next = try {
            AudioDecoder.openOrNull(path, streamIndex)
        } catch (_: Throwable) {
            null
        }
        if (next == null) {
            if (!wasAwaiting && !paused && !isEnded) runSink()
            return
        }
        val crop = cropAt(next, pos)
        if (crop == null) {
            // The new track ends before the playhead; refuse rather than
            // wrap the mastered clock mid-lap or strand a non-looping
            // player at a frozen anchor.
            runCatching { next.close() }
            if (!wasAwaiting && !paused && !isEnded) runSink()
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
        if (wasAwaiting || paused) freezeSink() // open() starts the device by contract
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
        freezeSink()
        // Between the freeze and the flush, for the reason [switchTrack]
        // gives -- and here the cost was the very thing this method exists
        // to avoid: a re-anchor over the buffered tail, leaving a permanent
        // A/V offset.
        val pos = theClock.mediaNanos()
        flushLine()

        // Open-new-before-close-old: a stretcher that cannot build leaves
        // tempo, clock and stream untouched.
        val next = if (newTempo == 1.0) {
            null
        } else {
            try {
                TempoFilter(sampleRate, newTempo)
            } catch (_: Throwable) {
                if (!wasAwaiting && !paused && !isEnded) runSink()
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
        if (!wasAwaiting && !paused && !isEnded) runSink()
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
         * Rounds of reopen-and-fail before this side gives up on the device
         * and lets the player fall back to wall-clock playback. Three, because
         * one is a hiccup a reopen fixes and a fourth would be the same answer
         * as the third.
         */
        const val MAX_WRITE_FAILURES = 3

        /**
         * What [close] spends when this side is closed on its own. The player
         * passes its own deadline instead, so the whole teardown shares one
         * budget rather than taking this per side.
         */
        const val DEFAULT_CLOSE_BUDGET_NANOS = 5_000_000_000L

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
