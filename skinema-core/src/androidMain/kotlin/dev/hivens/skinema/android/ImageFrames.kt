package dev.hivens.skinema.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import dev.hivens.skinema.libav.Chapter
import dev.hivens.skinema.libav.FrameSource
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.VideoDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * One frame of an image file: where it lands on the canvas, how long it
 * stays, what happens to its area afterwards, and its pixels as a standalone
 * single-frame file the platform decoder reads.
 *
 * The file is built when it is decoded, from ranges of the original bytes,
 * not when the container is read: what every frame's file shares (a palette,
 * a colour profile) would otherwise be copied once per frame, and a small
 * file of many frames with a large shared chunk asked for gigabytes.
 */
internal class ImagePart(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val durationNanos: Long,
    val dispose: Dispose,
    /** Alpha-blended over what is there, or written in its place. */
    val blend: Boolean,
    private val encode: () -> ByteArray,
) {
    enum class Dispose { NONE, BACKGROUND, PREVIOUS }

    /** This frame as a file of its own. */
    fun encoded(): ByteArray = encode()
}

/**
 * An image file as frames: animated GIF, APNG and animated WebP split into
 * their frames, anything else the platform decodes (JPEG, PNG, WebP, GIF,
 * BMP, HEIF, AVIF) as one.
 *
 * The platform animates GIF and WebP itself but hands out no frame and no
 * timing, and does not animate APNG at all. What it does read, for all
 * three, is a single frame. So the container is taken apart here, each
 * frame's data is wrapped as a file of its own (its pixels unchanged, the
 * frame's size in the header), the platform decodes that, and [ImageFrameSource]
 * composes the frames by the format's own disposal and blending rules. No
 * pixel decoding is written here; only containers are read.
 */
internal class ImageFrames(
    val width: Int,
    val height: Int,
    val parts: List<ImagePart>,
    val animated: Boolean,
) {
    companion object {

        /** Whether [head] (a file's first bytes) is an image this reads rather than a video. */
        fun isImage(head: ByteArray): Boolean = when {
            head.startsWith("GIF8") -> true
            head.size >= 8 && head.copyOf(8).contentEquals(PNG_SIGNATURE) -> true
            head.startsWith("RIFF") && head.size >= 12 && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> true
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> true
            head.startsWith("BM") -> true
            head.size >= 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" &&
                String(head, 8, 4, Charsets.US_ASCII) in setOf("heic", "heix", "mif1", "msf1", "avif", "avis") -> true
            else -> false
        }

        /**
         * The frames of [bytes]. A container cut short gives the frames it
         * holds whole, as libavformat does; one that is not an animation, or
         * not one this reads, is decoded by the platform as a single picture.
         * Throws [LibavException] when there is no picture at all, and for a
         * canvas larger than [MAX_PIXELS].
         */
        fun read(bytes: ByteArray): ImageFrames {
            val parsed = when {
                bytes.startsWith("GIF8") -> gif(bytes)
                bytes.size >= 8 && bytes.copyOf(8).contentEquals(PNG_SIGNATURE) -> apng(bytes)
                bytes.startsWith("RIFF") -> webp(bytes)
                else -> null
            }
            return parsed ?: still(bytes)
        }

        private fun still(bytes: ByteArray): ImageFrames {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw LibavException("the platform reads no image in this file")
            checkCanvas(bounds.outWidth, bounds.outHeight)
            val part = ImagePart(0, 0, bounds.outWidth, bounds.outHeight, 0L, ImagePart.Dispose.NONE, false) { bytes }
            return ImageFrames(bounds.outWidth, bounds.outHeight, listOf(part), animated = false)
        }

        /** An animation's canvas, refused by name past [MAX_PIXELS]; null for one with no area, which is no animation. */
        private fun canvasOf(width: Int, height: Int): Pair<Int, Int>? {
            if (width <= 0 || height <= 0) return null
            checkCanvas(width, height)
            return width to height
        }

        private fun checkCanvas(width: Int, height: Int) {
            if (width.toLong() * height > MAX_PIXELS) throw LibavException("the image's canvas, ${width}x$height, is larger than this reads")
        }

        // -- GIF ---------------------------------------------------------------------

        private fun gif(b: ByteArray): ImageFrames? {
            val r = Reader(b)
            val header = try {
                r.skip(6)
                val width = r.u16le()
                val height = r.u16le()
                val packed = r.u8()
                val background = r.u8()
                val aspect = r.u8()
                val globalTable = if (packed and 0x80 != 0) r.bytes(3 * (1 shl ((packed and 7) + 1))) else ByteArray(0)
                GifHeader(width, height, packed, background, aspect, globalTable)
            } catch (_: Truncated) {
                return null
            }
            val (width, height) = canvasOf(header.width, header.height) ?: return null
            val packed = header.packed
            val background = header.background
            val aspect = header.aspect
            val globalTable = header.globalTable
            val parts = ArrayList<ImagePart>()
            var gce: ByteArray? = null
            var delay = 0
            var disposal = 0
            // A block cut short ends the file there: the frames before it are
            // whole, and are what it has.
            try {
                while (r.remaining() > 0) {
                    when (r.u8()) {
                        0x21 -> {
                            val label = r.u8()
                            if (label == 0xF9) {
                                val size = r.u8()
                                val body = r.bytes(size)
                                r.skipSubBlocks()
                                // Shorter than its four fields is no control at all.
                                if (body.size >= 4) {
                                    disposal = (body[0].toInt() shr 2) and 7
                                    delay = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
                                    // Carried into the frame's own file for its
                                    // transparency, with the disposal dropped: that
                                    // is the composer's business, not the decoder's.
                                    gce = byteArrayOf(0x21, 0xF9.toByte(), 4, (body[0].toInt() and 0x1C.inv() and 0xFF).toByte(), body[1], body[2], body[3], 0)
                                }
                            } else {
                                r.skipSubBlocks()
                            }
                        }
                        0x2C -> {
                            val x = r.u16le()
                            val y = r.u16le()
                            val w = r.u16le()
                            val h = r.u16le()
                            val local = r.u8()
                            // The frame's own colour table and data, as ranges of
                            // the file: copied when the frame is decoded.
                            val localStart = r.pos
                            if (local and 0x80 != 0) r.skip(3 * (1 shl ((local and 7) + 1)))
                            val dataStart = r.pos
                            r.skip(1) // LZW minimum code size
                            r.skipSubBlocks()
                            val dataEnd = r.pos
                            val frameGce = gce
                            parts += ImagePart(
                                x, y, w, h, frameNanos(delay * 10_000_000L),
                                when (disposal) {
                                    2 -> ImagePart.Dispose.BACKGROUND
                                    3 -> ImagePart.Dispose.PREVIOUS
                                    else -> ImagePart.Dispose.NONE
                                },
                                blend = true,
                            ) {
                                val out = ByteArrayOutputStream()
                                out.write("GIF89a".toByteArray(Charsets.US_ASCII))
                                out.write(le16(w)); out.write(le16(h)); out.write(packed); out.write(background); out.write(aspect)
                                out.write(globalTable)
                                frameGce?.let(out::write)
                                out.write(0x2C); out.write(le16(0)); out.write(le16(0)); out.write(le16(w)); out.write(le16(h)); out.write(local)
                                out.write(b, localStart, dataStart - localStart)
                                out.write(b, dataStart, dataEnd - dataStart)
                                out.write(0x3B)
                                out.toByteArray()
                            }
                            gce = null
                            delay = 0
                            disposal = 0
                        }
                        0x3B -> break
                        else -> break
                    }
                }
            } catch (_: Truncated) {
                // The frames read so far stand.
            }
            if (parts.size < 2) return null
            return ImageFrames(width, height, parts, animated = true)
        }

        private class GifHeader(val width: Int, val height: Int, val packed: Int, val background: Int, val aspect: Int, val globalTable: ByteArray)

        // -- APNG --------------------------------------------------------------------

        /** A chunk's type and where its data sits in the file, read from there only when needed. */
        private class PngChunk(val type: String, val start: Int, val length: Int) {
            fun bytes(b: ByteArray): ByteArray = b.copyOfRange(start, start + length)
        }

        private fun apng(b: ByteArray): ImageFrames? {
            val chunks = ArrayList<PngChunk>()
            var p = 8
            while (p + 8 <= b.size) {
                val length = ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).int
                val type = String(b, p + 4, 4, Charsets.US_ASCII)
                if (length < 0 || p + 12L + length > b.size) break
                chunks += PngChunk(type, p + 8, length)
                p += 12 + length
                if (type == "IEND") break
            }
            if (chunks.none { it.type == "acTL" }) return null
            val ihdr = chunks.firstOrNull { it.type == "IHDR" }?.takeIf { it.length >= 13 }?.bytes(b) ?: return null
            val (width, height) = canvasOf(ByteBuffer.wrap(ihdr, 0, 4).int, ByteBuffer.wrap(ihdr, 4, 4).int) ?: return null
            // What every frame's file needs from the original header: the
            // palette, its transparency and the colour description.
            val shared = chunks.takeWhile { it.type != "IDAT" && it.type != "fcTL" }
                .filter { it.type in setOf("PLTE", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT") }
            val parts = ArrayList<ImagePart>()
            var control: ByteArray? = null
            // The frame's pixel data, as (offset, length) ranges of the file.
            var data = ArrayList<IntArray>()
            fun flush() {
                val c = control ?: return
                // A control with no pixels after it (a file cut between the two)
                // is no frame.
                if (data.isEmpty()) {
                    control = null
                    return
                }
                val bb = ByteBuffer.wrap(c)
                bb.int // sequence
                val w = bb.int
                val h = bb.int
                val x = bb.int
                val y = bb.int
                val num = bb.short.toInt() and 0xFFFF
                val den = (bb.short.toInt() and 0xFFFF).let { if (it == 0) 100 else it }
                val dispose = bb.get().toInt()
                val blend = bb.get().toInt()
                val header = ihdr.copyOf()
                ByteBuffer.wrap(header).putInt(w).putInt(h)
                val pixels = data
                parts += ImagePart(
                    x, y, w, h, frameNanos(num * 1_000_000_000L / den),
                    when (dispose) {
                        1 -> ImagePart.Dispose.BACKGROUND
                        2 -> if (parts.isEmpty()) ImagePart.Dispose.BACKGROUND else ImagePart.Dispose.PREVIOUS
                        else -> ImagePart.Dispose.NONE
                    },
                    blend = blend == 1 && parts.isNotEmpty(),
                ) {
                    val file = ByteArrayOutputStream()
                    file.write(PNG_SIGNATURE)
                    pngChunk(file, "IHDR", header, listOf(intArrayOf(0, header.size)))
                    for (s in shared) pngChunk(file, s.type, b, listOf(intArrayOf(s.start, s.length)))
                    pngChunk(file, "IDAT", b, pixels)
                    pngChunk(file, "IEND", ByteArray(0), emptyList())
                    file.toByteArray()
                }
                control = null
                data = ArrayList()
            }
            for (chunk in chunks) {
                when (chunk.type) {
                    "fcTL" -> {
                        flush()
                        // Shorter than its fields is no frame: its data is skipped with it.
                        control = chunk.takeIf { it.length >= FCTL_SIZE }?.bytes(b)
                    }
                    // A default image that precedes every fcTL is not a frame of
                    // the animation, and is skipped with control still null.
                    "IDAT" -> if (control != null) data += intArrayOf(chunk.start, chunk.length)
                    "fdAT" -> if (control != null && chunk.length > 4) data += intArrayOf(chunk.start + 4, chunk.length - 4)
                }
            }
            flush()
            if (parts.size < 2) return null
            return ImageFrames(width, height, parts, animated = true)
        }

        /** A chunk of [type] whose data is the [ranges] of [source], in order, written with its length and CRC. */
        private fun pngChunk(out: ByteArrayOutputStream, type: String, source: ByteArray, ranges: List<IntArray>) {
            out.write(ByteBuffer.allocate(4).putInt(ranges.sumOf { it[1] }).array())
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.write(typeBytes)
            val crc = CRC32().apply { update(typeBytes) }
            for ((start, length) in ranges.map { it[0] to it[1] }) {
                out.write(source, start, length)
                crc.update(source, start, length)
            }
            out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        }

        // -- WebP --------------------------------------------------------------------

        private fun webp(b: ByteArray): ImageFrames? {
            if (b.size < 12 || String(b, 8, 4, Charsets.US_ASCII) != "WEBP") return null
            var p = 12
            var width = 0
            var height = 0
            var animated = false
            val parts = ArrayList<ImagePart>()
            while (p + 8 <= b.size) {
                val type = String(b, p, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val body = p + 8
                if (size < 0 || body.toLong() + size > b.size) break
                when (type) {
                    "VP8X" -> if (size >= 10) {
                        animated = b[body].toInt() and 0x02 != 0
                        width = u24(b, body + 4) + 1
                        height = u24(b, body + 7) + 1
                    }
                    "ANMF" -> if (size > 16) {
                        val x = u24(b, body) * 2
                        val y = u24(b, body + 3) * 2
                        val w = u24(b, body + 6) + 1
                        val h = u24(b, body + 9) + 1
                        val duration = u24(b, body + 12)
                        val flags = b[body + 15].toInt()
                        val dataStart = body + 16
                        val dataLength = size - 16
                        parts += ImagePart(
                            x, y, w, h, frameNanos(duration * 1_000_000L),
                            if (flags and 0x01 != 0) ImagePart.Dispose.BACKGROUND else ImagePart.Dispose.NONE,
                            blend = flags and 0x02 == 0,
                        ) { webpFrameFile(b, dataStart, dataLength, w, h) }
                    }
                }
                p = (body.toLong() + size + (size and 1)).coerceAtMost(b.size.toLong()).toInt()
            }
            if (!animated || parts.size < 2) return null
            canvasOf(width, height) ?: return null
            return ImageFrames(width, height, parts, animated = true)
        }

        /**
         * A frame's chunks as a standalone WebP: a lossless frame is a simple
         * file of its one chunk; a lossy one with alpha needs the extended
         * header that says so, with the frame's size as the canvas.
         */
        private fun webpFrameFile(b: ByteArray, start: Int, length: Int, w: Int, h: Int): ByteArray {
            val hasAlpha = length >= 4 && String(b, start, 4, Charsets.US_ASCII) == "ALPH"
            val body = ByteArrayOutputStream()
            if (hasAlpha) {
                body.write("VP8X".toByteArray(Charsets.US_ASCII))
                body.write(le32(10))
                body.write(byteArrayOf(0x10, 0, 0, 0))
                body.write(le24(w - 1)); body.write(le24(h - 1))
            }
            body.write(b, start, length)
            val payload = body.toByteArray()
            val out = ByteArrayOutputStream()
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(le32(4 + payload.size))
            out.write("WEBP".toByteArray(Charsets.US_ASCII))
            out.write(payload)
            return out.toByteArray()
        }

        // -- Shared --------------------------------------------------------------------

        /**
         * A frame's stay, with the convention every browser keeps: a delay of
         * a hundredth of a second or less is read as a tenth, since files from
         * the GIF era write zero or one meaning "as fast as allowed".
         */
        private fun frameNanos(nanos: Long): Long = if (nanos <= 10_000_000L) 100_000_000L else nanos

        /**
         * The largest canvas read, 2^27 pixels: a 108 MP photo fits, and a
         * header claiming more than that is refused rather than believed.
         */
        private const val MAX_PIXELS = 1L shl 27

        /** An fcTL's fields: sequence, size, offset, delay and the two ops. */
        private const val FCTL_SIZE = 26

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        private fun ByteArray.startsWith(prefix: String): Boolean =
            size >= prefix.length && String(this, 0, prefix.length, Charsets.US_ASCII) == prefix

        private fun u24(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)

        private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        private fun le24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    }

    /** The end of the bytes, met in the middle of something. */
    private object Truncated : RuntimeException()

    /** Reads forward through [b], throwing [Truncated] at anything that runs past its end. */
    private class Reader(private val b: ByteArray) {
        var pos = 0
        fun remaining() = b.size - pos
        private fun need(n: Int) {
            if (n > remaining()) throw Truncated
        }
        fun u8(): Int {
            need(1)
            return b[pos++].toInt() and 0xFF
        }
        fun u16le(): Int = u8() or (u8() shl 8)
        fun skip(n: Int) {
            need(n)
            pos += n
        }
        fun bytes(n: Int): ByteArray {
            need(n)
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }
        fun skipSubBlocks() {
            while (true) {
                val size = u8()
                if (size == 0) return
                skip(size)
            }
        }
    }
}

/**
 * The frames of an [ImageFrames], composed in order on a canvas of the
 * image's size and handed out as RGBA, straight alpha, as the decoders hand
 * out video. Every frame can be reached, so a seek lands exactly where it is
 * sent, composing the frames before it as it goes.
 */
internal class ImageFrameSource(private val image: ImageFrames) : FrameSource {

    // The one allocation the size of the picture. One the device has no memory
    // for is refused like any other image this cannot open.
    private val canvasBitmapField = try {
        Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    } catch (_: OutOfMemoryError) {
        throw LibavException("no memory for a ${image.width}x${image.height} canvas")
    }
    private val canvas = Canvas(canvasBitmapField)
    private val replace = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val over = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val pts = LongArray(image.parts.size).also { a ->
        var t = 0L
        for (i in a.indices) {
            a[i] = t
            t += image.parts[i].durationNanos
        }
    }
    private var next = 0
    private var previous: Bitmap? = null
    private var lastDrawn = -1
    // Only a decoder converting frames needs these; the player draws the
    // canvas. A row at a time, so converting costs one row of ints rather
    // than a second copy of the picture.
    private val row by lazy { IntArray(image.width) }
    private val rgba by lazy { ByteArray(image.width * image.height * 4) }

    private fun compose(index: Int) {
        if (lastDrawn >= 0) {
            val last = image.parts[lastDrawn]
            when (last.dispose) {
                ImagePart.Dispose.BACKGROUND -> canvas.drawRect(Rect(last.x, last.y, last.x + last.width, last.y + last.height), clear)
                ImagePart.Dispose.PREVIOUS -> previous?.let { canvas.drawBitmap(it, 0f, 0f, replace) }
                ImagePart.Dispose.NONE -> Unit
            }
        }
        val part = image.parts[index]
        previous = if (part.dispose == ImagePart.Dispose.PREVIOUS) canvasBitmapField.copy(Bitmap.Config.ARGB_8888, false) else null
        val encoded = part.encoded()
        val frame = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            ?: throw LibavException("the platform could not decode frame $index of the image")
        canvas.drawBitmap(frame, part.x.toFloat(), part.y.toFloat(), if (part.blend) over else replace)
        frame.recycle()
        lastDrawn = index
    }

    private fun current(target: ByteArray?): VideoDecoder.RgbaFrame {
        // getPixels hands colours back unpremultiplied, which is the straight
        // alpha the RGBA contract promises.
        val out = target?.takeIf { it.size == image.width * image.height * 4 } ?: rgba
        var d = 0
        for (y in 0 until image.height) {
            canvasBitmapField.getPixels(row, 0, image.width, 0, y, image.width, 1)
            for (c in row) {
                out[d] = Color.red(c).toByte()
                out[d + 1] = Color.green(c).toByte()
                out[d + 2] = Color.blue(c).toByte()
                out[d + 3] = Color.alpha(c).toByte()
                d += 4
            }
        }
        return VideoDecoder.RgbaFrame(image.width, image.height, pts[lastDrawn], out)
    }

    override fun nextFrame(target: ByteArray?, convert: Boolean): VideoDecoder.RgbaFrame? {
        if (next >= image.parts.size) return null
        compose(next++)
        return if (convert) current(target) else VideoDecoder.RgbaFrame(image.width, image.height, pts[lastDrawn], NO_PIXELS)
    }

    override fun convertLast(target: ByteArray?): VideoDecoder.RgbaFrame {
        check(lastDrawn >= 0) { "no frame has been decoded to convert" }
        return current(target)
    }

    /** Lands on the frame showing at [ptsNanos], composing the ones before it. */
    override fun seekTo(ptsNanos: Long) = landOn(pts.indexOfLast { it <= ptsNanos }.coerceAtLeast(0))

    override fun seekBefore(ptsNanos: Long) = landOn(pts.indexOfLast { it < ptsNanos }.coerceAtLeast(0))

    private fun landOn(index: Int) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        previous = null
        lastDrawn = -1
        for (i in 0 until index) compose(i)
        next = index
    }

    // -- For the player, which paces the frames itself and draws the canvas --

    val animated: Boolean get() = image.animated
    val frameCount: Int get() = image.parts.size

    /** The frame last composed onto [canvasBitmap], or -1 before the first. */
    val shown: Int get() = lastDrawn

    /** The composed picture. Premultiplied, as every software bitmap is, and changed by the next [show]. */
    val canvasBitmap: Bitmap get() = canvasBitmapField

    /** One lap of an animation; zero for a still. */
    val lapNanos: Long get() = pts.last() + image.parts.last().durationNanos

    fun ptsOf(index: Int): Long = pts[index]

    /** The frame showing at [ptsNanos] within a lap. */
    fun frameAt(ptsNanos: Long): Int = pts.indexOfLast { it <= ptsNanos }.coerceAtLeast(0)

    /** Composes up to frame [index]: forward from the one shown when it can, from the start when it cannot. */
    fun show(index: Int) {
        if (index == lastDrawn) return
        if (index < lastDrawn) landOn(0)
        while (lastDrawn < index) compose(lastDrawn + 1)
        next = index + 1
    }

    /** One lap, for an animation; null for a still, which declares none. */
    override fun durationNanos(): Long? = if (image.animated) lapNanos else null
    override fun tags(): Map<String, String> = emptyMap()
    override fun chapters(): List<Chapter> = emptyList()
    override fun coverArt(): ByteArray? = null
    override fun rotationDegrees(): Int = 0
    override fun videoSize(): Pair<Int, Int> = image.width to image.height
    override fun hardwareActive(): Boolean = false

    override fun close() {
        canvasBitmapField.recycle()
        previous?.recycle()
    }

    private companion object {
        val NO_PIXELS = ByteArray(0)
    }
}
