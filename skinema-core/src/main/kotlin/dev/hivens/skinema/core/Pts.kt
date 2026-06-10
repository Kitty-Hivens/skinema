package dev.hivens.skinema.core

private const val NANOS_PER_SECOND = 1_000_000_000L

/**
 * Converts a presentation timestamp counted in [timeBaseNum]/[timeBaseDen]
 * second units (an AVRational time base) to nanoseconds.
 *
 * Split into whole seconds + remainder so the intermediate products stay
 * inside Long: `rem < den <= Int.MAX_VALUE`, so `rem * 1e9` cannot
 * overflow, and whole seconds only overflow past ~292 years of playback.
 */
fun ptsToNanos(pts: Long, timeBaseNum: Int, timeBaseDen: Int): Long {
    require(timeBaseDen > 0) { "time base denominator must be positive, got $timeBaseDen" }
    val units = Math.multiplyExact(pts, timeBaseNum.toLong())
    val seconds = units / timeBaseDen
    val remainder = units % timeBaseDen
    return Math.addExact(
        Math.multiplyExact(seconds, NANOS_PER_SECOND),
        remainder * NANOS_PER_SECOND / timeBaseDen,
    )
}
