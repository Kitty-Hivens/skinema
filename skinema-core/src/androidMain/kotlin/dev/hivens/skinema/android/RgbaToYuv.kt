package dev.hivens.skinema.android

import android.media.Image

/**
 * RGBA8888 into an encoder's flexible YUV 4:2:0 input image, limited range,
 * in the matrix [YuvMatrix.of] picks for the geometry: the rule the desktop
 * writer converts and tags by, so a file written on either platform reads
 * back the same. Alpha is dropped; the platform encoders carry none.
 */
internal class RgbaToYuv(private val width: Int, private val height: Int) {

    val matrix: YuvMatrix = YuvMatrix.of(null, width, height)

    private val kr = matrix.kr.toFloat()
    private val kb = matrix.kb.toFloat()
    private val kg = 1f - kr - kb

    fun convert(rgba: ByteArray, image: Image) {
        val planes = image.planes
        val y = planes[0].buffer
        val u = planes[1].buffer
        val v = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        for (row in 0 until height step 2) {
            for (col in 0 until width step 2) {
                var cb = 0f
                var cr = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val i = ((row + dy) * width + col + dx) * 4
                    val r = (rgba[i].toInt() and 0xFF).toFloat()
                    val g = (rgba[i + 1].toInt() and 0xFF).toFloat()
                    val b = (rgba[i + 2].toInt() and 0xFF).toFloat()
                    val luma = kr * r + kg * g + kb * b
                    y.put((row + dy) * yRow + (col + dx) * yPix, toByte(16f + luma * 219f / 255f))
                    cb += (b - luma) / (2f * (1f - kb))
                    cr += (r - luma) / (2f * (1f - kr))
                }
                val cRow = row / 2
                val cCol = col / 2
                u.put(cRow * uRow + cCol * uPix, toByte(128f + cb / 4f * 224f / 255f))
                v.put(cRow * vRow + cCol * vPix, toByte(128f + cr / 4f * 224f / 255f))
            }
        }
    }

    private fun toByte(value: Float): Byte = (value + 0.5f).toInt().coerceIn(0, 255).toByte()
}
