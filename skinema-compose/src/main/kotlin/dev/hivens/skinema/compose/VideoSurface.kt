package dev.hivens.skinema.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.skiko.SubtitleOverlayImage
import dev.hivens.skinema.skiko.VideoFrameImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.roundToInt

/** How the video maps onto the surface's bounds. */
enum class VideoScale {
    /** Fill the bounds completely, cropping overflow -- backgrounds. */
    Cover,

    /** Fit entirely inside the bounds, letterboxing -- previews. */
    Fit,
}

/**
 * Draws [player]'s frames, repainting on the Compose frame clock: each
 * UI frame polls [VideoPlayer.acquireFrame], so a hidden window stops the
 * pump for free while the player keeps its latest-frame mailbox warm.
 *
 * The surface draws pixels and nothing else -- no spinners, no error
 * states. Watch [VideoPlayer.state] and react outside; before the first
 * frame (and on [VideoPlayer.State.Failed]) the surface simply draws
 * nothing, leaving whatever is composed behind it visible.
 */
@Composable
fun VideoSurface(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    scale: VideoScale = VideoScale.Cover,
) {
    val frames = remember(player) { VideoFrameImage() }
    val subtitles = remember(player) { SubtitleOverlayImage() }
    var frameStamp by remember(player) { mutableLongStateOf(0L) }
    var subtitleCanvas by remember(player) { mutableStateOf(0 to 0) }

    DisposableEffect(player) {
        onDispose {
            frames.close()
            subtitles.close()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { }
            player.acquireFrame()?.let { slot ->
                frames.update(slot.width, slot.height, slot.rgba)
                frameStamp++
            }
            player.acquireSubtitles()?.let { overlay ->
                subtitles.update(
                    overlay.patches.map {
                        SubtitleOverlayImage.PatchPixels(it.x, it.y, it.width, it.height, it.rgba)
                    },
                )
                subtitleCanvas = overlay.canvasWidth to overlay.canvasHeight
                frameStamp++
            }
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION")
        frameStamp // snapshot read: a new frame invalidates this draw scope
        val image = frames.image ?: return@Canvas
        // Phone footage arrives sideways with its orientation as metadata;
        // scaling decisions follow what the viewer SEES, so quarter turns
        // swap the dimensions before Cover/Fit does its math.
        val rotation = player.rotationDegrees
        val (displayedW, displayedH) = displayedSize(image.width.toFloat(), image.height.toFloat(), rotation)
        val dst = destinationRect(
            srcWidth = displayedW,
            srcHeight = displayedH,
            boundsWidth = size.width,
            boundsHeight = size.height,
            scale = scale,
        )
        drawIntoCanvas { canvas ->
            val nc = canvas.skiaCanvas
            nc.save()
            // Cover overflows the bounds by design; never paint outside them.
            nc.clipRect(Rect.makeWH(size.width, size.height))
            if (rotation != 0) {
                nc.save()
                nc.rotate(rotation.toFloat(), (dst.left + dst.right) / 2f, (dst.top + dst.bottom) / 2f)
            }
            nc.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                imageDrawRect(dst, rotation),
                SamplingMode.LINEAR,
                null,
                true,
            )
            if (rotation != 0) nc.restore()
            // Subtitles composite upright in DISPLAYED space, over the
            // (possibly rotated) video, mapped from their canvas onto
            // the same destination rect.
            if (player.activeSubtitleTrack != null) {
                val (canvasW, canvasH) = subtitleCanvas
                for (placed in subtitles.images) {
                    nc.drawImageRect(
                        placed.image,
                        Rect.makeWH(placed.image.width.toFloat(), placed.image.height.toFloat()),
                        subtitleDrawRect(
                            dst, canvasW, canvasH,
                            placed.x, placed.y, placed.image.width, placed.image.height,
                        ),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    )
                }
            }
            nc.restore()
        }
        // The pipeline rasterizes text at whatever size the surface
        // reports; posting the displayed rect keeps glyphs crisp at any
        // window size. Cheap and idempotent -- a command only when the
        // size actually changed.
        if (player.activeSubtitleTrack != null) {
            player.setSubtitleCanvasSize(dst.width.roundToInt(), dst.height.roundToInt())
        }
    }
}

/**
 * Where one overlay patch lands on screen: its canvas maps uniformly
 * onto the video's destination rect. Pure -- tested without a renderer.
 */
internal fun subtitleDrawRect(
    dst: Rect,
    canvasWidth: Int,
    canvasHeight: Int,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
): Rect {
    if (canvasWidth <= 0 || canvasHeight <= 0) return Rect.makeWH(0f, 0f)
    val scaleX = dst.width / canvasWidth
    val scaleY = dst.height / canvasHeight
    return Rect.makeXYWH(
        dst.left + x * scaleX,
        dst.top + y * scaleY,
        width * scaleX,
        height * scaleY,
    )
}

/** The source dimensions as the viewer sees them after rotation. */
internal fun displayedSize(width: Float, height: Float, rotationDegrees: Int): Pair<Float, Float> =
    if (rotationDegrees % 180 == 0) width to height else height to width

/**
 * The rect to hand the canvas while it is rotated about [dst]'s center:
 * for quarter turns the image's natural orientation is the displayed
 * rect with its sides swapped around the same center.
 */
internal fun imageDrawRect(dst: Rect, rotationDegrees: Int): Rect {
    if (rotationDegrees % 180 == 0) return dst
    val centerX = (dst.left + dst.right) / 2f
    val centerY = (dst.top + dst.bottom) / 2f
    val halfWidth = dst.height / 2f
    val halfHeight = dst.width / 2f
    return Rect.makeLTRB(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
}

/**
 * Where the video lands inside the bounds: uniformly scaled, centered,
 * cropping (Cover) or letterboxing (Fit). Pure -- tested without a
 * renderer.
 */
internal fun destinationRect(
    srcWidth: Float,
    srcHeight: Float,
    boundsWidth: Float,
    boundsHeight: Float,
    scale: VideoScale,
): Rect {
    val factor = when (scale) {
        VideoScale.Cover -> maxOf(boundsWidth / srcWidth, boundsHeight / srcHeight)
        VideoScale.Fit -> minOf(boundsWidth / srcWidth, boundsHeight / srcHeight)
    }
    val width = srcWidth * factor
    val height = srcHeight * factor
    val left = (boundsWidth - width) / 2f
    val top = (boundsHeight - height) / 2f
    return Rect.makeXYWH(left, top, width, height)
}
