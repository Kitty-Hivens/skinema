package dev.hivens.skinema.libav

/**
 * Struct offsets and ABI constants for the pinned FFmpeg major line (n8.1,
 * ROADMAP.md section 4), captured by tools/layout-oracle.c compiled against
 * that line's headers. Do not edit by hand -- re-run the oracle on a major
 * bump and transcribe its output.
 *
 * Offsets are stable within a soname major; [Libav] verifies the loaded
 * majors before anything here is dereferenced.
 */
object LibavAbi {

    object FormatContext {
        const val NB_STREAMS = 44L
        const val STREAMS = 48L
        const val SIZEOF = 480L
    }

    object Stream {
        const val CODECPAR = 16L
        const val TIME_BASE = 32L
        const val SIZEOF = 216L
    }

    object Packet {
        const val STREAM_INDEX = 36L
        const val SIZEOF = 104L
    }

    object CodecParameters {
        const val CODEC_ID = 4L
        const val SIZEOF = 184L
    }

    object Frame {
        const val DATA = 0L
        const val LINESIZE = 64L
        const val WIDTH = 104L
        const val HEIGHT = 108L
        const val FORMAT = 116L
        const val PTS = 136L
        const val BEST_EFFORT_TIMESTAMP = 304L
        const val SIZEOF = 424L
    }

    const val AVMEDIA_TYPE_VIDEO = 0
    const val AV_PIX_FMT_RGBA = 26
    const val SWS_BILINEAR = 2
    const val AVSEEK_FLAG_BACKWARD = 1
    const val AV_CODEC_ID_VP8 = 139
    const val AV_CODEC_ID_VP9 = 167
    const val AV_LOG_QUIET = -8
    const val AVERROR_EOF = -541478725
    const val AVERROR_INVALIDDATA = -1094995529
    const val AV_NOPTS_VALUE = Long.MIN_VALUE

    /**
     * AVERROR(EAGAIN) is negative errno, and errno values differ per OS:
     * EAGAIN is 11 on Linux and Windows but 35 on macOS. Comparing against
     * the wrong value makes the receive loop spin forever (ROADMAP trap 1).
     */
    val AVERROR_EAGAIN: Int = when (Os.current()) {
        Os.MAC -> -35
        else -> -11
    }
}
