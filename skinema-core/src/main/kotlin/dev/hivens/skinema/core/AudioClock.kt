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
    private val sampleRate: Int,
    private val positionFrames: () -> Long,
) : MediaClock {

    private val lock = Any()
    private var baseMediaNanos = 0L
    private var baseFrames = 0L

    @Volatile
    override var isPaused = true
        private set

    override fun start(atMediaNanos: Long) {
        synchronized(lock) {
            baseMediaNanos = atMediaNanos
            baseFrames = positionFrames()
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
        }
    }

    override fun mediaNanos(): Long = synchronized(lock) {
        if (detachedAtWall >= 0) {
            detachedMedia + (System.nanoTime() - detachedAtWall)
        } else {
            baseMediaNanos + (positionFrames() - baseFrames) * 1_000_000_000L / sampleRate
        }
    }

    private var detachedAtWall = -1L
    private var detachedMedia = 0L

    /**
     * Failure hatch: when the audio pipeline dies mid-stream, its frozen
     * frame position must not freeze video with it. Media time continues
     * on the wall clock from the current position; pause stops being
     * honoured -- acceptable for a failure mode that also lost the sound.
     */
    fun detachToWallTime() {
        synchronized(lock) {
            detachedMedia = baseMediaNanos + (positionFrames() - baseFrames) * 1_000_000_000L / sampleRate
            detachedAtWall = System.nanoTime()
        }
    }
}
