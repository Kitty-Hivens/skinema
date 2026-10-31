package dev.hivens.skinema.player

import android.graphics.Bitmap
import android.media.Image
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One frame lent out by [VideoPlayer.acquireFrameBitmap]: a hardware [bitmap]
 * over the image the GPU drew, and the media time it was shown at. Closing it
 * gives the image back to the player; until then the player cannot reuse it.
 * Idempotent.
 */
class FrameBitmap internal constructor(
    private val image: Image,
    val bitmap: Bitmap,
    val ptsNanos: Long,
    private val giveBack: (Image) -> Unit,
) : AutoCloseable {

    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    private val closed = AtomicBoolean(false)

    /** RGBA8888 of this frame, tightly packed, copied out of the image on the CPU. */
    fun copyPixels(): ByteArray {
        val plane = image.planes[0]
        val rowBytes = image.width * 4
        val out = ByteArray(rowBytes * image.height)
        val buffer = plane.buffer.duplicate()
        for (row in 0 until image.height) {
            buffer.position(row * plane.rowStride)
            buffer.get(out, row * rowBytes, rowBytes)
        }
        return out
    }

    override fun close() {
        // Not recycled: the platform draws a recorded frame on its render
        // thread after the code that recorded it has moved on, and a bitmap
        // freed by hand can go while that draw still reads it. The collector
        // frees it once nothing refers to it.
        if (closed.compareAndSet(false, true)) giveBack(image)
    }
}
