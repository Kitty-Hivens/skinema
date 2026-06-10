package dev.hivens.skinema.core

/**
 * Maps a monotonic wall clock to media time. FFmpeg owns no clock
 * (ROADMAP.md section 3): frames carry pts, and this is the other half --
 * "what media time is it now". Pausable and seekable; speed is fixed at
 * 1x until a consumer needs otherwise.
 *
 * Not thread-safe on its own; the player serializes access on its decode
 * thread. [now] is injectable so pacing logic tests run on a fake clock.
 */
class PlaybackClock(private val now: () -> Long = System::nanoTime) {

    private var anchorWall = 0L
    private var anchorMedia = 0L
    var isPaused = true
        private set

    /** Starts (or restarts) running from [atMediaNanos]. */
    fun start(atMediaNanos: Long = 0L) {
        anchorMedia = atMediaNanos
        anchorWall = now()
        isPaused = false
    }

    /** Freezes media time in place. No-op when already paused. */
    fun pause() {
        if (!isPaused) {
            anchorMedia = mediaNanos()
            isPaused = true
        }
    }

    /** Resumes from the frozen media time. No-op when running. */
    fun resume() {
        if (isPaused) {
            anchorWall = now()
            isPaused = false
        }
    }

    /** Jumps media time to [mediaNanos]; keeps the paused/running state. */
    fun seek(mediaNanos: Long) {
        anchorMedia = mediaNanos
        anchorWall = now()
    }

    /** Current media position in nanoseconds. */
    fun mediaNanos(): Long =
        if (isPaused) anchorMedia else anchorMedia + (now() - anchorWall)

    /** Wall nanos until [ptsNanos] is due; zero or negative = already due. */
    fun nanosUntilDue(ptsNanos: Long): Long = ptsNanos - mediaNanos()
}
