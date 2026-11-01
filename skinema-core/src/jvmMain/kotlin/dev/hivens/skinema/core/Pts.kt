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

/**
 * Inverse of [ptsToNanos]: nanoseconds to whole time-base units, rounded
 * half-up, for feeding av_seek_frame. `nanos * den` overflows Long for
 * large positions, and splitting the computation re-introduces sub-unit
 * truncation (a 1001/30000 base puts whole seconds off the unit grid), so
 * this goes through 128-bit math -- it runs per seek, not per frame.
 */
fun nanosToPts(nanos: Long, timeBaseNum: Int, timeBaseDen: Int): Long {
    require(nanos >= 0) { "seek positions are non-negative, got $nanos" }
    require(timeBaseNum > 0) { "time base numerator must be positive, got $timeBaseNum" }
    require(timeBaseDen > 0) { "time base denominator must be positive, got $timeBaseDen" }
    val unitNanos = timeBaseNum * NANOS_PER_SECOND
    return java.math.BigInteger.valueOf(nanos)
        .multiply(java.math.BigInteger.valueOf(timeBaseDen.toLong()))
        .add(java.math.BigInteger.valueOf(unitNanos / 2))
        .divide(java.math.BigInteger.valueOf(unitNanos))
        .toLong()
}
