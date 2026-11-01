package dev.hivens.skinema.android

import dev.hivens.skinema.audio.PcmEncoding
import dev.hivens.skinema.audio.PcmFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The canonical layout name for a channel count, as the desktop's decoder names it. */
internal fun layoutFor(channels: Int): String = when (channels) {
    1 -> "mono"
    2 -> "stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels channels"
}

/**
 * Converts interleaved PCM between encodings and channel counts at one rate,
 * through float. The platform decoders hand out 16-bit or float, and a caller
 * negotiating with its device asks for whatever the device took, which is the
 * one conversion this does. The rate is never touched: the contract keeps the
 * stream's own.
 *
 * A fold to fewer channels uses the ITU weights for 5.1 and 7.1 (centre and
 * surrounds at -3 dB, the LFE dropped) and an even split of the remaining
 * channels otherwise, in the WAVE channel order the platform decoders emit.
 */
internal object PcmConvert {

    fun convert(src: ByteArray, srcLength: Int, from: PcmFormat, to: PcmFormat, dst: ByteArray): Int {
        val frames = srcLength / from.bytesPerFrame
        val needed = frames * to.bytesPerFrame
        require(dst.size >= needed) { "destination holds ${dst.size} bytes, $needed needed" }
        val inBuf = ByteBuffer.wrap(src, 0, srcLength).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN)
        val frameIn = FloatArray(from.channels)
        val frameOut = FloatArray(to.channels)
        repeat(frames) {
            for (c in 0 until from.channels) frameIn[c] = read(inBuf, from.encoding)
            mix(frameIn, frameOut)
            for (c in 0 until to.channels) write(outBuf, to.encoding, frameOut[c])
        }
        return needed
    }

    private fun mix(input: FloatArray, output: FloatArray) {
        val n = input.size
        val m = output.size
        when {
            n == m -> input.copyInto(output)
            m == 1 -> output[0] = input.average().toFloat()
            n == 1 -> output.fill(input[0])
            m == 2 && (n == 6 || n == 8) -> {
                // FL FR FC LFE BL BR [SL SR]
                var l = input[0] + MINUS_3DB * input[2] + MINUS_3DB * input[4]
                var r = input[1] + MINUS_3DB * input[2] + MINUS_3DB * input[5]
                if (n == 8) {
                    l += MINUS_3DB * input[6]
                    r += MINUS_3DB * input[7]
                }
                val scale = if (n == 8) 1f / (1f + 3 * MINUS_3DB) else 1f / (1f + 2 * MINUS_3DB)
                output[0] = l * scale
                output[1] = r * scale
            }
            m == 2 -> {
                var l = input[0]
                var r = input[1]
                for (c in 2 until n) {
                    if (c % 2 == 0) l += input[c] else r += input[c]
                }
                val perSide = (n + 1) / 2
                output[0] = l / perSide
                output[1] = r / (n / 2)
            }
            else -> for (c in 0 until m) output[c] = if (c < n) input[c] else 0f
        }
    }

    private fun read(buf: ByteBuffer, encoding: PcmEncoding): Float = when (encoding) {
        PcmEncoding.U8 -> ((buf.get().toInt() and 0xFF) - 128) / 128f
        PcmEncoding.S16LE -> buf.short / 32768f
        PcmEncoding.S32LE -> (buf.int / 2147483648.0).toFloat()
        PcmEncoding.F32LE -> buf.float
        PcmEncoding.F64LE -> buf.double.toFloat()
    }

    private fun write(buf: ByteBuffer, encoding: PcmEncoding, value: Float) {
        val v = value.coerceIn(-1f, 1f)
        when (encoding) {
            PcmEncoding.U8 -> buf.put((Math.round(v * 127f) + 128).toByte())
            PcmEncoding.S16LE -> buf.putShort(Math.round(v * 32767f).toShort())
            PcmEncoding.S32LE -> buf.putInt((v * 2147483647.0).toLong().toInt())
            PcmEncoding.F32LE -> buf.putFloat(v)
            PcmEncoding.F64LE -> buf.putDouble(v.toDouble())
        }
    }

    private const val MINUS_3DB = 0.70710677f
}
