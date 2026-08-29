package dev.hivens.skinema.skiko

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Owns the Skia image for the current video frame. [update] raster-copies the
 * RGBA pixels into a fresh image -- Skia images hold native memory, and
 * waiting for the finalizer is a leak in practice (ROADMAP.md section 6).
 *
 * ## Which thread does what
 *
 * The copy is the expensive part: eight megabytes at 1080p, four times that
 * at 4K, every frame. Done where the picture is drawn it is taken out of the
 * host's own rendering, so [update] is built to run somewhere else -- and
 * then two threads share these images, which is what the rest of this is
 * about.
 *
 * - [update] runs on whatever thread rasters. It publishes the new image and
 *   frees what neither side can still be holding.
 * - [image] is how the DRAWING thread takes the current picture.
 * - [reclaim] hands that borrow back a frame early. Call it at the start of a
 *   draw or not at all; nothing leaks either way.
 * - [close] may come from either, and shuts the door: an [update] racing it
 *   returns null rather than publishing into a closed session.
 *
 * ## One live borrow per side
 *
 * There are two ways to get an image out of this class, and both hand over
 * whichever one is current at the time:
 *
 * - what [update] returns lasts until your next [update] publishes over it,
 * - what [image] returns lasts until your next read of [image] (or a
 *   [reclaim]), because the drawing thread's most recent read is the one
 *   reference kept alive after the picture it names has been superseded.
 *
 * Everything older than those is unreachable -- no caller has a way to name
 * it again -- so the class closes it itself. That is what bounds the native
 * memory at one superseded frame without asking the caller for anything.
 *
 * The rule this leaves is to read [image] ONCE per draw and not keep the
 * result: a drawer that reads twice and holds both can have the older closed
 * under it. One read per draw is what a painter does anyway.
 *
 * Two sides means two threads, and only two. A second drawing thread reading
 * [image] takes the borrow away from the first, and two threads calling
 * [update] supersede each other's returns.
 */
class VideoFrameImage : AutoCloseable {

    private val lock = Any()

    // The current frame, and the drawing thread's borrow against an older
    // one. Both guarded by [lock] -- the whole point is that a publish and a
    // read cannot interleave, since a read landing just after the publish
    // that superseded its image would take a reference to something the same
    // publish had already freed.
    private var current: Image? = null
    private var lastDrawn: Image? = null

    // Superseded images still spoken for. Holds the drawing thread's borrow
    // and nothing else, so it is empty or holds one.
    private val retired = ArrayDeque<Image>()

    private var closed = false

    /**
     * The current frame's image, null before the first [update] and after
     * [close]. Reading it is the drawing thread's borrow: that image stays
     * alive until this is read again.
     */
    val image: Image? get() = synchronized(lock) {
        current.also {
            // Taking the next image is what makes the previous one
            // unreachable, so it is freed here rather than waiting for a
            // publish or a [reclaim] that a caller may never make.
            lastDrawn = it
            disposeUnreachable()
        }
    }

    /** Superseded images still spoken for -- one at most. */
    val pending: Int get() = synchronized(lock) { retired.size }

    /**
     * Replaces [image] with [rgba] ([width] x [height], straight alpha,
     * stride = width * 4) and returns it, or null once [close] has run. The
     * pixels are copied, so the caller's buffer is free for reuse
     * immediately.
     */
    fun update(width: Int, height: Int, rgba: ByteArray): Image? {
        // Skia reads height * rowBytes out of the array and checks nothing,
        // so a short one is a native out-of-bounds read rather than an
        // exception. The player's own frames always match; this is a public
        // module and the buffer can come from anywhere.
        require(width > 0 && height > 0) { "a frame must have positive dimensions, got ${width}x$height" }
        require(rgba.size >= width.toLong() * height * 4) {
            // Long in the message too: computed in Int, a hostile geometry
            // overflows and the refusal reports a NEGATIVE byte count, which
            // is the one thing a diagnostic must not do.
            "a ${width}x$height RGBA frame needs ${width.toLong() * height * 4} bytes, got ${rgba.size}"
        }
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        // Rastered OUTSIDE the lock, which the drawing thread now takes on
        // every read: holding it across an eight-megabyte copy would stall the
        // draw for the length of one, every frame. Nothing in the copy touches
        // this object's state, so the lock only has to cover the swap -- and a
        // close that wins the race leaves an image nobody published, closed
        // below rather than dropped on the floor.
        val next = Image.makeRaster(info, rgba, rowBytes = width * 4)
        synchronized(lock) {
            if (closed) {
                next.close()
                return null
            }
            current?.let { retired.addLast(it) }
            current = next
            disposeUnreachable()
            return next
        }
    }

    /**
     * Closes every retired image the drawing thread can no longer name.
     *
     * This is what makes the mistake impossible rather than merely visible.
     * [reclaim] used to be the only thing that closed anything, and a caller
     * who did not call it got no error, no ceiling and no signal beyond
     * process size: the queue held a strong reference, so the images were
     * neither freed nor collectable and a heap profiler showed nothing.
     * Measured on a caller that never reclaimed, at 1080p: two hundred frames
     * took resident memory from 250 MB to 1796 MB, and one reclaim returned it
     * to 245.
     *
     * What could not be closed eagerly was real -- the drawing thread may
     * still be using an image and only it knows when it is done -- but it can
     * only be using the one it took most recently. Everything before that is
     * unreachable by construction, whoever published it.
     *
     * Must run under [lock]: it decides what to free from the same field a
     * read writes.
     */
    private fun disposeUnreachable() {
        val iterator = retired.iterator()
        while (iterator.hasNext()) {
            val image = iterator.next()
            if (image === lastDrawn) continue
            iterator.remove()
            image.close()
        }
    }

    /**
     * Gives up the drawing thread's borrow: the image it last took is closed
     * now rather than when it takes the next one. From the DRAWING thread, at
     * the start of a draw.
     *
     * Optional, and it is no longer a correctness requirement -- the class
     * bounds itself without it. What it still buys is one frame of native
     * memory held for one frame less, which is worth having in a drawer that
     * knows exactly where its draw begins. The Compose surface calls it for
     * that reason.
     *
     * At the START of a draw, not inside one: it says the previous draw is
     * finished, and a caller that reads [image] first and reclaims afterwards
     * has told the class its own frame is past.
     */
    fun reclaim() {
        synchronized(lock) {
            lastDrawn = null
            disposeUnreachable()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lastDrawn = null
            disposeUnreachable()
            current?.close()
            current = null
        }
    }
}
