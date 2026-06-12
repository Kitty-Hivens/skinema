package dev.hivens.skinema.libav

/**
 * One audio stream of a container, as the demuxer describes it.
 * [streamIndex] is the selection key: pass it to open a specific track
 * or to the player's track switching.
 */
class AudioTrack(
    val streamIndex: Int,
    /** ISO 639 tag from the stream metadata; null when untagged. */
    val language: String?,
    /** Stream title from the metadata; null when untagged. */
    val title: String?,
    val channels: Int,
    val sampleRate: Int,
    /** The container's default-track disposition. */
    val isDefault: Boolean,
)
