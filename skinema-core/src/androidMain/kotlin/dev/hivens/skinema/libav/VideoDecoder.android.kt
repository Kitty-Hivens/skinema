package dev.hivens.skinema.libav

import dev.hivens.skinema.android.CodecFrameSource
import dev.hivens.skinema.android.ImageFrameSource
import dev.hivens.skinema.android.ImageFrames
import dev.hivens.skinema.android.Input
import java.nio.file.Path

/**
 * One open video or image file on Android.
 *
 * Video goes through the platform extractor and a MediaCodec decoder
 * ([CodecFrameSource]). Images, which the platform's video path does not open
 * at all, go through [ImageFrameSource]: animated GIF, APNG and animated WebP
 * as their frames with their own timing, any other image the platform
 * decodes (JPEG, PNG, WebP, GIF, BMP, HEIF, AVIF) as one frame, as the desktop
 * decodes a still. Which is which is read off the file's first bytes.
 */
actual class VideoDecoder private constructor(
    private val source: FrameSource,
    actual val streamIndex: Int,
    // An image is read into memory at the open; the input is still closed with
    // the decoder, which is when a consumer's MediaSource is promised its close.
    private val input: Input?,
) : FrameSource {

    actual val timeBaseNum: Int = 1
    actual val timeBaseDen: Int = 1_000_000

    actual class RgbaFrame internal constructor(
        actual val width: Int,
        actual val height: Int,
        actual val ptsNanos: Long,
        actual val rgba: ByteArray,
    )

    actual override fun nextFrame(target: ByteArray?, convert: Boolean): RgbaFrame? = source.nextFrame(target, convert)
    actual override fun convertLast(target: ByteArray?): RgbaFrame = source.convertLast(target)
    actual override fun seekTo(ptsNanos: Long) = source.seekTo(ptsNanos)
    actual override fun seekBefore(ptsNanos: Long) = source.seekBefore(ptsNanos)
    actual override fun durationNanos(): Long? = source.durationNanos()
    actual override fun tags(): Map<String, String> = source.tags()
    actual override fun chapters(): List<Chapter> = source.chapters()
    actual override fun coverArt(): ByteArray? = source.coverArt()
    actual override fun rotationDegrees(): Int = source.rotationDegrees()
    actual override fun subtitleTracks(): List<SubtitleTrack> = source.subtitleTracks()
    actual override fun videoSize(): Pair<Int, Int>? = source.videoSize()
    actual override fun hardwareActive(): Boolean = source.hardwareActive()
    actual override fun captionBytes(): ByteArray? = source.captionBytes()

    actual override fun close() {
        runCatching { source.close() }
        input?.close()
    }

    actual companion object {
        actual fun open(path: Path, hardware: HwAccel): VideoDecoder = openFrom(Input.File(path), hardware)

        actual fun open(source: MediaSource, hardware: HwAccel): VideoDecoder = openFrom(Input.Source(source), hardware)

        private fun openFrom(input: Input, hardware: HwAccel): VideoDecoder {
            val image = try {
                ImageFrames.isImage(input.head(16))
            } catch (t: Throwable) {
                input.close()
                throw LibavException("cannot read ${input.label}: ${t.message}")
            }
            if (!image) {
                val codec = CodecFrameSource.open(input, hardware)
                return VideoDecoder(codec, codec.streamIndex, null)
            }
            try {
                // An image has no hardware decoder anywhere, so REQUIRE refuses
                // it, as it does on the desktop.
                if (hardware == HwAccel.REQUIRE) throw LibavException("no hardware decoder takes an image (HwAccel.REQUIRE)")
                return VideoDecoder(ImageFrameSource(ImageFrames.read(input.readAll())), 0, input)
            } catch (e: LibavException) {
                input.close()
                throw e
            } catch (e: Exception) {
                // The contract's one refusal type, whatever in the file tripped
                // what reads it.
                input.close()
                throw LibavException("cannot read the image in ${input.label}: $e").apply { initCause(e) }
            } catch (t: Throwable) {
                input.close()
                throw t
            }
        }
    }
}
