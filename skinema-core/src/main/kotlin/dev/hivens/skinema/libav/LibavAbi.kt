package dev.hivens.skinema.libav

/**
 * Struct offsets and ABI constants for the pinned FFmpeg major line (n9.0,
 * ROADMAP.md section 4), captured by tools/layout-oracle.c compiled against
 * that line's headers. Do not edit by hand -- re-run the oracle on a major
 * bump and transcribe its output.
 *
 * The n8.1 -> n9.0 bump moved no struct offset at all: every value below was
 * re-captured against the 9.0 headers and matched. The one constant that
 * moved was AV_CODEC_ID_VP9 -- which is exactly why the ids are transcribed
 * rather than assumed stable.
 *
 * Offsets are stable within a soname major; [Libav] verifies the loaded
 * majors before anything here is dereferenced.
 */
object LibavAbi {

    object FormatContext {
        /** AVOutputFormat* -- the muxer (avformat_alloc_output_context2 sets it). */
        const val OFORMAT = 16L

        /** AVIOContext* -- the byte sink/source (avio_open, or a custom AVIO context for input). */
        const val PB = 32L

        /** AVFMT_FLAG_*; CUSTOM_IO is set here for a custom AVIO input source. */
        const val FLAGS = 128L
        const val NB_STREAMS = 44L
        const val STREAMS = 48L

        /** Output URL/filename string. */
        const val URL = 88L
        const val NB_CHAPTERS = 72L
        const val CHAPTERS = 80L

        /** int64 in AV_TIME_BASE units (microseconds); the timeline origin. */
        const val START_TIME = 96L

        /** int64 in AV_TIME_BASE units (microseconds). */
        const val DURATION = 104L
        const val METADATA = 192L
        const val SIZEOF = 480L
    }

    /** AVOutputFormat: only its AVFMT_* flags are read (header/IO gating). */
    object OutputFormat {
        const val FLAGS = 44L
        const val SIZEOF = 64L
    }

    /** AVIOContext: only its buffer pointer is read, to free it after a custom-IO session. */
    object AvioContext {
        const val BUFFER = 8L
    }

    object Stream {
        const val INDEX = 8L
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
        const val DTS = 16L
        const val DATA = 24L
        const val SIZE = 32L
        const val STREAM_INDEX = 36L
        const val FLAGS = 40L
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

        /** enum AVPixelFormat for video, AVSampleFormat for audio. */
        const val FORMAT = 44L
        const val WIDTH = 72L
        const val HEIGHT = 76L
        const val CH_LAYOUT = 128L
        const val SAMPLE_RATE = 152L
        const val SIZEOF = 184L
    }

    object PacketSideData {
        const val DATA = 0L

        /** size_t; av_display_rotation_get reads 9 int32 and checks nothing. */
        const val SIZE = 8L
        const val SIZEOF = 24L
    }

    /**
     * Direct reads on AVCodecContext are otherwise avoided (functions
     * cover everything); subtitle_header has no accessor, and converted
     * text decoders synthesize the ASS style header THERE at open --
     * codecpar extradata is empty for them. The two hwaccel fields are
     * WRITTEN, not read: get_format installs the negotiation upcall and
     * hw_device_ctx hands the decoder its device.
     */
    object CodecContext {
        // -- M12 encode write fields --
        const val BIT_RATE = 56L

        /** AV_CODEC_FLAG_*; the encode side sets GLOBAL_HEADER here. */
        const val FLAGS = 64L

        /** AVRational time_base (num at +0, den at +4): the unit of encoded pts. */
        const val TIME_BASE = 84L

        /** AVRational framerate: the encoder's rate-control hint. */
        const val FRAMERATE = 100L
        const val WIDTH = 112L
        const val HEIGHT = 116L

        /** AVPixelFormat the encoder takes, negotiated against what it advertises. */
        const val PIX_FMT = 136L
        const val MAX_B_FRAMES = 200L
        const val GOP_SIZE = 332L

        // -- M12 audio encode write fields --
        const val SAMPLE_RATE = 344L
        const val SAMPLE_FMT = 348L
        const val CH_LAYOUT = 352L

        /** Samples per encoded frame, reported by the encoder after open (0 = variable). */
        const val FRAME_SIZE = 376L

        /** AVPixelFormat (*get_format)(...): the hwaccel format-negotiation upcall. */
        const val GET_FORMAT = 192L

        /**
         * void* reserved for the caller, untouched by libav and carried into
         * every worker context frame threading clones -- which is what makes
         * it the only place a per-decoder fact reaches [GET_FORMAT].
         */
        const val OPAQUE = 48L

        /** AVBufferRef* to the AVHWFramesContext the encoder pulls GPU surfaces from (M13). */
        const val HW_FRAMES_CTX = 552L

        /** AVBufferRef* to the AVHWDeviceContext driving hardware decode. */
        const val HW_DEVICE_CTX = 560L

        const val SUBTITLE_HEADER_SIZE = 748L
        const val SUBTITLE_HEADER = 752L
        const val SIZEOF = 864L
    }

    /** AVCodecHWConfig, walked by avcodec_get_hw_config to find a usable hwaccel. */
    object CodecHWConfig {
        const val PIX_FMT = 0L
        const val METHODS = 4L
        const val DEVICE_TYPE = 8L
        const val SIZEOF = 12L
    }

    /**
     * AVBufferRef: only its [DATA] pointer is read -- av_hwframe_ctx_alloc
     * returns one whose data is the [HwFramesContext] to configure (M13).
     */
    object BufferRef {
        const val DATA = 8L
        const val SIZEOF = 24L
    }

    /**
     * AVHWFramesContext: the GPU surface pool the hardware encoder draws
     * from. Reached through an AVBufferRef's [BufferRef.DATA]; [FORMAT] is
     * the hw-surface format (e.g. AV_PIX_FMT_VAAPI), [SW_FORMAT] the layout
     * uploaded into it (NV12), and [INITIAL_POOL_SIZE] pre-allocates
     * surfaces (VAAPI wants a fixed pool).
     */
    object HwFramesContext {
        const val INITIAL_POOL_SIZE = 56L
        const val FORMAT = 60L
        const val SW_FORMAT = 64L
        const val WIDTH = 68L
        const val HEIGHT = 72L
        const val SIZEOF = 80L
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

        // The planar plane array. `data` holds only AV_NUM_DATA_POINTERS (8)
        // of them; beyond that FFmpeg stores the planes here and `data` is
        // just the first eight. Passing `data` to a resampler on planar audio
        // with more than eight channels walks off its end into `linesize`,
        // where small ints get read as pointers.
        const val EXTENDED_DATA = 96L
        const val WIDTH = 104L
        const val HEIGHT = 108L
        const val NB_SAMPLES = 112L
        const val FORMAT = 116L
        const val PTS = 136L

        // How long this frame is shown. Animated WebP declares no container
        // duration, so the last frame's pts plus this is the only way to
        // learn one without decoding the file twice.
        const val DURATION = 408L
        const val SAMPLE_RATE = 180L
        const val COLOR_RANGE = 280L

        /** AVColorTransferCharacteristic; HDR is detected here (PQ=16, HLG=18). */
        const val COLOR_TRC = 288L
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

    /**
     * AVCodecDescriptor: only its property bits are read, to ask the library
     * whether a subtitle codec is text or bitmap rather than keeping a list.
     */
    object CodecDescriptor {
        const val PROPS = 24L
        const val SIZEOF = 48L
    }

    const val AV_CODEC_PROP_BITMAP_SUB = 65536
    const val AV_CODEC_PROP_TEXT_SUB = 131072
    const val AV_CODEC_ID_SUBRIP = 94225
    const val AV_CODEC_ID_WEBVTT = 94226
    const val AV_CODEC_ID_ASS = 94230
    const val AV_SAMPLE_FMT_S16 = 1
    const val AV_SAMPLE_FMT_S32 = 2
    const val AV_SAMPLE_FMT_FLT = 3
    const val AV_SAMPLE_FMT_S16P = 6
    const val AV_SAMPLE_FMT_S32P = 7

    /** Planar 32-bit float -- the native AAC encoder's input format. */
    const val AV_SAMPLE_FMT_FLTP = 8

    /**
     * enum AVCodecConfig, for avcodec_get_supported_config. Each list is
     * NULL when the codec accepts anything of that kind.
     */
    const val AV_CODEC_CONFIG_PIX_FORMAT = 0
    const val AV_CODEC_CONFIG_SAMPLE_RATE = 2
    const val AV_CODEC_CONFIG_SAMPLE_FORMAT = 3
    const val AV_PIX_FMT_RGBA = 26

    /** 16-bit-per-channel RGBA: the precision staging format for HDR tone-mapping. */
    const val AV_PIX_FMT_RGBA64LE = 105

    /** Sentinel: no pixel format -- the "decode in software" answer and the get_format list terminator. */
    const val AV_PIX_FMT_NONE = -1

    // Hardware-surface pixel formats: a frame in one of these lives in GPU
    // memory; av_hwframe_transfer_data brings it down to a software format
    // swscale can read. The get_format upcall pins one to keep frames on
    // the device.
    const val AV_PIX_FMT_VAAPI = 44
    const val AV_PIX_FMT_DXVA2_VLD = 51
    const val AV_PIX_FMT_QSV = 114
    const val AV_PIX_FMT_CUDA = 117
    const val AV_PIX_FMT_VIDEOTOOLBOX = 157
    const val AV_PIX_FMT_D3D11 = 171

    /** NV12: the software layout uploaded into a hw surface for GPU encode (M13). */
    const val AV_PIX_FMT_NV12 = 23

    /** AVHWDeviceType selectors for av_hwdevice_ctx_create, per platform. */
    const val AV_HWDEVICE_TYPE_CUDA = 2
    const val AV_HWDEVICE_TYPE_VAAPI = 3
    const val AV_HWDEVICE_TYPE_DXVA2 = 4
    const val AV_HWDEVICE_TYPE_QSV = 5
    const val AV_HWDEVICE_TYPE_VIDEOTOOLBOX = 6
    const val AV_HWDEVICE_TYPE_D3D11VA = 7

    /** AVCodecHWConfig.methods bit: the decoder accepts an AVHWDeviceContext. */
    const val AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX = 1

    /** AVCodecHWConfig.methods bit: the encoder takes frames from an AVHWFramesContext (M13). */
    const val AV_CODEC_HW_CONFIG_METHOD_HW_FRAMES_CTX = 2

    // -- M12 encode + mux --
    const val AV_PIX_FMT_YUV422P = 4
    const val AV_PIX_FMT_YUV444P = 5
    const val AV_PIX_FMT_GBRP = 168
    const val AV_PIX_FMT_RGB24 = 2
    const val AV_PIX_FMT_YUV420P = 0
    const val AV_PIX_FMT_YUVA420P = 33

    /** AVOutputFormat.flags: NOFILE skips avio_open; GLOBALHEADER moves extradata into the header. */
    const val AVFMT_NOFILE = 1
    const val AVFMT_GLOBALHEADER = 64
    const val AV_CODEC_FLAG_GLOBAL_HEADER = 0x400000
    const val AVIO_FLAG_WRITE = 2

    /** av_opt_set flag: also search a context's private child (codec) options -- crf, preset, ... */
    const val AV_OPT_SEARCH_CHILDREN = 1

    // -- custom AVIO input (segment/stream feeding) --
    const val AVFMT_FLAG_CUSTOM_IO = 128

    /** seek "whence" that returns the total stream size instead of seeking. */
    const val AVSEEK_SIZE = 65536
    const val SEEK_SET = 0
    const val SEEK_CUR = 1
    const val SEEK_END = 2

    /** The avio_alloc_context bounce buffer: one read-callback fill (FFmpeg owns and may realloc it). */
    const val AVIO_BUFFER_SIZE = 32768

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

    /** HDR transfer characteristics -- the only triggers for the tone-mapper. */
    const val AVCOL_TRC_SMPTE2084 = 16
    const val AVCOL_TRC_ARIB_STD_B67 = 18
    const val SWS_CS_ITU709 = 1
    const val SWS_CS_FCC = 4
    const val SWS_CS_ITU601 = 5
    const val SWS_CS_SMPTE240M = 7
    const val SWS_CS_BT2020 = 9
    const val AVSEEK_FLAG_BACKWARD = 1
    const val AV_CODEC_ID_VP8 = 139

    /** Was 167 on n8.1; the enum shifted under it in n9.0 (167 is now aic). */
    const val AV_CODEC_ID_VP9 = 166
    const val AV_LOG_QUIET = -8
    const val AVERROR_EOF = -541478725

    /**
     * av_find_best_stream found no stream of the requested type. Distinct
     * from AVERROR_DECODER_NOT_FOUND, which means the stream is there and
     * unplayable -- one is a shape of file, the other is a failure.
     */
    const val AVERROR_STREAM_NOT_FOUND = -1381258232

    /**
     * av_find_best_stream found the stream but no decoder for it. Still
     * "nothing this can show" from the player's side -- a trimmed bundle
     * carries a deliberately narrow decoder set -- so it is not a failure.
     */
    const val AVERROR_DECODER_NOT_FOUND = -1128613112
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
