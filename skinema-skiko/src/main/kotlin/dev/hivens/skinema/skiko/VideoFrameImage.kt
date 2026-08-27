package dev.hivens.skinema.skiko

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.ConcurrentLinkedQueue

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
 *   RETIRES the old one rather than closing it: the drawing thread may still
 *   be holding it.
 * - [reclaim] closes what has been retired and must be called from the
 *   DRAWING thread, at the start of a draw. An image retired while a draw is
 *   running is closed at the start of the next one, by which time that draw
 *   has finished with it -- and since only the drawing thread closes, and
 *   only between draws, nothing is ever freed under a draw that is using it.
 * - [close] may come from either, and shuts the door: an [update] racing it
 *   returns null rather than rastering into a closed session.
 */
class VideoFrameImage : AutoCloseable {

    private companion object {
        /**
         * How many retired images it takes to be sure, given that [reclaim] has
         * never run at all. Not a frame rate and not a duration: a caller who
         * never reclaims passes any number, and one who does is not judged by
         * this at all.
         */
        const val BACKLOG_WARN_AT = 60
    }

    private val lock = Any()

    /** The current frame's image; null before the first [update]. */
    @Volatile
    var image: Image? = null
        private set

    // Images the drawing thread has not been past yet. Never closed here.
    private val retired = ConcurrentLinkedQueue<Image>()

    /** Images published but not yet reclaimed -- the drawer's backlog. */
    val pending: Int get() = retired.size

    private var closed = false

    @Volatile
    private var backlogSaid = false

    @Volatile
    private var everReclaimed = false

    /** Whether the backlog warning has been printed. For tests. */
    internal val warnedAboutBacklog: Boolean get() = backlogSaid


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
            "a ${width}x$height RGBA frame needs ${width * height * 4} bytes, got ${rgba.size}"
        }
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        // The copy runs under the lock, which is what makes a close racing it
        // safe: closing waits for the raster in flight rather than freeing the
        // session under it. The lock is uncontended in the steady state --
        // only a teardown ever asks for it.
        synchronized(lock) {
            if (closed) return null
            val next = Image.makeRaster(info, rgba, rowBytes = width * 4)
            image?.let { retired.add(it) }
            image = next
            warnIfBacklogging()
            return next
        }
    }

    /**
     * Says something, once, when the backlog has clearly stopped being drained.
     *
     * [reclaim] is half of this class's contract and the half that is easy to
     * leave out: [update] on its own looks like it works, because the picture
     * is right. What it costs is invisible to a heap profiler and to the
     * collector alike -- the queue holds a strong reference, so the images are
     * neither freed nor reclaimable, and a consumer sees only that the process
     * is large. Measured on a caller that never reclaimed: two hundred frames
     * of 1080p took resident memory from 250 MB to 1796 MB, and one reclaim
     * put it back to 245.
     *
     * So the silence is the defect worth fixing. The condition is that
     * [reclaim] has NEVER run, which is what the mistake actually is -- and it
     * deliberately does not measure a backlog against a frame rate. A correct
     * drawer does fall behind: a hitch, a resize, a collection pause, and at
     * 240 fps a quarter of a second of that is sixty images. Warning on the
     * count alone would fire at exactly the callers doing it right, and a
     * warning that fires on correct code is worse than none. A caller who
     * never reclaims, meanwhile, passes any threshold at any frame rate, so
     * the number below only decides how soon.
     *
     * Printed unconditionally rather than behind SKINEMA_DEBUG, because the
     * person who needs it is exactly the one who does not know to turn a flag
     * on. Someone watching for the other case -- reclaiming but not keeping up
     * -- has [pending] for it.
     *
     * It warns and does nothing else. Closing the backlog from here would free
     * an image the drawing thread may be holding, which is the crash this
     * class's whole retire-then-reclaim shape exists to avoid.
     */
    private fun warnIfBacklogging() {
        if (backlogSaid || everReclaimed || retired.size < BACKLOG_WARN_AT) return
        backlogSaid = true
        System.err.println(
            "[skinema] $BACKLOG_WARN_AT frames are retired and unreclaimed. VideoFrameImage.reclaim() " +
                "must be called from the drawing thread at the start of each draw, or every frame's " +
                "native memory is held until close().",
        )
    }

    /** Closes retired images. The drawing thread only, at the start of a draw. */
    fun reclaim() {
        everReclaimed = true
        while (true) (retired.poll() ?: return).close()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            reclaim()
            image?.close()
            image = null
        }
    }
}
