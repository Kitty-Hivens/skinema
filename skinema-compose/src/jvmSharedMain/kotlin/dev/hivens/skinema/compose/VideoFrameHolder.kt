package dev.hivens.skinema.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.subtitles.SubtitleOverlay

/**
 * One frame ready to draw: its stored size and a way to put it in a rect.
 * Only valid on the drawing thread, during the draw that took it.
 */
interface DrawableFrame {
    val width: Int
    val height: Int

    /** Draws the whole frame into [dst], under whatever transform the scope carries. */
    fun DrawScope.drawInto(dst: Rect)
}

/** A frame's pixels on the CPU: RGBA8888, straight alpha, tightly packed. */
class RgbaPixels(val width: Int, val height: Int, val rgba: ByteArray)

/**
 * A player's frames, held for drawing in Compose: what [VideoSurface] draws
 * through, and what a consumer drawing frames its own way (a [androidx.compose.ui.graphics.painter.Painter]
 * of its own, say) uses instead of reaching for a platform type.
 *
 * Two sides, and the split is the point. [update] takes the player's freshest
 * frame, from whatever thread pumps it. [frameForDraw] hands the drawing
 * thread the frame to draw. A frame the drawing side may still be using is
 * never freed under it: the holder frees a superseded frame only once the
 * draws after it have taken a newer one. On the desktop a frame is a Skia
 * image copied from the player's RGBA; on Android it is the hardware image
 * the GPU drew, wrapped with no copy.
 *
 * One holder per player, as one surface per player: the player's mailbox has
 * one reader.
 */
expect class VideoFrameHolder() : AutoCloseable {

    /** Takes [player]'s freshest frame; true when there was one newer than the last. */
    fun update(player: VideoPlayer): Boolean

    /** The frame to draw now, or null before the first. On the drawing thread. */
    fun frameForDraw(): DrawableFrame?

    /**
     * The pixels of the frame the last [update] took, copied out, or null
     * before the first. Call it from the thread that calls [update]: that
     * thread is what decides when a frame is superseded.
     */
    fun copyPixels(): RgbaPixels?

    /** Frees every frame held. A teardown: nothing is updated or drawn through the holder after it. */
    override fun close()
}

/** The subtitle overlay, held for drawing the way [VideoFrameHolder] holds frames. */
internal expect class SubtitleOverlayHolder() : AutoCloseable {

    /** Takes [overlay]'s patches. An overlay with none is a clear. */
    fun update(overlay: SubtitleOverlay)

    /** Drops what is shown, keeping the drawing side's borrow rule. */
    fun clear()

    /** Draws the patches, mapping the overlay's canvas onto [target]. */
    fun DrawScope.drawPatches(target: Rect, canvasWidth: Int, canvasHeight: Int)

    override fun close()
}
