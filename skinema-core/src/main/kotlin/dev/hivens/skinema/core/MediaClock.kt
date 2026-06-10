package dev.hivens.skinema.core

/**
 * The clock video pacing follows. Silent playback uses [PlaybackClock]
 * (wall time); once audio lands, the audio sink provides this instead --
 * a DAC consumes samples at its own immutable rate, so when sound is
 * present it IS the clock and video frames sync to it, never the other
 * way around. Keeping the seam here means adding audio inverts nothing:
 * VideoPlayer already paces against whatever [mediaNanos] says.
 */
interface MediaClock {

    val isPaused: Boolean

    /** Starts (or restarts) running from [atMediaNanos]. */
    fun start(atMediaNanos: Long = 0L)

    /** Freezes media time in place. No-op when already paused. */
    fun pause()

    /** Resumes from the frozen media time. No-op when running. */
    fun resume()

    /** Jumps media time to [mediaNanos]; keeps the paused/running state. */
    fun seek(mediaNanos: Long)

    /** Current media position in nanoseconds. */
    fun mediaNanos(): Long

    /** Nanos until [ptsNanos] is due; zero or negative = already due. */
    fun nanosUntilDue(ptsNanos: Long): Long = ptsNanos - mediaNanos()
}
