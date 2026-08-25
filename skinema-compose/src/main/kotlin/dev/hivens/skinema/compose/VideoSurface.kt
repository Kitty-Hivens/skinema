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
import kotlinx.coroutines.CancellationException
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.skiko.SubtitleOverlayImage
import dev.hivens.skinema.skiko.VideoFrameImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.util.WeakHashMap
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
 * UI frame polls [VideoPlayer.acquireFrame], so a hidden window stops
 * polling for free -- Compose runs it no frame clock. The player goes on
 * decoding and pacing into its latest-wins mailbox regardless.
 *
 * The surface draws pixels and nothing else -- no spinners, no error
 * states. Watch [VideoPlayer.state] and react outside; before the first
 * frame (and on [VideoPlayer.State.Failed]) the surface simply draws
 * nothing, leaving whatever is composed behind it visible.
 *
 * ONE SURFACE PER PLAYER. The mailbox hands each published frame to one
 * reader -- that is what makes the handoff copy-free -- so two surfaces on
 * one player take turns rather than both seeing everything: each draws part
 * of the frames, neither draws them all, and the two show different pictures.
 * Nothing fails, which is why it reads as choppy video rather than as a
 * mistake, so the second surface says so on stderr. Two views of one file
 * means two players.
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
    // Snapshot state rather than a read in the draw scope, because a failure
    // publishes no frame: nothing would invalidate the draw, and the picture
    // this surface promises to drop would stay on screen until something
    // else recomposed it.
    var failed by remember(player) { mutableStateOf(false) }
    // The size last posted to the player, held outside Compose state on
    // purpose: it is written from the draw scope, and a snapshot write there
    // would invalidate the very frame writing it.
    val postedCanvas = remember(player) { intArrayOf(-1, -1) }

    DisposableEffect(player) {
        if (SurfaceRegistry.add(player)) {
            System.err.println(
                "skinema: a second VideoSurface is drawing one player. The player's mailbox has a single " +
                    "reader, so the surfaces take turns -- each draws part of the frames and neither draws " +
                    "them all. Give each surface its own player.",
            )
        }
        onDispose {
            SurfaceRegistry.remove(player)
            frames.close()
            subtitles.close()
        }
    }
    LaunchedEffect(player) {
        var subtitled = player.activeSubtitleTrack != null
        while (true) {
            withFrameNanos { }
            val state = player.state
            try {
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // Both updates raster-copy into native memory -- 8 MB a frame
                // at 1080p, four times that at 4K -- and Skia answers a
                // refusal by throwing. Left to travel, that throw goes into
                // the Recomposer's effect job and cancels every LaunchedEffect
                // in the composition, not only this surface's, while the
                // player it came from still reports itself Playing. Stop
                // drawing the way a failed player does and leave the rest of
                // the UI alone.
                // Said out loud rather than traced behind a flag: the
                // player's own state is not Failed here, so nothing else in
                // the API can tell the consumer why the picture stopped, and
                // an uncaught throw would have printed anyway.
                System.err.println("skinema: the video surface could not raster a frame: $t")
                failed = true
                frames.close()
                subtitles.close()
                return@LaunchedEffect
            }
            // A track turned off publishes nothing, so nothing invalidates
            // the draw and the last cue stays painted -- on a paused player
            // indefinitely, which is exactly where a viewer toggles them.
            val nowSubtitled = player.activeSubtitleTrack != null
            if (nowSubtitled != subtitled) {
                subtitled = nowSubtitled
                if (!nowSubtitled) subtitles.close()
                frameStamp++
            }
            val nowFailed = state is VideoPlayer.State.Failed
            if (nowFailed != failed) {
                failed = nowFailed
                // The draw stops at the failure; the frame it was holding
                // does not have to stay in native memory behind it.
                if (nowFailed) frames.close()
            }
            // A closed player publishes nothing ever again, so the frame
            // clock has nothing left to poll for. A paused one still can --
            // a seek landing, a frame step -- so it keeps its loop.
            if (state is VideoPlayer.State.Closed) return@LaunchedEffect
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION")
        frameStamp // snapshot read: a new frame invalidates this draw scope
        // The documented contract, which the draw did not keep: a failed
        // player draws nothing, so the fallback composed behind this surface
        // is what the viewer sees. A failure publishes no frame, so the last
        // one stayed painted -- and a consumer drawing its fallback anywhere
        // but on top of the surface never got to show it.
        if (failed) return@Canvas
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
        // The rect the video draws into BEFORE the rotation transform --
        // the storage orientation. Equals dst for upright video.
        val imageRect = imageDrawRect(dst, rotation)
        // And the part of it a viewer can see. Under Cover the video rect is
        // deliberately larger than the bounds, and text laid out in it was
        // laid out partly outside them: a portrait clip in a square surface
        // put a bottom-anchored line a hundred pixels below the edge, so the
        // viewer turned subtitles on and saw nothing at all over a picture
        // that was plainly running.
        val subtitleRect = visibleRect(imageRect, size.width, size.height, rotation)
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
                imageRect,
                SamplingMode.LINEAR,
                null,
                true,
            )
            // Subtitles live in the video's own coordinate space: drawn
            // inside the SAME rotation transform and mapped onto the
            // pre-rotation rect, so positioned ASS and bitmap planes stay
            // glued to the picture rather than compositing upright over a
            // rotated frame. Upright video (the common case) leaves
            // imageRect == dst and the transform a no-op -- nothing moves.
            if (player.activeSubtitleTrack != null) {
                val (canvasW, canvasH) = subtitleCanvas
                for (placed in subtitles.images) {
                    nc.drawImageRect(
                        placed.image,
                        Rect.makeWH(placed.image.width.toFloat(), placed.image.height.toFloat()),
                        subtitleDrawRect(
                            subtitleRect, canvasW, canvasH,
                            placed.x, placed.y, placed.image.width, placed.image.height,
                        ),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    )
                }
            }
            if (rotation != 0) nc.restore()
            nc.restore()
        }
        // The pipeline rasterizes text at whatever size the surface
        // reports; posting the pre-rotation (storage-oriented) rect keeps
        // the libass frame aspect matched to the video and glyphs crisp at
        // any window size.
        //
        // Only when it changes, and the comparison belongs here. It used to
        // be described as idempotent and was not: the call queues a command
        // unconditionally and the size is compared on the subtitle thread,
        // after that thread has already been woken to read it. This draw
        // scope runs on every painted frame, so a steady window posted sixty
        // commands a second onto an unbounded queue -- which the subtitle
        // pump treats as work pending, so it refilled a packet at a time and
        // never reached its own render cadence.
        if (player.activeSubtitleTrack != null) {
            val postW = subtitleRect.width.roundToInt()
            val postH = subtitleRect.height.roundToInt()
            if (postW != postedCanvas[0] || postH != postedCanvas[1]) {
                postedCanvas[0] = postW
                postedCanvas[1] = postH
                player.setSubtitleCanvasSize(postW, postH)
            }
        }
    }
}

/**
 * Which players already have a surface drawing them.
 *
 * Exists to name a silent failure. Two surfaces on one player is a mistake
 * with no symptom of its own: the mailbox hands a published frame to whoever
 * polls first, so the two split the stream between them and both look merely
 * slow. Weak keys, because a player outlives nothing here -- an entry left
 * behind by a surface that was never disposed must not hold one alive.
 */
internal object SurfaceRegistry {

    private val counts = WeakHashMap<VideoPlayer, Int>()

    /** Adds a surface for [player]; true when it is not the first. */
    @Synchronized
    fun add(player: VideoPlayer): Boolean {
        val now = (counts[player] ?: 0) + 1
        counts[player] = now
        return now > 1
    }

    @Synchronized
    fun remove(player: VideoPlayer) {
        val now = (counts[player] ?: 1) - 1
        if (now <= 0) counts.remove(player) else counts[player] = now
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
 * The part of the video's rect a viewer can actually see: what the
 * subtitles are laid out in and mapped onto.
 *
 * Under [VideoScale.Fit] the video rect is already inside the bounds and
 * this changes nothing. Under [VideoScale.Cover] it deliberately overflows
 * them, and laying text out in the whole of it put lines outside the clip
 * -- invisible, and rasterized at full size to be thrown away.
 *
 * The intersection is taken in the video's PRE-rotation space, because
 * that is where the subtitles are placed while the clip is in screen
 * space: a quarter turn swaps the bounds' sides about the same centre the
 * rotation turns around. Pure -- tested without a renderer.
 */
internal fun visibleRect(
    imageRect: Rect,
    boundsWidth: Float,
    boundsHeight: Float,
    rotationDegrees: Int,
): Rect {
    val centerX = (imageRect.left + imageRect.right) / 2f
    val centerY = (imageRect.top + imageRect.bottom) / 2f
    val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
    val halfWidth = (if (quarterTurn) boundsHeight else boundsWidth) / 2f
    val halfHeight = (if (quarterTurn) boundsWidth else boundsHeight) / 2f
    val left = maxOf(imageRect.left, centerX - halfWidth)
    val top = maxOf(imageRect.top, centerY - halfHeight)
    val right = minOf(imageRect.right, centerX + halfWidth)
    val bottom = minOf(imageRect.bottom, centerY + halfHeight)
    // A degenerate overlap means the bounds carry no video at all; there is
    // nothing better to lay text out in than the rect itself.
    if (right <= left || bottom <= top) return imageRect
    return Rect.makeLTRB(left, top, right, bottom)
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
