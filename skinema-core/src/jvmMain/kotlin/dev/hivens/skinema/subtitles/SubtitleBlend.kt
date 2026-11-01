package dev.hivens.skinema.subtitles

import dev.hivens.skinema.ass.AssPatch

/**
 * One composited overlay region in frame coordinates: premultiplied
 * RGBA at a tight stride, ready for a PREMUL Skia image or any GPU
 * upload without per-pixel work downstream.
 */
internal class BlendedPatch(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
)

/**
 * Collapses a rendered libass image list into one bounding-box patch.
 * Blending runs in list order, which is libass's paint order, with
 * source-over compositing; each image contributes its single color at
 * the coverage its alpha bitmap dictates, where the color's low byte is
 * INVERTED alpha (0 = opaque). Returns null for an empty list (no cue
 * on screen). [reuse] becomes the output array when its size matches --
 * karaoke retimes publish at tick rate and must not churn the GC.
 */
internal fun blendAssPatches(patches: List<AssPatch>, reuse: ByteArray? = null): BlendedPatch? {
    if (patches.isEmpty()) return null
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    var bottom = Int.MIN_VALUE
    for (p in patches) {
        left = minOf(left, p.dstX)
        top = minOf(top, p.dstY)
        right = maxOf(right, p.dstX + p.width)
        bottom = maxOf(bottom, p.dstY + p.height)
    }
    val width = right - left
    val height = bottom - top
    val bytes = width * height * 4
    val out = reuse?.takeIf { it.size == bytes } ?: ByteArray(bytes)
    out.fill(0)

    for (p in patches) {
        val red = (p.color ushr 24) and 0xFF
        val green = (p.color ushr 16) and 0xFF
        val blue = (p.color ushr 8) and 0xFF
        val opacity = 255 - (p.color and 0xFF)
        if (opacity == 0) continue
        for (row in 0 until p.height) {
            val srcRow = row * p.stride
            val dstRow = ((p.dstY - top + row) * width + (p.dstX - left)) * 4
            for (col in 0 until p.width) {
                val coverage = p.alpha[srcRow + col].toInt() and 0xFF
                if (coverage == 0) continue
                val alpha = coverage * opacity / 255
                val keep = 255 - alpha
                val i = dstRow + col * 4
                out[i] = (red * alpha / 255 + (out[i].toInt() and 0xFF) * keep / 255).toByte()
                out[i + 1] = (green * alpha / 255 + (out[i + 1].toInt() and 0xFF) * keep / 255).toByte()
                out[i + 2] = (blue * alpha / 255 + (out[i + 2].toInt() and 0xFF) * keep / 255).toByte()
                out[i + 3] = (alpha + (out[i + 3].toInt() and 0xFF) * keep / 255).toByte()
            }
        }
    }
    return BlendedPatch(left, top, width, height, out)
}
