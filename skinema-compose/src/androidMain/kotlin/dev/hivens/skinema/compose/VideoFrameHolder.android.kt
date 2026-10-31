package dev.hivens.skinema.compose

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.hivens.skinema.player.FrameBitmap
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.subtitles.SubtitleOverlay
import java.nio.ByteBuffer
import kotlin.math.roundToInt

private fun DrawScope.drawBitmap(bitmap: Bitmap, dst: Rect) {
    drawImage(
        image = bitmap.asImageBitmap(),
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
        dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
    )
}

private class HardwareFrame(private val frame: FrameBitmap) : DrawableFrame {
    override val width: Int get() = frame.width
    override val height: Int get() = frame.height

    override fun DrawScope.drawInto(dst: Rect) = drawBitmap(frame.bitmap, dst)
}

/**
 * Android's holder: frames lent by [VideoPlayer.acquireFrameBitmap], drawn as
 * hardware bitmaps with no copy.
 *
 * A frame is given back two draws after the one that last used it, not one.
 * The platform's render thread draws a recorded frame after the code that
 * recorded it has moved on, so the frame the previous draw used can still be
 * on the GPU when the next draw is recorded; giving its image back then would
 * let the player draw the next picture into a buffer still being shown.
 */
actual class VideoFrameHolder actual constructor() : AutoCloseable {

    private val lock = Any()
    private var current: FrameBitmap? = null
    private var drawn: FrameBitmap? = null
    private var drawnBefore: FrameBitmap? = null

    actual fun update(player: VideoPlayer): Boolean {
        val next = player.acquireFrameBitmap() ?: return false
        synchronized(lock) {
            val old = current
            current = next
            if (old != null && old !== drawn && old !== drawnBefore) old.close()
        }
        return true
    }

    actual fun frameForDraw(): DrawableFrame? = synchronized(lock) {
        val frame = current ?: return null
        if (frame !== drawn) {
            drawnBefore?.takeIf { it !== current && it !== drawn }?.close()
            drawnBefore = drawn
            drawn = frame
        }
        HardwareFrame(frame)
    }

    actual fun copyPixels(): RgbaPixels? = synchronized(lock) {
        current?.let { RgbaPixels(it.width, it.height, it.copyPixels()) }
    }

    actual override fun close() = synchronized(lock) {
        listOfNotNull(current, drawn, drawnBefore).distinct().forEach(FrameBitmap::close)
        current = null
        drawn = null
        drawnBefore = null
    }
}

/**
 * Overlay patches as bitmaps. The patch bytes are premultiplied RGBA, which is
 * exactly the memory layout of an ARGB_8888 bitmap, so each is copied in as
 * it is. Bitmaps are left to the collector rather than recycled, for the
 * render-thread reason [VideoFrameHolder] gives.
 */
internal actual class SubtitleOverlayHolder actual constructor() : AutoCloseable {

    private class Placed(val x: Int, val y: Int, val bitmap: Bitmap)

    @Volatile
    private var placed: List<Placed> = emptyList()

    actual fun update(overlay: SubtitleOverlay) {
        placed = overlay.patches.filter { it.width > 0 && it.height > 0 }.map { patch ->
            val bitmap = Bitmap.createBitmap(patch.width, patch.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(patch.rgba, 0, patch.width * patch.height * 4))
            Placed(patch.x, patch.y, bitmap)
        }
    }

    actual fun clear() {
        placed = emptyList()
    }

    actual fun DrawScope.drawPatches(target: Rect, canvasWidth: Int, canvasHeight: Int) {
        for (p in placed) {
            drawBitmap(p.bitmap, subtitleDrawRect(target, canvasWidth, canvasHeight, p.x, p.y, p.bitmap.width, p.bitmap.height))
        }
    }

    actual override fun close() = clear()
}
