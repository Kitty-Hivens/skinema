package dev.hivens.skinema.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import dev.hivens.skinema.Debug
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.LibavException

/**
 * The platform decoders that can take [mime], in the order a policy allows
 * them: [HwAccel.OFF] keeps the software ones only, [HwAccel.REQUIRE] the
 * hardware ones only, and [HwAccel.AUTO] all of them with hardware first.
 *
 * The same three words the desktop gives them. OFF is software decode here
 * too, which on a phone is the expensive answer: a consumer that wants the
 * platform's hardware decoder asks for AUTO.
 */
internal fun decoderCandidates(mime: String, hardware: HwAccel): List<MediaCodecInfo> {
    val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }
    return when (hardware) {
        HwAccel.OFF -> all.filter { it.isSoftwareOnly }
        HwAccel.REQUIRE -> all.filter { it.isHardwareAccelerated }
        HwAccel.AUTO -> all.sortedByDescending { it.isHardwareAccelerated }
    }
}

/**
 * A configured, started decoder for [format], the first candidate that
 * accepts it. A decoder that refuses the format is closed and the next one
 * asked, so a hardware decoder that cannot take a profile falls through to
 * software under AUTO, the way the desktop falls back. None left is a
 * refusal by name, and under REQUIRE it says so.
 */
internal fun openDecoder(
    format: MediaFormat,
    hardware: HwAccel,
    configure: (MediaCodec) -> Unit,
): MediaCodec {
    val mime = format.getString(MediaFormat.KEY_MIME)
        ?: throw LibavException("the stream declares no media type")
    val candidates = decoderCandidates(mime, hardware)
    for (info in candidates) {
        val codec = try {
            MediaCodec.createByCodecName(info.name)
        } catch (e: Exception) {
            Debug.trace("creating decoder ${info.name}", e)
            continue
        }
        try {
            configure(codec)
            codec.start()
            return codec
        } catch (e: Exception) {
            Debug.trace("configuring decoder ${info.name} for $mime", e)
            runCatching { codec.release() }
        }
    }
    val policy = if (hardware == HwAccel.REQUIRE) " (HwAccel.REQUIRE)" else if (hardware == HwAccel.OFF) " (HwAccel.OFF)" else ""
    throw LibavException("no decoder on this device takes $mime$policy")
}
