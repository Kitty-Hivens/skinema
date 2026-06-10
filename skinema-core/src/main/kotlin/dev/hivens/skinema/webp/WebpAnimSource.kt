package dev.hivens.skinema.webp

import dev.hivens.skinema.libav.FrameSource
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.VideoDecoder
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path

/**
 * Animated (and still) WebP through libwebp's WebPAnimDecoder: frames
 * come out as RGBA on the full canvas with millisecond timestamps, so no
 * swscale and no pixel-format zoo. The whole file stays mapped in the
 * session arena for the decoder's lifetime -- WebPData borrows, never
 * copies, and background loops are small files by nature.
 *
 * Same confinement contract as [VideoDecoder]: the opening thread owns
 * the session.
 */
class WebpAnimSource private constructor(
    private val arena: Arena,
    private val decoder: MemorySegment,
    private val width: Int,
    private val height: Int,
) : FrameSource {

    private val bufOut = arena.allocate(ADDRESS)
    private val timestampOut = arena.allocate(JAVA_INT)
    private val rgbaHeap = ByteArray(width * height * 4)

    // GetNext reports when a frame's display ENDS; a frame's pts is the
    // previous frame's end.
    private var prevEndMs = 0

    override fun nextFrame(target: ByteArray?): VideoDecoder.RgbaFrame? {
        if (!Webp.hasMoreFrames(decoder)) return null
        if (Webp.getNext(decoder, bufOut, timestampOut) == 0) {
            throw LibavException("WebPAnimDecoderGetNext failed (corrupt animation data)")
        }
        val endMs = timestampOut.get(JAVA_INT, 0)
        val ptsNanos = prevEndMs * 1_000_000L
        prevEndMs = endMs

        val out = target?.takeIf { it.size == rgbaHeap.size } ?: rgbaHeap
        val canvas = bufOut.get(ADDRESS, 0).reinterpret(out.size.toLong())
        MemorySegment.copy(canvas, JAVA_BYTE, 0, out, 0, out.size)
        return VideoDecoder.RgbaFrame(width, height, ptsNanos, out)
    }

    override fun seekTo(ptsNanos: Long) {
        // Position zero is at-or-before any target; the caller decodes
        // forward to the exact frame, per the FrameSource contract.
        Webp.reset(decoder)
        prevEndMs = 0
    }

    override fun close() {
        Webp.delete(decoder)
        arena.close()
    }

    companion object {

        /** Opens [path]; the caller checked [Webp.available] and the RIFF/WEBP magic. */
        fun open(path: Path): WebpAnimSource {
            val arena = Arena.ofConfined()
            try {
                val bytes = Files.readAllBytes(path)
                val fileData = arena.allocate(bytes.size.toLong())
                MemorySegment.copy(bytes, 0, fileData, JAVA_BYTE, 0, bytes.size)

                val webpData = arena.allocate(WebpAbi.Data.SIZEOF)
                webpData.set(ADDRESS, WebpAbi.Data.BYTES, fileData)
                webpData.set(java.lang.foreign.ValueLayout.JAVA_LONG, WebpAbi.Data.SIZE, bytes.size.toLong())

                val options = arena.allocate(WebpAbi.DecoderOptions.SIZEOF)
                if (Webp.optionsInit(options) == 0) {
                    throw LibavException("WebPAnimDecoderOptionsInit refused ABI ${WebpAbi.DEMUX_ABI_VERSION}")
                }
                options.set(JAVA_INT, WebpAbi.DecoderOptions.COLOR_MODE, WebpAbi.MODE_RGBA)
                options.set(JAVA_INT, WebpAbi.DecoderOptions.USE_THREADS, 1)

                val decoder = Webp.decoderNew(webpData, options)
                if (decoder == MemorySegment.NULL) {
                    throw LibavException("WebPAnimDecoderNew rejected $path (not a decodable WebP)")
                }

                val info = arena.allocate(WebpAbi.AnimInfo.SIZEOF)
                if (Webp.getInfo(decoder, info) == 0) {
                    Webp.delete(decoder)
                    throw LibavException("WebPAnimDecoderGetInfo failed for $path")
                }
                val width = info.get(JAVA_INT, WebpAbi.AnimInfo.CANVAS_WIDTH)
                val height = info.get(JAVA_INT, WebpAbi.AnimInfo.CANVAS_HEIGHT)
                return WebpAnimSource(arena, decoder, width, height)
            } catch (t: Throwable) {
                arena.close()
                throw t
            }
        }
    }
}
