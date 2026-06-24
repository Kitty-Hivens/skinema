package dev.hivens.skinema.core

/**
 * [MediaClock] driven by an audio sink's frame position: media time is
 * literally "how many samples the DAC has consumed". The DAC's pace is
 * the only truth, so when sound is present video follows it, never the
 * reverse (ROADMAP.md section 3).
 *
 * Pause and resume only track the flag: stopping the device freezes
 * [positionFrames], which freezes media time by construction. Underruns
 * behave the same way -- the position stalls, video stalls with it, and
 * sync survives. Thread-safe: the audio thread re-anchors on seek while
 * the video thread reads.
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

    override fun start(atMediaNanos: Long) {
        synchronized(lock) {
            baseMediaNanos = atMediaNanos
            baseFrames = positionFrames()
            floorNanos = Long.MIN_VALUE
            isPaused = false
        }
    }

    override fun pause() {
        isPaused = true
    }

    override fun resume() {
        isPaused = false
    }

    /** Re-anchor after the sink was flushed; the audio thread owns this. */
    override fun seek(mediaNanos: Long) {
        synchronized(lock) {
            baseMediaNanos = mediaNanos
            baseFrames = positionFrames()
            floorNanos = Long.MIN_VALUE
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
        synchronized(lock) {
            baseMediaNanos = mediaNanos
            baseFrames = positionFrames()
            this.sampleRate = sampleRate
            floorNanos = Long.MIN_VALUE
            detachedAtWall = -1L
            detachedMedia = 0L
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
        synchronized(lock) {
            if (detachedAtWall >= 0) {
                val wall = System.nanoTime()
                detachedMedia += ((wall - detachedAtWall) * this.tempo).toLong()
                detachedAtWall = wall
            } else {
                val frames = positionFrames()
                baseMediaNanos += framesToNanos(((frames - baseFrames) * this.tempo).toLong())
                baseFrames = frames
            }
            this.tempo = tempo
        }
    }

    override fun mediaNanos(): Long = synchronized(lock) {
        val raw = if (detachedAtWall >= 0) {
            detachedMedia + ((System.nanoTime() - detachedAtWall) * tempo).toLong()
        } else {
            baseMediaNanos + framesToNanos(((positionFrames() - baseFrames) * tempo).toLong())
        }
        if (raw < floorNanos) {
            floorNanos
        } else {
            floorNanos = raw
            raw
        }
    }

    private var detachedAtWall = -1L
    private var detachedMedia = 0L

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
    fun detachToWallTime() {
        synchronized(lock) {
            val raw = baseMediaNanos + framesToNanos(((positionFrames() - baseFrames) * tempo).toLong())
            detachedMedia = maxOf(raw, floorNanos)
            detachedAtWall = System.nanoTime()
        }
    }
}
