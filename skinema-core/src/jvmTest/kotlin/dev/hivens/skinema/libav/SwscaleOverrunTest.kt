package dev.hivens.skinema.libav

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * sws_scale's packed RGBA writer emits whole SIMD blocks, spilling past a
 * width not aligned to the block (1080 writes ~32 B past a tight w*h*4, while
 * the 16-aligned 2560/1280 spill nothing). VideoDecoder.ensureSws pads its
 * destination by [SWS_PAD] for exactly this; here the same pad must absorb the
 * spill, i.e. nothing is written at or past w*h*4 + [SWS_PAD].
 */
class SwscaleOverrunTest {

    // Must match VideoDecoder.SWS_WRITE_PADDING.
    private val SWS_PAD = 128L

    private fun spillPastPad(w: Int, h: Int): Int {
        val guard = 4096
        val sentinel = 0xAB.toByte()
        Arena.ofConfined().use { arena ->
            // Synthetic YUV420P source: mid-gray. Content is irrelevant -- the
            // over-write depends on width, not pixels.
            val yl = w; val cl = w / 2
            val yPlane = arena.allocate(yl.toLong() * h).also { fill(it, yl.toLong() * h, 128) }
            val uPlane = arena.allocate(cl.toLong() * (h / 2)).also { fill(it, cl.toLong() * (h / 2), 128) }
            val vPlane = arena.allocate(cl.toLong() * (h / 2)).also { fill(it, cl.toLong() * (h / 2), 128) }
            val srcData = arena.allocate(ADDRESS, 8).apply {
                setAtIndex(ADDRESS, 0, yPlane); setAtIndex(ADDRESS, 1, uPlane); setAtIndex(ADDRESS, 2, vPlane)
            }
            val srcStride = arena.allocate(JAVA_INT, 8).apply {
                setAtIndex(JAVA_INT, 0, yl); setAtIndex(JAVA_INT, 1, cl); setAtIndex(JAVA_INT, 2, cl)
            }

            // Mirror ensureSws: the real destination is w*h*4 + SWS_PAD; a
            // guard past that catches any spill the pad fails to absorb.
            val bytes = w.toLong() * h * 4
            val total = bytes + SWS_PAD + guard
            val dst = arena.allocate(total)
            for (i in 0 until total) dst.set(JAVA_BYTE, i, sentinel)
            val dstData = arena.allocate(ADDRESS, 8).also { it.setAtIndex(ADDRESS, 0, dst) }
            val dstStride = arena.allocate(JAVA_INT, 8).also { it.setAtIndex(JAVA_INT, 0, w * 4) }

            val ctx = Libav.swsGetContext(w, h, LibavAbi.AV_PIX_FMT_YUV420P, w, h, LibavAbi.AV_PIX_FMT_RGBA, LibavAbi.SWS_BILINEAR)
            check(ctx != MemorySegment.NULL) { "sws_getContext refused ${w}x$h" }
            Libav.swsScale(ctx, srcData, srcStride, 0, h, dstData, dstStride)
            Libav.swsFreeContext(ctx)

            var touched = 0
            for (i in (bytes + SWS_PAD) until total) if (dst.get(JAVA_BYTE, i) != sentinel) touched++
            return touched
        }
    }

    private fun fill(seg: MemorySegment, len: Long, value: Int) {
        for (i in 0 until len) seg.set(JAVA_BYTE, i, value.toByte())
    }

    @Test
    fun `the swscale destination pad absorbs the block spill`() {
        Fixtures.assumeDecodeEnvironment()
        // A spread of non-block-aligned widths (the crashing 1080 among them).
        for ((w, h) in listOf(2560 to 1440, 1280 to 720, 1080 to 1080, 1082 to 1082, 1918 to 1080, 1278 to 720)) {
            assertEquals(0, spillPastPad(w, h), "sws_scale wrote past w*h*4 + $SWS_PAD for ${w}x$h (w%16=${w % 16})")
        }
    }
}
