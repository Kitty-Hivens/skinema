package dev.hivens.skinema.skiko

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Owns the Skia image for the current video frame. [update] raster-copies
 * the RGBA pixels into a fresh image and closes the previous one -- Skia
 * images hold native memory, and waiting for the finalizer is a leak in
 * practice (ROADMAP.md section 6). Single-threaded by design: call from
 * the same thread that draws (the Compose frame pump does both).
 */
class VideoFrameImage : AutoCloseable {

    /** The current frame's image; null before the first [update]. */
    var image: Image? = null
        private set

    /**
     * Replaces [image] with [rgba] ([width] x [height], straight alpha,
     * stride = width * 4) and returns it. The pixels are copied, so the
     * caller's buffer is free for reuse immediately.
     */
    fun update(width: Int, height: Int, rgba: ByteArray): Image {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val next = Image.makeRaster(info, rgba, rowBytes = width * 4)
        image?.close()
        image = next
        return next
    }

    override fun close() {
        image?.close()
        image = null
    }
}
