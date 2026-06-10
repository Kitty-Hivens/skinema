package dev.hivens.skinema.core

/**
 * Wall-time [MediaClock] for silent playback. FFmpeg owns no clock
 * (ROADMAP.md section 3): frames carry pts, and this is the other half --
 * "what media time is it now". Speed is fixed at 1x until a consumer
 * needs otherwise.
 *
 * Not thread-safe on its own; the player serializes access on its decode
 * thread. [now] is injectable so pacing logic tests run on a fake clock.
 */
class PlaybackClock(private val now: () -> Long = System::nanoTime) : MediaClock {

    private var anchorWall = 0L
    private var anchorMedia = 0L
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

    override fun mediaNanos(): Long =
        if (isPaused) anchorMedia else anchorMedia + (now() - anchorWall)
}
