package dev.hivens.skinema.libav

import java.nio.file.Path

/**
 * One subtitle stream of a container or an external file. [id] is the
 * selection handle: embedded tracks use their [streamIndex], external
 * files get negative ids assigned by the player as they are added.
 */
class SubtitleTrack internal constructor(
    val id: Int,
    /** Stream index within its own container/file. */
    val streamIndex: Int,
    val language: String?,
    val title: String?,
    /** Codec name as libav reports it: "ass", "subrip", "mov_text", ... */
    val codecName: String,
    /**
     * Text tracks render through libass and need it loadable; bitmap
     * tracks (pgs, dvdsub) decode to pixels and do not.
     */
    val isText: Boolean,
    val isDefault: Boolean,
    /** Forced tracks carry only translations of on-screen text. */
    val isForced: Boolean,
    /** Null for embedded tracks; the source file for external ones. */
    val externalPath: Path? = null,
)
