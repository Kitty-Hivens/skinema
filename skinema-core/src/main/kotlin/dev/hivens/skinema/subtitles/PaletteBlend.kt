package dev.hivens.skinema.subtitles

/**
 * Paletted subtitle bitmap (dvdsub, pgs) to premultiplied RGBA at a
 * tight stride. The palette entries are 32-bit ARGB as libav stores
 * them in rect data[1]; indices walk data[0] at [linesize] per row.
 * Out-of-range indices read as transparent (a damaged stream must not
 * throw -- fail closed per rect).
 */
internal fun paletteToRgba(
    indices: ByteArray,
    linesize: Int,
    width: Int,
    height: Int,
    palette: IntArray,
): ByteArray {
    val out = ByteArray(width * height * 4)
    for (row in 0 until height) {
        val srcRow = row * linesize
        val dstRow = row * width * 4
        for (col in 0 until width) {
            val index = indices[srcRow + col].toInt() and 0xFF
            if (index >= palette.size) continue
            val argb = palette[index]
            val alpha = (argb ushr 24) and 0xFF
            if (alpha == 0) continue
            val i = dstRow + col * 4
            out[i] = (((argb ushr 16) and 0xFF) * alpha / 255).toByte()
            out[i + 1] = (((argb ushr 8) and 0xFF) * alpha / 255).toByte()
            out[i + 2] = ((argb and 0xFF) * alpha / 255).toByte()
            out[i + 3] = alpha.toByte()
        }
    }
    return out
}
