package dev.hivens.skinema.skiko

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Owns the Skia images for the current subtitle overlay: positioned pixel
 * patches in, placed images out, premultiplied alpha -- the blend pipeline
 * emits it, and PREMUL skips a per-pixel conversion on upload. Deliberately
 * core-independent, like its sibling [VideoFrameImage].
 *
 * ## One live borrow per side
 *
 * The same rule [VideoFrameImage] keeps, and for the same reason: the images
 * belong to this class and the caller never closes one, so the class has to
 * know when a reference stops being live. Freeing one the drawing thread is
 * painting with is a native crash; freeing none is native memory nothing
 * reports.
 *
 * - what [update] returns lasts until your next [update] publishes over it,
 * - what [images] returns lasts until your next read of [images], because the
 *   drawing thread's most recent read is the one generation kept alive after
 *   the overlay it names has been superseded.
 *
 * Everything older is unreachable -- no caller has a way to name it again --
 * so this closes it itself. Read [images] ONCE per draw and do not keep the
 * result: a drawer that reads twice and holds both can have the older closed
 * under it.
 *
 * It used to close every image it replaced on the spot, which made [update]
 * the drawing thread's alone -- while the guide tells you, correctly, to
 * raster frames off that thread. A consumer generalising from one to the
 * other freed overlay pixels under a draw. Now the two answer the same way.
 *
 * ## close() frees, it does not shut the door
 *
 * The other half of [VideoFrameImage]'s contract does NOT carry over, because
 * this class has a second job: turning subtitles off is a reason to drop the
 * pixels while the surface lives on, and a re-selection has to be able to
 * publish again. So [close] releases what is held and leaves the object
 * usable, where the frame holder's [close] is a teardown that refuses later
 * work. Stop the thread that calls [update] before tearing down, the way the
 * surface already joins its raster thread.
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

    private val lock = Any()

    // The overlay on screen, and the drawing thread's borrow against an older
    // one. Both guarded by [lock], so a publish and a read cannot interleave:
    // a read landing just after the publish that superseded its generation
    // would take a reference the same publish had already freed.
    private var current: List<Placed> = emptyList()
    private var lastDrawn: List<Placed>? = null

    // Superseded generations still spoken for. Holds the drawing thread's
    // borrow and nothing else, so it is empty or holds one. Empty generations
    // never enter it -- there is nothing in one to free.
    private val retired = ArrayDeque<List<Placed>>()

    /**
     * The overlay to draw, empty when there is nothing on screen. Reading it
     * is the drawing thread's borrow: that generation stays alive until this
     * is read again.
     */
    val images: List<Placed> get() = synchronized(lock) {
        current.also {
            // Taking the next generation is what makes the previous one
            // unreachable, so it is freed here rather than at the next
            // publish. On a track whose cues are minutes apart that is the
            // difference between holding one generation's pixels for a frame
            // and holding them until something else happens.
            lastDrawn = it
            disposeUnreachable()
        }
    }

    /**
     * Replaces the overlay and returns what it published; an empty list is the
     * clear. The pixels are copied, so the caller's buffers are free for reuse
     * immediately.
     */
    fun update(patches: List<PatchPixels>): List<Placed> {
        val next = raster(patches)
        synchronized(lock) {
            if (current.isNotEmpty()) retired.addLast(current)
            current = next
            disposeUnreachable()
            return next
        }
    }

    /**
     * Rasters outside the lock, which the drawing thread takes on every read.
     * Each patch is built one at a time, so a refusal partway through leaves
     * the ones already made held by nothing at all -- the leak this class's
     * own discipline exists to avoid.
     */
    private fun raster(patches: List<PatchPixels>): List<Placed> {
        if (patches.isEmpty()) return emptyList()
        val next = mutableListOf<Placed>()
        try {
            for (p in patches) {
                // The same reason the frame holder checks: Skia reads
                // height * rowBytes and checks nothing.
                require(p.width > 0 && p.height > 0) {
                    "a patch must have positive dimensions, got ${p.width}x${p.height}"
                }
                require(p.rgba.size >= p.width.toLong() * p.height * 4) {
                    // Long in the message too: computed in Int, a hostile
                    // geometry overflows and the refusal reports a NEGATIVE
                    // byte count, which is the one thing a diagnostic must
                    // not do.
                    "a ${p.width}x${p.height} patch needs ${p.width.toLong() * p.height * 4} bytes, got ${p.rgba.size}"
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
            next.forEach { it.image.close() }
            throw t
        }
        return next
    }

    /** Must run under [lock]: it frees from the same field a read writes. */
    private fun disposeUnreachable() {
        val iterator = retired.iterator()
        while (iterator.hasNext()) {
            val generation = iterator.next()
            if (generation === lastDrawn) continue
            iterator.remove()
            generation.forEach { it.image.close() }
        }
    }

    override fun close() {
        synchronized(lock) {
            lastDrawn = null
            disposeUnreachable()
            current.forEach { it.image.close() }
            current = emptyList()
        }
    }
}
