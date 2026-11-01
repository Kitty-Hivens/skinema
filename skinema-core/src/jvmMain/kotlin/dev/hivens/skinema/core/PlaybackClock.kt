package dev.hivens.skinema.core

/**
 * Wall-time [MediaClock] for silent playback. FFmpeg owns no clock
 * (ROADMAP.md section 3): frames carry pts, and this is the other half --
 * "what media time is it now". [setRate] scales how fast it runs.
 *
 * Thread-safe like [AudioClock]: the decode thread mutates while the
 * pacer and the subtitle pipeline read concurrently. [now] is injectable
 * so pacing logic tests run on a fake clock.
 */
class PlaybackClock(private val now: () -> Long = System::nanoTime) : MediaClock {

    private val lock = Any()
    private var anchorWall = 0L
    private var anchorMedia = 0L

    // Playback rate: wall deltas scale by this. Mutated only through
    // [setRate], which re-anchors first -- the factor applies forward.
    private var rate = 1.0
    private var paused = true

    override val isPaused: Boolean
        get() = synchronized(lock) { paused }

    override fun start(atMediaNanos: Long) {
        synchronized(lock) {
            anchorMedia = atMediaNanos
            anchorWall = now()
            paused = false
        }
    }

    override fun pause() {
        synchronized(lock) {
            if (!paused) {
                anchorMedia = rawMediaNanos()
                paused = true
            }
        }
    }

    override fun resume() {
        synchronized(lock) {
            if (paused) {
                anchorWall = now()
                paused = false
            }
        }
    }

    override fun seek(mediaNanos: Long) {
        synchronized(lock) {
            anchorMedia = mediaNanos
            anchorWall = now()
        }
    }

    /** Speed change; re-anchors so the factor applies only forward. */
    fun setRate(rate: Double) {
        synchronized(lock) {
            anchorMedia = rawMediaNanos()
            anchorWall = now()
            this.rate = rate
        }
    }

    override fun mediaNanos(): Long = synchronized(lock) { rawMediaNanos() }

    private fun rawMediaNanos(): Long =
        if (paused) anchorMedia else anchorMedia + ((now() - anchorWall) * rate).toLong()
}
