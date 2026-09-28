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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.skiko.SubtitleOverlayImage
import dev.hivens.skinema.skiko.VideoFrameImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.util.WeakHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * How long one wait on [VideoPlayer.awaitChange] lasts before it is taken
 * again. Only a bound on how long a waiting thread is held at a stretch: a
 * change ends the wait at once, and leaving the composition interrupts it.
 */
internal const val CHANGE_WAIT_NANOS = 1_000_000_000L

/**
 * Where the waits on [VideoPlayer.awaitChange] block.
 *
 * Each surface and each state helper holds a thread there for as long as it
 * lives, so on [Dispatchers.IO] itself they would count against its 64: a
 * few dozen player cells would fill it, and the application's own I/O would
 * queue behind threads that are only waiting. A view of it takes threads
 * from the same pool without counting against that limit, and bounds only
 * this.
 */
internal val changeWaits = Dispatchers.IO.limitedParallelism(1024, "skinema-change-waits")

/** How the video maps onto the surface's bounds. */
enum class VideoScale {
    /** Fill the bounds completely, cropping overflow -- backgrounds. */
    Cover,

    /** Fit entirely inside the bounds, letterboxing -- previews. */
    Fit,
}

/**
 * Draws [player]'s frames, repainting when the player has something new and
 * at no other time: a 24 fps file draws twenty-four times a second on any
 * display, where polling on every refresh drew ten times for each of those
 * on a 240 Hz one. What wakes it is [VideoPlayer.awaitChange].
 *
 * The frame clock still has one job, and it is the one that matters when the
 * window is hidden. Each picture is taken from the mailbox only after the
 * window has drawn the one before it, so a window that stops drawing stops the
 * surface taking pictures, and the player notices the mailbox going unread and
 * stops decoding for it, on the policy its
 * [dev.hivens.skinema.player.WhenUnwatched] names.
 *
 * Whether a window the viewer cannot see stops drawing is the platform's
 * call, and not every platform stops. Measured, an XWayland window on a
 * Hyprland workspace that is not on screen still draws about once a second.
 * Read off Skiko's Metal renderer rather than measured, a macOS window behind
 * others keeps drawing a few times a second. At those rates
 * the surface reads often enough that the player does not notice, which the
 * polling surface this replaced did not either. A consumer that knows when
 * its picture is out of sight says so with [VideoPlayer.setPresenting], which
 * is exact on every platform.
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
    /**
     * Painted over the bounds before the picture, so [VideoScale.Fit]'s bars
     * are this colour instead of whatever is composed behind the surface.
     * Null (the default) keeps the surface drawing pixels and nothing else.
     *
     * Only ever painted together with a frame: before the first one, and on a
     * failed player, the surface still draws nothing at all -- a consumer's
     * own fallback has to be able to show through, and a background that
     * appeared first would cover it.
     */
    background: Color? = null,
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
    // The raster's side of the seam. A permit per picture to take, a stamp back
    // when one has been made into an image, whether one is in flight, and the
    // throw that stops it -- Compose state is written on the composition
    // thread only, so what crosses the seam is all plain.
    val ticks = remember(player) { Semaphore(0) }
    val rasterStamp = remember(player) { AtomicLong(0L) }
    val rasterFailure = remember(player) { AtomicReference<Throwable?>(null) }
    val rasterInFlight = remember(player) { AtomicBoolean(false) }
    // What wakes the loop below: the player changing, and a raster finishing.
    // Conflated, because the loop looks at everything on each pass and two
    // wakes in a row mean no more than one.
    val wakes = remember(player) { Channel<Unit>(Channel.CONFLATED) }

    DisposableEffect(player) {
        if (SurfaceRegistry.add(player)) {
            System.err.println(
                "skinema: a second VideoSurface is drawing one player. The player's mailbox has a single " +
                    "reader, so the surfaces take turns -- each draws part of the frames and neither draws " +
                    "them all. Give each surface its own player.",
            )
        }
        // Eight megabytes at 1080p, four times that at 4K, every frame: the
        // raster copy is real work, and done on the composition thread it is
        // taken straight out of the host's own rendering. It runs here
        // instead, one picture per permit, and the loop below hands out a
        // permit only once the window has drawn the last picture. That keeps
        // the player's notice of an unwatched mailbox tied to the window
        // actually rendering rather than to a loop of this thread's own.
        val stop = AtomicBoolean(false)
        val rasteriser = Thread({
            while (!stop.get()) {
                try {
                    ticks.acquire()
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (stop.get()) return@Thread
                // Nothing here bounds the images: VideoFrameImage keeps one
                // superseded frame, the drawing thread's own, and frees the
                // rest as it publishes. What paces this thread is the permit.
                try {
                    val slot = player.acquireFrame()
                    if (slot != null && frames.update(slot.width, slot.height, slot.rgba) != null) {
                        rasterStamp.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    rasterFailure.set(t)
                    return@Thread
                } finally {
                    // Always, the empty take included: a frame published while
                    // this one was in flight is waiting for the next permit,
                    // and only the loop can give it.
                    rasterInFlight.set(false)
                    wakes.trySend(Unit)
                }
            }
        }, "skinema-raster").apply {
            isDaemon = true
            start()
        }
        onDispose {
            SurfaceRegistry.remove(player)
            // Joined before anything it touches is closed, so the teardown
            // cannot free a session out from under a raster in flight.
            stop.set(true)
            ticks.release()
            rasteriser.join(1_000)
            frames.close()
            subtitles.close()
        }
    }
    LaunchedEffect(player) {
        var subtitled = player.activeSubtitleTrack != null
        // The raster the draw scope was last handed.
        var presentedRaster = 0L
        // The change count a permit was last given against. A permit is only
        // worth giving when the player has moved since, or when the window has
        // just drawn. Without the rule, every empty take would wake this loop
        // into giving the next one.
        var permittedAt = Long.MIN_VALUE
        // Blocking, so it waits on its own threads rather than on this one, and
        // interruptible, so leaving the composition cancels the wait. The
        // reading is taken here rather than inside the coroutine, which starts
        // whenever its dispatcher gets to it: a change landing before that
        // would become its starting point and never be reported.
        var seen = player.changeCount
        val forwarder = launch {
            while (true) {
                val now = runInterruptible(changeWaits) { player.awaitChange(seen, CHANGE_WAIT_NANOS) }
                if (now != seen) {
                    seen = now
                    wakes.trySend(Unit)
                }
            }
        }
        wakes.trySend(Unit)
        try {
            while (true) {
                wakes.receive()
                val state = player.state
                val changes = player.changeCount
                var invalidated = false
                val rastered = rasterStamp.get()
                if (rastered != presentedRaster) {
                    presentedRaster = rastered
                    frameStamp++
                    invalidated = true
                }
                try {
                    rasterFailure.get()?.let { throw it }
                    player.acquireSubtitles()?.let { overlay ->
                        subtitles.update(
                            overlay.patches.map {
                                SubtitleOverlayImage.PatchPixels(it.x, it.y, it.width, it.height, it.rgba)
                            },
                        )
                        subtitleCanvas = overlay.canvasWidth to overlay.canvasHeight
                        frameStamp++
                        invalidated = true
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
                    // Cleared rather than closed. Both drop the pixels, and only
                    // one of them keeps the holder's borrow rule: close() frees
                    // the generation the drawing thread read last as well, which
                    // is a teardown's privilege and not a deselect's. The two
                    // close() calls that remain are teardowns -- the dispose below
                    // joins the raster thread first, and the failure path stops
                    // drawing.
                    if (!nowSubtitled) subtitles.update(emptyList())
                    frameStamp++
                    invalidated = true
                }
                val nowFailed = state is VideoPlayer.State.Failed
                if (nowFailed != failed) {
                    failed = nowFailed
                    invalidated = true
                    // The draw stops at the failure; the frame it was holding
                    // does not have to stay in native memory behind it.
                    if (nowFailed) frames.close()
                }
                // A closed or failed player publishes nothing ever again, and the
                // draw that shows it is already asked for above, so the loop and
                // the thread its wait holds can go. A paused one still can publish
                // (a seek landing, a frame step), so it keeps its loop.
                if (state is VideoPlayer.State.Closed || state is VideoPlayer.State.Failed) return@LaunchedEffect
                if (invalidated) {
                    // The frame that shows what was just handed over. It costs
                    // no draw of its own, since the invalidation above has
                    // asked for that frame already, and it does not come while
                    // the window is not drawing: that wait is what stops the
                    // surface taking pictures nobody can see. How often a
                    // window the viewer cannot see still draws is the
                    // platform's call, and not every platform stops.
                    withFrameNanos { }
                    // Drawn, so the next take is worth making even with nothing
                    // new behind it. It is also what revives a player that
                    // stopped while the window was hidden, since only a take
                    // tells it the picture is wanted again.
                    permittedAt = Long.MIN_VALUE
                }
                // One picture in flight at a time: a second permit would only
                // let the raster thread run ahead of the draw it feeds.
                if (!rasterInFlight.get() && changes != permittedAt) {
                    permittedAt = changes
                    rasterInFlight.set(true)
                    ticks.release()
                }
            }
        } finally {
            forwarder.cancel()
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
        // The drawing thread's half of the image handover: what the raster
        // thread retired before this draw began is finished with, because
        // the draw that was using it has ended.
        frames.reclaim()
        val image = frames.image ?: return@Canvas
        // With the frame in hand, so the bars arrive with the picture rather
        // than ahead of it.
        background?.let { drawRect(it) }
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
        // Posted unguarded, from a draw scope that runs on every painted
        // frame, because the announcement is idempotent now: the same size
        // twice costs a comparison and queues nothing. This used to keep its
        // own copy of the last size, back when it did not -- a second place
        // holding the same rule, and the one a consumer drawing its own frames
        // could not see.
        if (player.activeSubtitleTrack != null) {
            player.setSubtitleCanvasSize(
                subtitleRect.width.roundToInt(),
                subtitleRect.height.roundToInt(),
            )
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
