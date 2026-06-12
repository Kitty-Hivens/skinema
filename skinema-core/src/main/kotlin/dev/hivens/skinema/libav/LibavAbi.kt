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
        const val NB_CHAPTERS = 72L
        const val CHAPTERS = 80L

        /** int64 in AV_TIME_BASE units (microseconds). */
        const val DURATION = 104L
        const val METADATA = 192L
        const val SIZEOF = 480L
    }

    object Stream {
        const val CODECPAR = 16L
        const val TIME_BASE = 32L

        /** int64 in the stream's own time_base. */
        const val DURATION = 48L
        const val DISPOSITION = 64L
        const val METADATA = 80L

        /** An AVPacket embedded by value, not a pointer. */
        const val ATTACHED_PIC = 96L
        const val SIZEOF = 216L
    }

    object Chapter {
        const val TIME_BASE = 8L
        const val START = 16L
        const val END = 24L
        const val METADATA = 32L
        const val SIZEOF = 40L
    }

    object DictEntry {
        const val KEY = 0L
        const val VALUE = 8L
        const val SIZEOF = 16L
    }

    object Packet {
        const val PTS = 8L
        const val DATA = 24L
        const val SIZE = 32L
        const val STREAM_INDEX = 36L
        const val DURATION = 64L
        const val SIZEOF = 104L
    }

    object CodecParameters {
        const val CODEC_TYPE = 0L
        const val CODEC_ID = 4L
        const val EXTRADATA = 16L
        const val EXTRADATA_SIZE = 24L
        const val CODED_SIDE_DATA = 32L
        const val NB_CODED_SIDE_DATA = 40L
        const val WIDTH = 72L
        const val HEIGHT = 76L
        const val CH_LAYOUT = 128L
        const val SAMPLE_RATE = 152L
        const val SIZEOF = 184L
    }

    object PacketSideData {
        const val DATA = 0L
        const val SIZEOF = 24L
    }

    /** Out-parameter of avcodec_decode_subtitle2; caller-allocated. */
    object Subtitle {
        const val FORMAT = 0L

        /** Milliseconds relative to the carrying packet's pts. */
        const val START_DISPLAY_TIME = 4L
        const val END_DISPLAY_TIME = 8L
        const val NUM_RECTS = 12L
        const val RECTS = 16L
        const val SIZEOF = 32L
    }

    object SubtitleRect {
        const val X = 0L
        const val Y = 4L
        const val W = 8L
        const val H = 12L
        const val NB_COLORS = 16L

        /** data[0] = palette indices, data[1] = 32-bit ARGB palette. */
        const val DATA = 24L
        const val LINESIZE = 56L
        const val TYPE = 76L
        const val TEXT = 80L

        /** The ASS event line every text decoder normalizes to. */
        const val ASS = 88L
        const val SIZEOF = 96L
    }

    object Frame {
        const val DATA = 0L
        const val LINESIZE = 64L
        const val WIDTH = 104L
        const val HEIGHT = 108L
        const val NB_SAMPLES = 112L
        const val FORMAT = 116L
        const val PTS = 136L
        const val SAMPLE_RATE = 180L
        const val COLOR_RANGE = 280L
        const val COLORSPACE = 292L
        const val BEST_EFFORT_TIMESTAMP = 304L
        const val CH_LAYOUT = 384L
        const val SIZEOF = 424L
    }

    object ChannelLayout {
        const val NB_CHANNELS = 4L
        const val SIZEOF = 24L
    }

    const val AVMEDIA_TYPE_VIDEO = 0
    const val AVMEDIA_TYPE_AUDIO = 1
    const val AVMEDIA_TYPE_SUBTITLE = 3
    const val AVMEDIA_TYPE_ATTACHMENT = 4
    const val AV_DISPOSITION_DEFAULT = 1
    const val AV_DISPOSITION_FORCED = 64
    const val AV_DISPOSITION_ATTACHED_PIC = 1024
    const val AV_DICT_IGNORE_SUFFIX = 2
    const val AV_PKT_DATA_DISPLAYMATRIX = 5
    const val SUBTITLE_BITMAP = 1
    const val SUBTITLE_TEXT = 2
    const val SUBTITLE_ASS = 3
    const val AV_CODEC_ID_DVD_SUBTITLE = 94208
    const val AV_CODEC_ID_SSA = 94212
    const val AV_CODEC_ID_MOV_TEXT = 94213
    const val AV_CODEC_ID_HDMV_PGS_SUBTITLE = 94214
    const val AV_CODEC_ID_SUBRIP = 94225
    const val AV_CODEC_ID_WEBVTT = 94226
    const val AV_CODEC_ID_ASS = 94230
    const val AV_SAMPLE_FMT_S16 = 1
    const val AV_PIX_FMT_RGBA = 26
    const val SWS_BILINEAR = 2
    const val AVCOL_SPC_BT709 = 1
    const val AVCOL_SPC_UNSPECIFIED = 2
    const val AVCOL_SPC_FCC = 4
    const val AVCOL_SPC_BT470BG = 5
    const val AVCOL_SPC_SMPTE170M = 6
    const val AVCOL_SPC_SMPTE240M = 7
    const val AVCOL_SPC_BT2020_NCL = 9
    const val AVCOL_SPC_BT2020_CL = 10
    const val AVCOL_RANGE_JPEG = 2
    const val SWS_CS_ITU709 = 1
    const val SWS_CS_FCC = 4
    const val SWS_CS_ITU601 = 5
    const val SWS_CS_SMPTE240M = 7
    const val SWS_CS_BT2020 = 9
    const val AVSEEK_FLAG_BACKWARD = 1
    const val AV_CODEC_ID_VP8 = 139
    const val AV_CODEC_ID_VP9 = 167
    const val AV_LOG_QUIET = -8
    const val AVERROR_EOF = -541478725
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
