package dev.hivens.skinema.encode

import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.FrameSource
import dev.hivens.skinema.libav.FrameSources
import dev.hivens.skinema.libav.LibavException
import java.nio.file.Path

/**
 * What to make of the input. Geometry is not here on purpose: it comes
 * from the source, because this converts a file rather than resizing one.
 *
 * [audioCodec] null drops the sound. [fps] zero measures the source's own
 * cadence from its first frames -- it is a rate-control hint either way,
 * since the timing that reaches the file is each frame's own timestamp.
 */
class TranscodeConfig(
    val videoCodec: String,
    val videoBitRate: Long = 0,
    val videoOptions: Map<String, String> = emptyMap(),
    val fps: Int = 0,
    val audioCodec: String? = null,
    val audioBitRate: Long = 0,
    val audioOptions: Map<String, String> = emptyMap(),
    /** Hardware device for a GPU encoder; null takes the driver's default. */
    val device: String? = null,
)

/**
 * Reads a media file and writes another: the join between the decode side
 * and [MediaWriter], which have existed separately and could not be put
 * together by a consumer without meeting the two traps below.
 *
 * **It re-renders; it does not copy streams.** Frames come out of the
 * decoder as RGBA and go into the encoder as RGBA, so every frame is
 * converted twice -- out of the source's chroma layout and back into the
 * encoder's. That costs a chroma generation and two swscale passes per
 * frame. It is what the seams this is built from actually carry, and it is
 * stated rather than hidden: a caller who needs a faithful or fast convert
 * of a file whose codec is already what they want is better served by not
 * decoding it at all, which this cannot do.
 *
 * What it does carry is the timing, which is the part a caller cannot get
 * right from the outside:
 *
 * - **One origin.** [MediaWriter] times video by the timestamp it is
 *   handed and audio by a running sample count from the first sample
 *   pushed, so a source whose sound starts after its picture would come
 *   out with the two shifted apart. The gap is padded with silence.
 * - **One cadence.** The muxer interleaves by timestamp and holds packets
 *   from one stream until the other catches up, so pushing a whole track
 *   and then the other queues the first in native memory. Both are pushed
 *   in timestamp order, a chunk at a time.
 *
 * Rotation is applied rather than carried: a source that stores its pixels
 * sideways with an orientation tag produces an upright file, because the
 * writer has no tag to pass it on with and a silently sideways output is
 * the worse answer.
 *
 * Fail-closed like the rest: any libav refusal throws [LibavException],
 * [close] releases everything, and a partial output file is left where it
 * fell rather than deleted -- the caller knows what it asked for.
 */
class Transcoder private constructor(
    private val video: FrameSource,
    private val audio: AudioDecoder?,
    private val audioRate: Int?,
    private val config: TranscodeConfig,
    private val output: Path,
) : AutoCloseable {

    private var writer: MediaWriter? = null
    private var closed = false

    @Volatile
    private var cancelled = false

    /** Frames written so far; readable from another thread for progress. */
    @Volatile
    var framesWritten = 0L
        private set

    /**
     * Media time reached, in nanoseconds -- against the source's duration
     * this is the progress fraction. Readable from another thread.
     */
    @Volatile
    var positionNanos = 0L
        private set

    /** The source's duration, when it declares one. */
    val durationNanos: Long? = video.durationNanos()

    /**
     * Stops the run at the next frame boundary. The output is finished
     * properly -- trailer and all -- so a cancelled transcode leaves a
     * shorter file that plays, not a broken one.
     */
    fun cancel() {
        cancelled = true
    }

    /**
     * Runs to the end of the source, or until [cancel]. Blocking, and the
     * only thread that may touch this instance while it runs is the one
     * that called it.
     */
    fun run() {
        check(!closed) { "run after close()" }
        val rotation = video.rotationDegrees()
        // The first frame settles the geometry, and the second the cadence,
        // so the writer cannot be opened before both are in hand.
        val first = video.nextFrame() ?: throw LibavException("the source produced no video frame")
        val firstPixels = first.rgba.copyOf()
        val firstPts = first.ptsNanos
        val second = video.nextFrame()
        val secondPixels = second?.rgba?.copyOf()
        val secondPts = second?.ptsNanos

        val (outWidth, outHeight) = displayedSize(first.width, first.height, rotation)
        val fps = config.fps.takeIf { it > 0 } ?: measuredFps(firstPts, secondPts)
        val w = MediaWriter.open(
            output,
            VideoEncodeConfig(
                codecName = config.videoCodec,
                width = outWidth,
                height = outHeight,
                fps = fps,
                bitRate = config.videoBitRate,
                options = config.videoOptions,
                device = config.device,
            ),
            config.audioCodec?.let { name ->
                AudioEncodeConfig(
                    codecName = name,
                    sampleRate = audioRate ?: throw LibavException("no audio stream to encode as '$name'"),
                    bitRate = config.audioBitRate,
                    options = config.audioOptions,
                )
            },
        )
        writer = w

        val rotated = if (rotation == 0) ByteArray(0) else ByteArray(outWidth * outHeight * 4)
        fun push(pixels: ByteArray, width: Int, height: Int, ptsNanos: Long) {
            // A source that changes geometry mid-stream cannot be encoded
            // into a stream already opened at the first frame's size. The
            // writer refuses it too, but a rotation buffer sized for the old
            // geometry would run off its end before the writer got to say so.
            if (width != first.width || height != first.height) {
                throw LibavException(
                    "the source changed geometry mid-stream, ${first.width}x${first.height} to ${width}x$height",
                )
            }
            val out = if (rotation == 0) pixels else rotate(pixels, width, height, rotation, rotated)
            w.writeFrame(out, ptsNanos)
            framesWritten++
            positionNanos = ptsNanos
        }

        push(firstPixels, first.width, first.height, firstPts)
        if (secondPixels != null && secondPts != null) push(secondPixels, first.width, first.height, secondPts)

        // Audio leads the interleave: its chunk is pushed while its start is
        // at or before the video frame waiting to go, so neither side runs
        // ahead of the muxer's window.
        var pendingAudio = audio?.nextChunk()
        var padded = false
        while (!cancelled) {
            val frame = video.nextFrame() ?: break
            while (pendingAudio != null && pendingAudio.ptsNanos <= frame.ptsNanos) {
                if (!padded) {
                    padded = true
                    padLeadingSilence(w, pendingAudio.ptsNanos, pendingAudio.sampleRate)
                }
                w.writeAudio(pendingAudio.pcm.copyOf(pendingAudio.byteCount))
                pendingAudio = audio?.nextChunk()
            }
            push(frame.rgba, frame.width, frame.height, frame.ptsNanos)
        }
        // Whatever sound outlives the picture. A track longer than the
        // video is ordinary -- any cut where the music runs past the last
        // shot -- and dropping it here would truncate the file's own tail.
        while (!cancelled && pendingAudio != null) {
            if (!padded) {
                padded = true
                padLeadingSilence(w, pendingAudio.ptsNanos, pendingAudio.sampleRate)
            }
            w.writeAudio(pendingAudio.pcm.copyOf(pendingAudio.byteCount))
            pendingAudio = audio?.nextChunk()
        }
        w.finish()
    }

    /**
     * Silence for the gap before the sound starts. The writer times audio
     * by a running sample count from the first sample it is given, so a
     * track that begins a second into the file would otherwise be pulled a
     * second forward against the picture.
     */
    private fun padLeadingSilence(w: MediaWriter, firstPtsNanos: Long, sampleRate: Int) {
        if (firstPtsNanos <= 0) return
        val frames = firstPtsNanos * sampleRate / 1_000_000_000L
        if (frames <= 0) return
        val chunk = ByteArray(CHUNK_FRAMES * BYTES_PER_AUDIO_FRAME)
        var left = frames
        while (left > 0) {
            val n = minOf(left, CHUNK_FRAMES.toLong()).toInt()
            w.writeAudio(if (n == CHUNK_FRAMES) chunk else ByteArray(n * BYTES_PER_AUDIO_FRAME))
            left -= n
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { writer?.close() }
        runCatching { video.close() }
        runCatching { audio?.close() }
    }

    companion object {
        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        private const val BYTES_PER_AUDIO_FRAME = 4

        /** Silence is written in chunks rather than one allocation. */
        private const val CHUNK_FRAMES = 4096

        /** When a source declares no usable cadence, and none was asked for. */
        internal const val FALLBACK_FPS = 30

        /**
         * Opens [input] and prepares to write [output]. The output's
         * extension picks the muxer, as it does for [MediaWriter].
         *
         * Nothing is written until [run]. Throws -- leaving nothing open --
         * when the input carries no video, or when either decoder refuses
         * it.
         */
        fun open(input: Path, output: Path, config: TranscodeConfig): Transcoder {
            val video = FrameSources.open(input)
            val audio = if (config.audioCodec != null) {
                runCatching { AudioDecoder.openOrNull(input) }.getOrNull()
            } else {
                null
            }
            if (config.audioCodec != null && audio == null) {
                runCatching { video.close() }
                throw LibavException("'$input' has no audio stream to encode as '${config.audioCodec}'")
            }
            val rate = audio?.let { d ->
                d.tracks.firstOrNull { it.streamIndex == d.streamIndex }?.sampleRate
                    ?: d.tracks.firstOrNull()?.sampleRate
            }
            return Transcoder(video, audio, rate, config, output)
        }

        /**
         * The cadence between the first two frames, as a whole number of
         * frames per second. A rate-control hint, not the timing: what
         * reaches the file is each frame's own timestamp.
         */
        internal fun measuredFps(firstPts: Long, secondPts: Long?): Int {
            if (secondPts == null) return FALLBACK_FPS
            val delta = secondPts - firstPts
            if (delta <= 0) return FALLBACK_FPS
            return (1_000_000_000L / delta).toInt().coerceIn(1, 240)
        }

        /** The size a viewer sees once the source's rotation is applied. */
        internal fun displayedSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
            if (rotationDegrees == 90 || rotationDegrees == 270) height to width else width to height

        /**
         * Turns RGBA clockwise by [rotationDegrees] into [into], which must
         * be sized for the rotated geometry. Applied rather than carried:
         * the writer has no orientation tag to pass on, so a source that
         * stores its pixels sideways would otherwise produce a file that
         * plays sideways.
         */
        internal fun rotate(
            src: ByteArray,
            width: Int,
            height: Int,
            rotationDegrees: Int,
            into: ByteArray,
        ): ByteArray {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val from = (y * width + x) * 4
                    val to = when (rotationDegrees) {
                        90 -> ((x * height) + (height - 1 - y)) * 4
                        180 -> (((height - 1 - y) * width) + (width - 1 - x)) * 4
                        else -> (((width - 1 - x) * height) + y) * 4
                    }
                    into[to] = src[from]
                    into[to + 1] = src[from + 1]
                    into[to + 2] = src[from + 2]
                    into[to + 3] = src[from + 3]
                }
            }
            return into
        }
    }
}
