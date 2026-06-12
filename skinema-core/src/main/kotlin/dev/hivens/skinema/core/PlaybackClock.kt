package dev.hivens.skinema.core

/**
 * Wall-time [MediaClock] for silent playback. FFmpeg owns no clock
 * (ROADMAP.md section 3): frames carry pts, and this is the other half --
 * "what media time is it now". [setRate] scales how fast it runs.
 *
 * Not thread-safe on its own; the player serializes access on its decode
 * thread. [now] is injectable so pacing logic tests run on a fake clock.
 */
class PlaybackClock(private val now: () -> Long = System::nanoTime) : MediaClock {

    private var anchorWall = 0L
    private var anchorMedia = 0L

    // Playback rate: wall deltas scale by this. Mutated only through
    // [setRate], which re-anchors first -- the factor applies forward.
    private var rate = 1.0
    override var isPaused = true
        private set

    override fun start(atMediaNanos: Long) {
        anchorMedia = atMediaNanos
        anchorWall = now()
        isPaused = false
    }

    override fun pause() {
        if (!isPaused) {
            anchorMedia = mediaNanos()
            isPaused = true
        }
    }

    override fun resume() {
        if (isPaused) {
            anchorWall = now()
            isPaused = false
        }
    }

    override fun seek(mediaNanos: Long) {
        anchorMedia = mediaNanos
        anchorWall = now()
    }

    /** Speed change; re-anchors so the factor applies only forward. */
    fun setRate(rate: Double) {
        anchorMedia = mediaNanos()
        anchorWall = now()
        this.rate = rate
    }

    override fun mediaNanos(): Long =
        if (isPaused) anchorMedia else anchorMedia + ((now() - anchorWall) * rate).toLong()
}
