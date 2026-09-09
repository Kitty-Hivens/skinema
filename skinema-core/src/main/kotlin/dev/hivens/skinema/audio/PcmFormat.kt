package dev.hivens.skinema.audio

/**
 * How samples are laid out in the bytes a [PcmSink] is handed.
 *
 * The set is what a decoder can actually produce, not a convenience pair:
 * FFmpeg decodes into 8-bit, 16-bit, 32-bit integer, float and double, and
 * anything the seam refuses to carry is a conversion the player would have to
 * do behind the consumer's back. Every one of these is interleaved and
 * little-endian.
 *
 * There is no 24-bit member, and its absence is a decision rather than an
 * oversight. FFmpeg has no 24-bit sample format at all: a 24-bit file decodes
 * into [S32LE] with the value in the high 24 bits and eight zero bits below it,
 * so [S32LE] carries such content whole. A device that wants three packed bytes
 * wants a narrower container, not more information, and the packing belongs
 * beside that device. What it needs to do the packing safely is
 * [PcmFormat.significantBits].
 */
enum class PcmEncoding(
    /** Bytes one sample of one channel occupies. */
    val bytesPerSample: Int,
) {
    U8(1),
    S16LE(2),
    S32LE(4),
    F32LE(4),
    F64LE(8),
}

/**
 * The shape of the PCM crossing the [PcmSink] seam.
 *
 * The player negotiates this rather than dictating it: it asks for the shape
 * the file actually has and walks down to something the sink accepts, so
 * nothing is converted for the sake of a seam that could have carried it. See
 * [PcmSink.open] for how a sink refuses.
 */
data class PcmFormat(
    /** Sample rate of the media. Never converted: see [PcmSink.open]. */
    val sampleRate: Int,
    /** Channels per frame, interleaved in the order [layout] names. */
    val channels: Int,
    /**
     * The channel order, as FFmpeg canonically names it: `mono`, `stereo`,
     * `5.1`, `5.1(side)`, `7.1`. A count alone does not say where a channel
     * goes, and 5.1 against 5.1(side) is the ordinary pair that differs, so the
     * name travels with the count rather than being assumed from it.
     *
     * A stream whose layout the container does not state comes across as the
     * default layout for its channel count, which is what FFmpeg would assume
     * anyway, named here so both sides assume the same thing.
     */
    val layout: String,
    val encoding: PcmEncoding,
    /**
     * How many bits of each sample are real, which is not always the width of
     * the container it arrives in.
     *
     * A 24-bit file decodes into [PcmEncoding.S32LE] with eight zero bits at
     * the bottom, and some DTS carries twenty. A sink that has to narrow the
     * samples for its device needs this to know whether it is discarding
     * padding or data; without it, packing 32-bit samples into three bytes is a
     * guess that is silently wrong on the content that actually uses the width.
     *
     * Equal to the encoding's own width when the source fills it, and never
     * larger than that width.
     */
    val significantBits: Int,
) {
    /** Bytes one frame occupies: one sample per channel. */
    val bytesPerFrame: Int get() = channels * encoding.bytesPerSample

    init {
        require(sampleRate > 0) { "sample rate must be positive, got $sampleRate" }
        require(channels > 0) { "channels must be positive, got $channels" }
        require(significantBits in 1..(encoding.bytesPerSample * 8)) {
            "significant bits must fit the encoding, got $significantBits in $encoding"
        }
    }

    companion object {
        /**
         * The format every [PcmSink] must accept, and the last rung of the
         * negotiation. Nothing about it is ideal: it is the shape a device that
         * can do only one thing can always be given.
         */
        fun floor(sampleRate: Int): PcmFormat = PcmFormat(
            sampleRate = sampleRate,
            channels = 2,
            layout = "stereo",
            encoding = PcmEncoding.S16LE,
            significantBits = 16,
        )
    }
}

/** How many channels to ask a device for. See [formatLadder]. */
enum class ChannelPreference {
    /**
     * What the file has. A device that cannot take it refuses, and the fold to
     * stereo happens then rather than always.
     *
     * The default, on the same argument the sample rate is left alone under:
     * the audio server knows the speaker layout and this library does not, so
     * folding here would be a second guess on top of the one it makes anyway.
     */
    Source,

    /**
     * Two channels, whatever the file has. For a consumer that knows the fold
     * is wanted: a device may accept six channels and still be heard through
     * two speakers, and accepting is not evidence of hearing.
     */
    Stereo,
}

/**
 * The shapes to offer a sink, best first, ending at [PcmFormat.floor].
 *
 * Pure, and separated from the pipeline for the reason every decision in this
 * codebase is: a ladder that is only exercised through a real device is a
 * ladder tested on one machine's answers. The order is what it claims to be,
 * fidelity first, and that is assertable without opening anything.
 *
 * Channels rank above sample width deliberately. A listener notices a surround
 * mix folded into two speakers long before they notice the bottom bits of a
 * sample, so a device that takes six channels at sixteen bits is offered that
 * before two channels at thirty-two.
 */
internal fun formatLadder(source: PcmFormat, preference: ChannelPreference): List<PcmFormat> {
    val counts = when (preference) {
        ChannelPreference.Source -> listOf(source.channels, 2)
        ChannelPreference.Stereo -> listOf(2)
    }.distinct().filter { it > 0 }

    val encodings = when (source.encoding) {
        PcmEncoding.U8 -> listOf(PcmEncoding.U8, PcmEncoding.S16LE)
        PcmEncoding.S16LE -> listOf(PcmEncoding.S16LE)
        PcmEncoding.S32LE -> listOf(PcmEncoding.S32LE, PcmEncoding.F32LE, PcmEncoding.S16LE)
        PcmEncoding.F32LE -> listOf(PcmEncoding.F32LE, PcmEncoding.S32LE, PcmEncoding.S16LE)
        PcmEncoding.F64LE ->
            listOf(PcmEncoding.F64LE, PcmEncoding.F32LE, PcmEncoding.S32LE, PcmEncoding.S16LE)
    }.filter { candidate ->
        // Float carries twenty-four bits of an integer exactly and not one
        // more, so it is a lossless carrier for a 24-bit file and a lossy one
        // for a 32-bit file. A float source is not narrowed by being asked for
        // in the format it already is.
        candidate != PcmEncoding.F32LE ||
            source.encoding == PcmEncoding.F32LE ||
            source.significantBits <= 24
    }

    val rungs = counts.flatMap { count ->
        encodings.map { encoding ->
            PcmFormat(
                sampleRate = source.sampleRate,
                channels = count,
                layout = layoutNameFor(count, source),
                encoding = encoding,
                significantBits = minOf(source.significantBits, encoding.bytesPerSample * 8),
            )
        }
    }
    return (rungs + PcmFormat.floor(source.sampleRate)).distinct()
}

/**
 * The order a fold lands in. The source's own name when nothing is folded,
 * because that is the one case where the order is known rather than assumed.
 */
private fun layoutNameFor(channels: Int, source: PcmFormat): String = when {
    channels == source.channels -> source.layout
    channels == 1 -> "mono"
    channels == 2 -> "stereo"
    else -> "$channels channels"
}
