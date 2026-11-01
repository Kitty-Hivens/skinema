package dev.hivens.skinema.core

/**
 * [MediaClock] driven by an audio sink's frame position: media time is how
 * many samples the device says it has played, filled in with wall time
 * between the moments it says anything new. The DAC's pace is the only
 * truth, so when sound is present video follows it, never the reverse
 * (ROADMAP.md section 3).
 *
 * The filling-in is not a detail. [positionFrames] refreshes once per
 * device period and is a constant in between, and the backend behind it
 * computes an estimate rather than reading hardware; the anchors are the
 * device's and every one of them corrects the fill, but read raw the
 * result is a staircase that paces video in bursts. See
 * [interpolationLocked], and "What the device actually reports" in
 * docs/internal/threading-and-clocks.md.
 *
 * Freezing therefore takes two things, not one: the pipeline stops the
 * line AND says so through [setDeviceRunning], because a stopped line's
 * position stands still and the fill must not walk on without it. Pause is
 * the flag on top of that. Detached from the device -- once the track has
 * ended, or it has died -- the wall clock is the source and only [pause]
 * can stop it, so it does. An underrun reads as a device that stopped
 * answering: time runs on for at most one period and then holds, video
 * holds with it, and sync survives. Thread-safe: the audio thread
 * re-anchors on seek while the video thread reads.
 */
class AudioClock(
    initialSampleRate: Int,
    private val positionFrames: () -> Long,
) : MediaClock {

    // Mutable for track switches; only [rebase] writes it, under the lock
    // and together with a fresh anchor -- a rate change anywhere else
    // would rescale history.
    private var sampleRate = initialSampleRate

    // Playback rate: each consumed device frame advances media time by
    // tempo / sampleRate seconds. Only [setTempo] writes it, re-anchored
    // under the lock for the same rescaled-history reason.
    private var tempo = 1.0

    private val lock = Any()
    private var baseMediaNanos = 0L
    private var baseFrames = 0L

    // The device's position report is only trusted forward: around a
    // flush/restart some backends reconcile their frame counter
    // non-monotonically, and a transient backward step would walk media
    // time below frames already shown -- video treats them as "not due"
    // and stalls. A re-anchor (start/seek) legitimately moves time
    // backward and resets the floor.
    private var floorNanos = Long.MIN_VALUE

    @Volatile
    override var isPaused = true
        private set

    // True while media time comes from the wall rather than the device.
    // Read without [lock] to decide whether the device may be consulted at
    // all; only written under it, by [detachToWallTime] and [rebase].
    @Volatile
    private var deviceDetached = false

    /**
     * Bumped by every re-anchor. A device reading is taken OUTSIDE [lock] --
     * it has to be, the line answers under a native monitor its blocking
     * write also takes -- and is then applied under the lock against an
     * anchor that may have moved in between. It usually has not; when it has,
     * the reading belongs to a line that no longer exists.
     *
     * A track switch and a device-loss recovery both reopen the line, so its
     * counter restarts at zero while the reading in flight still carries the
     * old one's total. Applied against the fresh anchor that is a leap of the
     * whole elapsed playing time -- and [mediaNanos] writes what it returns
     * into [floorNanos], so the leap is not transient but permanent: every
     * honest reading afterwards is below the floor and the clock never comes
     * back. Reading this before the sample and re-checking it under the lock
     * is what tells the two apart.
     */
    @Volatile
    private var anchorGeneration = 0L

    /**
     * The device's position, or null when the clock is not driven by it.
     *
     * Never called under [lock], and never at all once detached.
     *
     * The first half is about cost, and the cost is smaller than it was
     * written up as. [positionFrames] goes into the audio backend, and a
     * JavaSound line does answer under the same native monitor its write
     * takes -- but the write is a Java polling loop (DirectAudioDevice.write:
     * `while (!flushing) { synchronized (lockNative) { nWrite(...) } ... }`),
     * so it holds that monitor for one non-blocking native write at a time
     * and waits out the rest on a different monitor entirely. A reader is
     * delayed by one iteration, not by the write: measured here at 3 to 31 ms
     * against writes that blocked for 154 ms and for two seconds alike. That
     * is inside the gap-fill this clock already allows. Taking the reading
     * outside [lock] is still right -- there is no reason to serialise every
     * reader behind the slowest one -- but it is a courtesy, not a rescue.
     *
     * The second half is the rescue. Once the clock is detached the device is
     * not asked at all, so a line whose native call is genuinely wedged --
     * which nothing in Java can break -- takes down only the readers already
     * inside it, and every later one lives on wall time. A player whose
     * device died degrades to silence instead of wedging whole, which is the
     * one thing this clock's failure hatch exists to prevent.
     */
    private fun sampleDevice(): Long? = if (deviceDetached) null else positionFrames()

    // The device answers in periods, not continuously. Its counter is exact
    // the instant it refreshes and then stands still for a whole period while
    // the DAC plays on -- measured here at 21.3 ms of stillness followed by a
    // 21.3 ms jump, and the backend's own position is an arithmetic estimate
    // rather than a hardware register (openjdk estimatePositionFromAvail), so
    // there is no finer answer to ask for. Read raw, media time is a
    // staircase: exact at each refresh and up to a period behind by the end
    // of it. Video paced on that gets its frames due in bursts -- 60 fps
    // content measured 48.4 distinct frames a second reaching the consumer,
    // the rest overwritten in the mailbox before anything could take them.
    //
    // So the wall clock fills the gaps BETWEEN refreshes, which is what
    // ffplay does (it never asks the device at all and interpolates from the
    // last callback). The bound is the device's own last step: past one
    // period of silence the reading is no longer evidence that anything is
    // playing, and holding there is the right answer for an underrun, a
    // stopped line and a dead device alike. Nothing here invents accuracy --
    // the anchors are still the device's, and every one of them corrects
    // whatever the interpolation guessed.
    private var lastFrames = Long.MIN_VALUE
    private var lastFramesWall = 0L
    private var lastStepFrames = 0L

    // Whether the line is consuming. Filling the gaps between refreshes is
    // only honest while it is: a stopped line plays nothing, so every
    // nanosecond added past the stop is invented. It matters because the
    // pipeline freezes the line for whole seconds at a time -- a seek holds
    // it until the picture lands, a track switch until the new decoder is
    // cropped -- and reads the playhead in that window to re-anchor on. Time
    // that crept forward there was time the re-anchor then took back, which
    // is the one move the video side's invariants forbid.
    private var deviceRunning = true

    /**
     * How far past its last answer the device has certainly gone, in media
     * nanos. Zero on the refresh itself and whenever no step has been
     * observed yet to size the bound with.
     */
    private fun interpolationLocked(frames: Long, wallNow: Long): Long {
        if (frames != lastFrames) {
            // Forward only, and the cadence anchor moves only forward with
            // it. The reading was sampled outside the lock by one of five
            // threads and taking the lock does not preserve the order they
            // sampled in, so an older one can land after a newer -- carrying
            // a fresh wall time, since that is read after the sample returns,
            // which is why the clock rather than the count is no guide here.
            // Accepted, it walks the anchor BACK and the next honest reading
            // measures its step from there: several periods instead of one,
            // and that step is the bound on how long the gap fill may run
            // without the device. Measured at 55 ms of invented time where
            // one period allows 21. A backend reconciling non-monotonically
            // around a flush lands in the same branch and wants the same
            // answer.
            if (lastFrames != Long.MIN_VALUE) {
                if (frames < lastFrames) return 0L
                lastStepFrames = frames - lastFrames
            }
            lastFrames = frames
            lastFramesWall = wallNow
            return 0L
        }
        if (!deviceRunning || lastStepFrames <= 0L) return 0L
        val cap = minOf(framesToNanos((lastStepFrames * tempo).toLong()), MAX_INTERPOLATION_NANOS)
        val elapsed = ((wallNow - lastFramesWall) * tempo).toLong()
        return elapsed.coerceIn(0L, cap)
    }

    /** Forgets the device's cadence; every re-anchor starts a fresh one. */
    private fun forgetCadence() {
        lastFrames = Long.MIN_VALUE
        lastStepFrames = 0L
    }

    /**
     * Says whether the line is consuming right now. The pipeline stops and
     * starts it for seeks, track switches, rate changes and pauses, and only
     * the pipeline knows which of those is in force -- [isPaused] covers the
     * player's pause and none of the rest.
     */
    fun setDeviceRunning(running: Boolean) {
        synchronized(lock) {
            // Restarting begins a fresh interval: the stop is not evidence
            // that the device played through it.
            if (running && !deviceRunning) lastFramesWall = System.nanoTime()
            deviceRunning = running
        }
    }

    override fun start(atMediaNanos: Long) {
        // Through the guard, like every other entry point. Asked raw, this
        // was the one way back into a device the clock had already detached
        // from -- and it is reachable: the player starts the clock when it
        // owns it, and it owns it once the audio side has gone, which is
        // exactly what a device that stopped answering causes. A start that
        // walked into a wedged native call took the decode thread with it and
        // left close() joining a thread that was never coming back, which is
        // the whole-player wedge the detach exists to prevent.
        val frames = sampleDevice()
        synchronized(lock) {
            anchorGeneration++
            baseMediaNanos = atMediaNanos
            // Detached, there is no device to base on and the wall drives;
            // keep the old base rather than pretend to a reading.
            baseFrames = frames ?: baseFrames
            floorNanos = Long.MIN_VALUE
            forgetCadence()
            pausedAt = atMediaNanos
            reanchorDetached(atMediaNanos, running = true)
            isPaused = false
        }
    }

    /**
     * Freezes media time where it stands, which is what the interface
     * promises, rather than trusting the device to stop.
     *
     * The flag alone used to be enough on the reasoning that pausing stops the
     * line and its frame position with it. That holds when the pipeline is the
     * one pausing. It does not when the timeline is stopped from the outside
     * -- at the end of playback, say -- while the line is still draining what
     * it was given: media time walked on with the device for the length of
     * that buffer, landing a fifth of a second past the end of the file. Nor
     * does it hold detached from the device, where the wall is the source and
     * nothing else can stop it.
     */
    override fun pause() {
        val generation = anchorGeneration
        val frames = sampleDevice()
        val wall = System.nanoTime()
        synchronized(lock) {
            if (!isPaused) {
                pausedAt = currentLocked(frames.takeIf { anchorGeneration == generation }, wall)
                isPaused = true
            }
        }
    }

    override fun resume() {
        val generation = anchorGeneration
        val frames = sampleDevice()
        synchronized(lock) {
            if (!isPaused) return
            // Carry on from where the pause froze it, whichever source is
            // driving: the device gets a fresh anchor, the wall a fresh start.
            val usable = frames.takeIf { anchorGeneration == generation }
            anchorGeneration++
            baseMediaNanos = pausedAt
            baseFrames = usable ?: baseFrames
            // The line stood still through the pause, so the cadence measured
            // before it says nothing about now; carried over, the first
            // reading after a resume would credit the whole pause as one
            // period of playing.
            forgetCadence()
            if (detachedPaused || detachedAtWall >= 0) {
                detachedMedia = pausedAt
                detachedAtWall = System.nanoTime()
                detachedPaused = false
            }
            isPaused = false
        }
    }

    private var pausedAt = 0L

    /** Re-anchor after the sink was flushed; the audio thread owns this. */
    override fun seek(mediaNanos: Long) {
        val generation = anchorGeneration
        val frames = sampleDevice()
        synchronized(lock) {
            val usable = frames.takeIf { anchorGeneration == generation }
            anchorGeneration++
            baseMediaNanos = mediaNanos
            baseFrames = usable ?: baseFrames
            floorNanos = Long.MIN_VALUE
            forgetCadence()
            reanchorDetached(mediaNanos, running = !isPaused)
            // A seek moves the position whether or not time is running.
            pausedAt = mediaNanos
        }
    }

    /**
     * Re-anchor onto a fresh device line: media time continues from
     * [mediaNanos] with future deltas scaled by [sampleRate]. A track
     * switch reopens the sink (position restarts at zero) and may change
     * the rate; both are only safe at an anchor, and this is that anchor.
     * It is also the re-attach after a device-loss detach (AudioPipeline
     * recovery): it ends the wall-time fallback so media time tracks the
     * device again. No-op on the detach state for the track-switch caller,
     * which is never detached.
     */
    fun rebase(mediaNanos: Long, sampleRate: Int) {
        // Unconditional: this IS the re-attach, and the line it re-attaches
        // to is a fresh one that answers.
        val frames = positionFrames()
        synchronized(lock) {
            anchorGeneration++
            baseMediaNanos = mediaNanos
            baseFrames = frames
            this.sampleRate = sampleRate
            floorNanos = Long.MIN_VALUE
            forgetCadence()
            pausedAt = mediaNanos
            detachedAtWall = -1L
            detachedMedia = 0L
            detachedPaused = false
            deviceDetached = false
        }
    }

    /**
     * Playback-rate change: re-anchors at the current position so the new
     * scale applies only forward -- swapping the factor against the old
     * anchor would rescale everything since it. Time does not move here
     * (the floor stays); the pipeline freezes and re-crops the stream
     * around this call.
     */
    fun setTempo(tempo: Double) {
        val generation = anchorGeneration
        val frames = sampleDevice()
        synchronized(lock) {
            @Suppress("NAME_SHADOWING")
            val frames = frames.takeIf { anchorGeneration == generation }
            if (detachedAtWall >= 0) {
                val wall = System.nanoTime()
                detachedMedia += ((wall - detachedAtWall) * this.tempo).toLong()
                detachedAtWall = wall
            } else if (frames != null) {
                baseMediaNanos += framesToNanos(((frames - baseFrames) * this.tempo).toLong())
                baseFrames = frames
            }
            this.tempo = tempo
        }
    }

    /**
     * What the clock reads right now, whatever it is currently driven by.
     * [frames] is the device's position, sampled by the caller before it
     * took [lock]; the detached branches ignore it.
     */
    private fun currentLocked(frames: Long?, wallNow: Long): Long = when {
        isPaused -> pausedAt
        detachedPaused -> detachedMedia
        detachedAtWall >= 0 -> detachedMedia + ((wallNow - detachedAtWall) * tempo).toLong()
        frames != null -> baseMediaNanos +
            framesToNanos(((frames - baseFrames) * tempo).toLong()) +
            interpolationLocked(frames, wallNow)
        // The device re-attached between the caller's decision not to sample
        // it and the lock. Hold where we were; the next reading takes it
        // forward off a fresh anchor.
        else -> maxOf(floorNanos, baseMediaNanos)
    }

    override fun mediaNanos(): Long {
        val generation = anchorGeneration
        val frames = sampleDevice()
        val wall = System.nanoTime()
        return synchronized(lock) {
            // A reading from before a re-anchor describes a line that is gone.
            val raw = currentLocked(frames.takeIf { anchorGeneration == generation }, wall)
            if (raw < floorNanos) {
                floorNanos
            } else {
                floorNanos = raw
                raw
            }
        }
    }

    private var detachedAtWall = -1L
    private var detachedMedia = 0L
    private var detachedPaused = false

    /**
     * Moves the wall-clock timeline to [mediaNanos] when we are running on it,
     * so a start or a seek is honoured while detached instead of being
     * shadowed by the frozen or accumulating value. A no-op on the attached
     * path, where the frame anchor the caller just set is the answer.
     */
    private fun reanchorDetached(mediaNanos: Long, running: Boolean) {
        if (detachedAtWall < 0 && !detachedPaused) return
        detachedMedia = mediaNanos
        detachedAtWall = if (running) System.nanoTime() else -1L
        detachedPaused = !running
    }

    // Tempo-scaled frame delta -> nanos without the scaledFrames * 1e9 overflow
    // that would bite past ~53 h at 48 kHz on one continuous anchor (quotient
    // plus remainder, never a 64-bit product). Seek and loop re-anchor sooner,
    // so only an unbroken non-looping play of that length ever reached it.
    private fun framesToNanos(scaledFrames: Long): Long =
        (scaledFrames / sampleRate) * 1_000_000_000L + (scaledFrames % sampleRate) * 1_000_000_000L / sampleRate

    /**
     * Failure hatch: when the audio pipeline dies mid-stream, its frozen
     * frame position must not freeze video with it. Media time continues
     * on the wall clock from the current position; pause stops being
     * honoured -- acceptable for a failure mode that also lost the sound.
     */
    fun detachToWallTime(readDevice: Boolean = true) {
        // The two callers want opposite things. The audio thread reaches here
        // at the orderly end of a track or a seek past the last sample, with
        // a line that still answers, and needs the exact position. The
        // watchdog reaches here because the line has STOPPED answering, and
        // asking it would park this call behind the very write it came to
        // free -- taking the pacer, the decode thread and the consumer's
        // render loop with it, since they all read this clock. It settles for
        // the last reading the clock returned, which is where a device that
        // stopped advancing left it anyway.
        val generation = anchorGeneration
        val frames = if (readDevice) sampleDevice() else null
        val wall = System.nanoTime()
        synchronized(lock) {
            @Suppress("NAME_SHADOWING")
            val frames = frames.takeIf { anchorGeneration == generation }
            // Where the clock reads now, not where the device says: a seek
            // may have placed it deliberately -- the end of a file, say --
            // and recomputing from a position the device is still walking
            // discarded that and drifted off it.
            detachedMedia = maxOf(currentLocked(frames, wall), floorNanos)
            deviceDetached = true
            // Detaching changes where time comes from, not whether it is
            // running. Starting it unconditionally resurrected a clock that
            // was deliberately stopped -- a paused player whose device then
            // died walked on, and a finished one placed on its duration
            // walked past it.
            if (isPaused) {
                detachedAtWall = -1L
                detachedPaused = true
            } else {
                detachedAtWall = System.nanoTime()
                detachedPaused = false
            }
        }
    }

    internal companion object {
        /**
         * Ceiling on filling the gap between two of the device's position
         * refreshes, whatever its last step suggests.
         *
         * The step is the evidence and this is the sanity bound on it: a gap
         * longer than any audio device's period is not a gap, it is a device
         * that has stopped saying anything, and inventing time over that is
         * how a stalled line would drift instead of freezing. The widest
         * period in practice is one maximum ALSA/PipeWire quantum -- 2048
         * frames, 42.7 ms at 48 kHz and 46.4 ms at 44.1 kHz -- and this
         * clears it. Anything that answers more slowly keeps the residual
         * staircase past this much, which is the safe way to be wrong.
         */
        internal const val MAX_INTERPOLATION_NANOS = 60_000_000L
    }
}
