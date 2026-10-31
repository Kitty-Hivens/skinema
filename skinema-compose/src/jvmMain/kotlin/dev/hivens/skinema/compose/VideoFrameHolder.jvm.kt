package dev.hivens.skinema.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.skiko.SubtitleOverlayImage
import dev.hivens.skinema.skiko.VideoFrameImage
import dev.hivens.skinema.subtitles.SubtitleOverlay
import org.jetbrains.skia.Image
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Rect as SkiaRect

private fun Rect.toSkia(): SkiaRect = SkiaRect.makeLTRB(left, top, right, bottom)

private class SkiaFrame(private val image: Image) : DrawableFrame {
    override val width: Int get() = image.width
    override val height: Int get() = image.height

    override fun DrawScope.drawInto(dst: Rect) {
        drawIntoCanvas { canvas ->
            canvas.skiaCanvas.drawImageRect(
                image,
                SkiaRect.makeWH(image.width.toFloat(), image.height.toFloat()),
                dst.toSkia(),
                SamplingMode.LINEAR,
                null,
                true,
            )
        }
    }
}

/** The desktop's holder: [VideoFrameImage], whose borrow rule is the one described on the expect. */
actual class VideoFrameHolder actual constructor() : AutoCloseable {

    private val frames = VideoFrameImage()

    // The slot the last update copied from. Its array is the player's until
    // the next acquireFrame, which only this same thread makes.
    @Volatile
    private var last: VideoPlayer.FrameSlot? = null

    actual fun update(player: VideoPlayer): Boolean {
        val slot = player.acquireFrame() ?: return false
        last = slot
        return frames.update(slot.width, slot.height, slot.rgba) != null
    }

    actual fun frameForDraw(): DrawableFrame? {
        frames.reclaim()
        return frames.image?.let(::SkiaFrame)
    }

    actual fun copyPixels(): RgbaPixels? {
        val slot = last ?: return null
        return RgbaPixels(slot.width, slot.height, slot.rgba.copyOf(slot.width * slot.height * 4))
    }

    actual override fun close() {
        last = null
        frames.close()
    }
}

internal actual class SubtitleOverlayHolder actual constructor() : AutoCloseable {

    private val images = SubtitleOverlayImage()

    actual fun update(overlay: SubtitleOverlay) {
        images.update(overlay.patches.map { SubtitleOverlayImage.PatchPixels(it.x, it.y, it.width, it.height, it.rgba) })
    }

    actual fun clear() {
        images.update(emptyList())
    }

    actual fun DrawScope.drawPatches(target: Rect, canvasWidth: Int, canvasHeight: Int) {
        drawIntoCanvas { canvas ->
            for (placed in images.images) {
                canvas.skiaCanvas.drawImageRect(
                    placed.image,
                    SkiaRect.makeWH(placed.image.width.toFloat(), placed.image.height.toFloat()),
                    subtitleDrawRect(target, canvasWidth, canvasHeight, placed.x, placed.y, placed.image.width, placed.image.height).toSkia(),
                    SamplingMode.LINEAR,
                    null,
                    true,
                )
            }
        }
    }

    actual override fun close() = images.close()
}
