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
) {
    internal companion object {
        /**
         * The id of the synthetic closed-caption track, reserved rather than
         * allocated: container tracks take their stream index and external
         * files count down from -1, so the far end of the range is the one
         * place neither can reach.
         *
         * It lives here rather than in the player because it is what
         * DISCRIMINATES a caption session, and keying that on the codec name
         * was wrong: `eia_608` is a real codec a container can carry as a real
         * stream, and FFmpeg marks it AV_CODEC_PROP_TEXT_SUB, so such a track
         * enumerates like any other text track. Read by name it took the
         * frame-fed path, was never demuxed and never fed, and rendered
         * nothing while reporting itself live.
         */
        const val CLOSED_CAPTION_ID = Int.MIN_VALUE
    }
}
