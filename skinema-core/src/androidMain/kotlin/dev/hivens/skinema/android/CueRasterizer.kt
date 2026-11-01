@file:OptIn(UnstableApi::class)

package dev.hivens.skinema.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import dev.hivens.skinema.subtitles.SubtitleOverlay
import dev.hivens.skinema.subtitles.SubtitlePatch
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Media3's cues as the overlay the desktop publishes: positioned patches of
 * premultiplied RGBA in a canvas of the size the consumer announced, so the
 * surface composites them the same way on both platforms.
 *
 * Text is laid out with the platform's own text engine and drawn white over a
 * dark outline, at the size and placement the cue asks for, falling back to
 * the convention subtitle renderers share: about a nineteenth of the height,
 * centred, eight percent up from the bottom. What it does not do is ASS
 * typesetting beyond what Media3's parser keeps (position, alignment, size):
 * no per-run fonts, karaoke or vector drawing, which libass does on the
 * desktop. Bitmap cues (PGS, DVB, VobSub) are scaled into the rect the cue
 * gives.
 */
internal object CueRasterizer {

    /** The overlay for [cues] on a [width] x [height] canvas. Empty [cues] is a clear. */
    fun rasterize(cues: List<Cue>, width: Int, height: Int): SubtitleOverlay {
        val overlay = SubtitleOverlay()
        overlay.canvasWidth = width
        overlay.canvasHeight = height
        if (width <= 0 || height <= 0) return overlay
        overlay.patches = cues.mapNotNull { cue ->
            runCatching { if (cue.bitmap != null) bitmapPatch(cue, width, height) else textPatch(cue, width, height) }.getOrNull()
        }
        return overlay
    }

    private fun textPatch(cue: Cue, width: Int, height: Int): SubtitlePatch? {
        val text = cue.text?.takeIf { it.isNotBlank() } ?: return null
        val textSize = when (cue.textSizeType) {
            Cue.TEXT_SIZE_TYPE_FRACTIONAL, Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING -> cue.textSize * height
            Cue.TEXT_SIZE_TYPE_ABSOLUTE -> cue.textSize
            else -> DEFAULT_TEXT_FRACTION * height
        }.coerceAtLeast(1f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            color = Color.WHITE
        }
        val maxWidth = ((if (cue.size != Cue.DIMEN_UNSET) cue.size else 1f) * width * (1f - 2 * SIDE_PADDING_FRACTION)).roundToInt().coerceAtLeast(1)
        val alignment = cue.textAlignment ?: Layout.Alignment.ALIGN_CENTER
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth).setAlignment(alignment).build()
        var textWidth = 0f
        for (i in 0 until layout.lineCount) textWidth = maxOf(textWidth, layout.getLineWidth(i))
        val outline = textSize * OUTLINE_FRACTION
        val pad = ceil(outline).toInt() + 1
        val boxWidth = ceil(textWidth).toInt() + 2 * pad
        val boxHeight = layout.height + 2 * pad

        val left = when {
            cue.position != Cue.DIMEN_UNSET -> anchored(cue.position * width, boxWidth, cue.positionAnchor)
            else -> (width - boxWidth) / 2f
        }
        val top = when {
            cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION -> anchored(cue.line * height, boxHeight, cue.lineAnchor)
            cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_NUMBER -> {
                val lineHeight = layout.height.toFloat() / layout.lineCount.coerceAtLeast(1)
                if (cue.line >= 0) cue.line * lineHeight else height + cue.line * lineHeight - boxHeight + lineHeight
            }
            else -> height * (1f - BOTTOM_PADDING_FRACTION) - boxHeight
        }

        val bitmap = Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (cue.windowColorSet) canvas.drawColor(cue.windowColor)
        // The layout is as wide as the cue allows; shift so the widest line
        // starts at the padding whatever the alignment placed it at.
        var lineLeft = Float.MAX_VALUE
        for (i in 0 until layout.lineCount) lineLeft = minOf(lineLeft, layout.getLineLeft(i))
        canvas.translate(pad - lineLeft, pad.toFloat())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outline * 2
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.BLACK
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        layout.draw(canvas)
        return patchOf(bitmap, left, top, width, height)
    }

    private fun bitmapPatch(cue: Cue, width: Int, height: Int): SubtitlePatch? {
        val source = cue.bitmap ?: return null
        val targetWidth = (if (cue.size != Cue.DIMEN_UNSET) cue.size * width else source.width.toFloat()).coerceAtLeast(1f)
        val targetHeight = if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
            cue.bitmapHeight * height
        } else {
            targetWidth * source.height / source.width.coerceAtLeast(1)
        }.coerceAtLeast(1f)
        val left = if (cue.position != Cue.DIMEN_UNSET) anchored(cue.position * width, targetWidth.roundToInt(), cue.positionAnchor) else (width - targetWidth) / 2f
        val top = if (cue.line != Cue.DIMEN_UNSET) anchored(cue.line * height, targetHeight.roundToInt(), cue.lineAnchor) else height - targetHeight
        val scaled = Bitmap.createBitmap(targetWidth.roundToInt(), targetHeight.roundToInt(), Bitmap.Config.ARGB_8888)
        Canvas(scaled).drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            RectF(0f, 0f, scaled.width.toFloat(), scaled.height.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return patchOf(scaled, left, top, width, height)
    }

    private fun anchored(at: Float, extent: Int, anchor: Int): Float = when (anchor) {
        Cue.ANCHOR_TYPE_MIDDLE -> at - extent / 2f
        Cue.ANCHOR_TYPE_END -> at - extent
        else -> at
    }

    /** The bitmap's premultiplied RGBA as a patch, placed and kept inside the canvas. */
    private fun patchOf(bitmap: Bitmap, left: Float, top: Float, width: Int, height: Int): SubtitlePatch {
        val bytes = ByteArray(bitmap.byteCount)
        // ARGB_8888 is stored as premultiplied R, G, B, A bytes: the patch's own layout.
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        val patch = SubtitlePatch()
        patch.width = bitmap.width
        patch.height = bitmap.height
        patch.x = left.roundToInt().coerceIn(0, (width - bitmap.width).coerceAtLeast(0))
        patch.y = top.roundToInt().coerceIn(0, (height - bitmap.height).coerceAtLeast(0))
        patch.rgba = bytes
        return patch
    }

    private const val DEFAULT_TEXT_FRACTION = 0.0533f
    private const val BOTTOM_PADDING_FRACTION = 0.08f
    private const val SIDE_PADDING_FRACTION = 0.05f
    private const val OUTLINE_FRACTION = 0.06f
}
