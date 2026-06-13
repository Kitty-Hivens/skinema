package dev.hivens.skinema.skiko

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Owns the Skia images for the current subtitle overlay, the
 * [VideoFrameImage] discipline: every update closes its predecessors
 * (Skia objects hold native memory). Premultiplied alpha -- the blend
 * pipeline emits it, and PREMUL skips a per-pixel conversion on upload.
 * Like its sibling, deliberately core-independent: positioned pixel
 * patches in, placed images out.
 */
class SubtitleOverlayImage : AutoCloseable {

    /** One overlay region as the consumer drew it, in canvas coordinates. */
    class PatchPixels(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        /** Premultiplied RGBA, stride = width * 4; copied on update. */
        val rgba: ByteArray,
    )

    class Placed internal constructor(
        val image: Image,
        val x: Int,
        val y: Int,
    )

    var images: List<Placed> = emptyList()
        private set

    /** Replaces the overlay; an empty list is the clear. */
    fun update(patches: List<PatchPixels>) {
        val next = patches.map { p ->
            Placed(
                Image.makeRaster(
                    ImageInfo(p.width, p.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                    p.rgba,
                    rowBytes = p.width * 4,
                ),
                p.x,
                p.y,
            )
        }
        images.forEach { it.image.close() }
        images = next
    }

    override fun close() {
        images.forEach { it.image.close() }
        images = emptyList()
    }
}
