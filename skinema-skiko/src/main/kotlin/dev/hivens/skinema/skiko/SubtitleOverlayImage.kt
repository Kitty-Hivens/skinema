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
        val next = mutableListOf<Placed>()
        try {
            for (p in patches) {
                // The same reason the frame holder checks: Skia reads
                // height * rowBytes and checks nothing.
                require(p.width > 0 && p.height > 0) {
                    "a patch must have positive dimensions, got ${p.width}x${p.height}"
                }
                require(p.rgba.size >= p.width.toLong() * p.height * 4) {
                    "a ${p.width}x${p.height} patch needs ${p.width * p.height * 4} bytes, got ${p.rgba.size}"
                }
                next += Placed(
                    Image.makeRaster(
                        ImageInfo(p.width, p.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                        p.rgba,
                        rowBytes = p.width * 4,
                    ),
                    p.x,
                    p.y,
                )
            }
        } catch (t: Throwable) {
            // Built one at a time, so a refusal partway through leaves the
            // ones already made held by nothing at all -- the leak this
            // class's own close-the-previous discipline exists to avoid.
            next.forEach { it.image.close() }
            throw t
        }
        images.forEach { it.image.close() }
        images = next
    }

    override fun close() {
        images.forEach { it.image.close() }
        images = emptyList()
    }
}
