package dev.hivens.skinema.player

import dev.hivens.skinema.libav.FrameSource
import dev.hivens.skinema.libav.VideoDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * [FrameSource] over a synthetic pts grid -- no natives, no files. Built
 * for queue/pacing tests that need deterministic decode behavior:
 * [blockAt] turns one frame's decode into a latch wait (a hiccup with no
 * wall-clock guessing), [maxStartedIndex] records how far decode ran.
 *
 * Tests MUST open the latch (in a finally) before closing the player:
 * the decode thread parks inside [nextFrame], and close() can only join
 * it once the latch counts down.
 */
class ScriptedFrameSource(
    private val frameCount: Int,
    private val periodNanos: Long = 100_000_000L,
    private val width: Int = 4,
    private val height: Int = 4,
    /** Keyframe spacing in frames; seeks land on these (at-or-before). */
    private val keyframeEvery: Int = 1,
) : FrameSource {

    private var index = 0
    private var lastIndex = 0
    private val rgbaHeap = ByteArray(width * height * 4)

    /** Highest frame index whose decode has started. */
    val maxStartedIndex = AtomicInteger(-1)

    /** Total nextFrame calls that found a frame; a chase shows up here. */
    val decodeCount = AtomicInteger(0)

    @Volatile
    private var gateIndex = -1

    @Volatile
    private var gate: CountDownLatch? = null

    /** The next decode of frame [index] parks until the latch opens. */
    fun blockAt(index: Int): CountDownLatch {
        val latch = CountDownLatch(1)
        gate = latch
        gateIndex = index
        return latch
    }

    override fun nextFrame(target: ByteArray?, convert: Boolean): VideoDecoder.RgbaFrame? {
        if (index >= frameCount) return null
        val i = index
        maxStartedIndex.updateAndGet { maxOf(it, i) }
        decodeCount.incrementAndGet()
        if (i == gateIndex) gate?.await()
        lastIndex = i
        index++
        return if (convert) fill(target, i) else VideoDecoder.RgbaFrame(width, height, i * periodNanos, NO_PIXELS)
    }

    override fun convertLast(target: ByteArray?): VideoDecoder.RgbaFrame = fill(target, lastIndex)

    override fun seekTo(ptsNanos: Long) {
        // At-or-before on the keyframe grid; also reopens a drained
        // stream, per the FrameSource contract.
        val frame = (ptsNanos / periodNanos).toInt()
        index = (frame / keyframeEvery * keyframeEvery).coerceIn(0, frameCount)
    }

    override fun close() = Unit

    private fun fill(target: ByteArray?, i: Int): VideoDecoder.RgbaFrame {
        val out = target?.takeIf { it.size == rgbaHeap.size } ?: rgbaHeap
        out.fill((i % 251).toByte())
        return VideoDecoder.RgbaFrame(width, height, i * periodNanos, out)
    }

    private companion object {
        val NO_PIXELS = ByteArray(0)
    }
}
