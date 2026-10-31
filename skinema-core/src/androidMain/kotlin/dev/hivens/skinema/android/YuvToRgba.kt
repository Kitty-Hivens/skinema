package dev.hivens.skinema.android

import android.graphics.Rect
import android.media.Image
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The YUV matrix a frame is converted with, as the luma weights of its red
 * and blue primaries. The desktop picks swscale's coefficient table by the
 * same rule: what the stream declares, and HD geometry means BT.709 when it
 * declares nothing.
 */
internal enum class YuvMatrix(val kr: Double, val kb: Double) {
    BT601(0.299, 0.114),
    BT709(0.2126, 0.0722),
    BT2020(0.2627, 0.0593),
    ;

    companion object {
        fun of(standard: Int?, width: Int, height: Int): YuvMatrix = when (standard) {
            MediaFormat.COLOR_STANDARD_BT709 -> BT709
            MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> BT601
            MediaFormat.COLOR_STANDARD_BT2020 -> BT2020
            else -> if (width >= 1280 || height >= 720) BT709 else BT601
        }
    }
}

/**
 * Converts decoded frames to RGBA8888 on the CPU, in 16.16 fixed point.
 *
 * MediaCodec in buffer mode hands out a flexible YUV 4:2:0 image whatever
 * the decoder's own layout is, so one reader covers every codec and every
 * vendor. The colour handling is ours rather than the driver's, which is the
 * point: the matrix and the range follow the same rule the desktop path
 * follows, so a file converts to the same pixels on both.
 */
internal class YuvToRgba(matrix: YuvMatrix, private val fullRange: Boolean) {


    /** The matrix in 16.16 fixed point for one code width: limited range spans a different share of 8 and of 10 bits. */
    private class Coefficients(matrix: YuvMatrix, fullRange: Boolean, maxCode: Double, lumaCodes: Double, chromaCodes: Double) {
        val yScale: Int
        val crToR: Int
        val cbToG: Int
        val crToG: Int
        val cbToB: Int

        init {
            val kr = matrix.kr
            val kb = matrix.kb
            val kg = 1.0 - kr - kb
            val ys = if (fullRange) 1.0 else maxCode / lumaCodes
            val cs = if (fullRange) 1.0 else maxCode / chromaCodes
            yScale = fixed(ys)
            crToR = fixed(2.0 * (1.0 - kr) * cs)
            cbToB = fixed(2.0 * (1.0 - kb) * cs)
            cbToG = fixed(2.0 * (1.0 - kb) * kb / kg * cs)
            crToG = fixed(2.0 * (1.0 - kr) * kr / kg * cs)
        }
    }

    // Limited range spans 219 luma and 224 chroma codes of 255, and 876 and
    // 896 of 1023.
    private val eight = Coefficients(matrix, fullRange, 255.0, 219.0, 224.0)
    private val ten = Coefficients(matrix, fullRange, 1023.0, 876.0, 896.0)

    /**
     * Writes [image]'s [crop] as tightly packed RGBA8888 into [out], which
     * must hold crop.width * crop.height * 4 bytes. Alpha is opaque: the
     * platform decoders hand no alpha plane out.
     */
    fun convert(image: Image, crop: Rect, out: ByteArray) {
        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        val yOffset = if (fullRange) 0 else 16
        val k = eight
        var d = 0
        for (y in crop.top until crop.bottom) {
            val yLine = y * yRow
            val cLine = y / 2
            for (x in crop.left until crop.right) {
                val luma = ((yBuf.get(yLine + x * yPix).toInt() and 0xFF) - yOffset) * k.yScale
                val cx = x / 2
                val cb = (uBuf.get(cLine * uRow + cx * uPix).toInt() and 0xFF) - 128
                val cr = (vBuf.get(cLine * vRow + cx * vPix).toInt() and 0xFF) - 128
                out[d] = clamp8(luma + k.crToR * cr)
                out[d + 1] = clamp8(luma - k.cbToG * cb - k.crToG * cr)
                out[d + 2] = clamp8(luma + k.cbToB * cb)
                out[d + 3] = OPAQUE
                d += 4
            }
        }
    }

    /**
     * Writes a P010 [image]'s [crop] as RGBA64 into [out] (four unsigned
     * shorts a pixel, full range): the 16-bit non-linear signal the tone
     * mapper expects, with the transfer still applied. Ten significant bits
     * sit at the top of each sixteen.
     */
    fun convertP010(image: Image, crop: Rect, out: ShortArray) {
        val planes = image.planes
        val yBuf = planes[0].buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val uBuf = planes[1].buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val vBuf = planes[2].buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        var d = 0
        for (y in crop.top until crop.bottom) {
            val cLine = y / 2
            for (x in crop.left until crop.right) {
                val cx = x / 2
                pixel10(
                    sample10(yBuf, y * yRow + x * yPix),
                    sample10(uBuf, cLine * uRow + cx * uPix),
                    sample10(vBuf, cLine * vRow + cx * vPix),
                    out, d,
                )
                d += 4
            }
        }
    }

    /** One pixel of ten-bit codes as RGBA64 at [at] in [out]. */
    internal fun pixel10(y: Int, cb: Int, cr: Int, out: ShortArray, at: Int) {
        val k = ten
        val luma = (y - if (fullRange) 0 else 64) * k.yScale
        val u = cb - CHROMA_MID_10
        val v = cr - CHROMA_MID_10
        out[at] = clamp16(luma + k.crToR * v)
        out[at + 1] = clamp16(luma - k.cbToG * u - k.crToG * v)
        out[at + 2] = clamp16(luma + k.cbToB * u)
        out[at + 3] = OPAQUE16
    }

    private companion object {
        const val ONE = 1 shl 16
        const val OPAQUE = 0xFF.toByte()
        const val OPAQUE16 = 0xFFFF.toShort()
        const val CHROMA_MID_10 = 512

        fun fixed(v: Double): Int = (v * ONE + 0.5).toInt()

        fun clamp8(v: Int): Byte = ((v + (ONE shr 1)) shr 16).coerceIn(0, 255).toByte()

        /** A ten-bit fixed-point value scaled up to sixteen bits. */
        fun clamp16(v: Int): Short = (((v + (ONE shr 1)) shr 16).coerceIn(0, 1023) * 65535 / 1023).toShort()

        fun sample10(buf: ByteBuffer, at: Int): Int = (buf.getShort(at).toInt() and 0xFFFF) ushr 6
    }
}
