package dev.hivens.skinema.libav

/** One container chapter -- a timeline marker, as the demuxer reports it. */
class Chapter(
    val startNanos: Long,
    val endNanos: Long,
    /** From the chapter's metadata; null when untagged. */
    val title: String?,
)
