package dev.hivens.skinema.player

import dev.hivens.skinema.core.AudioClock
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptedFrameSourceTest {

    @Test
    fun `frames follow the pts grid and end at the count`() {
        val source = ScriptedFrameSource(frameCount = 3, periodNanos = 100)
        assertEquals(0L, source.nextFrame(convert = false)!!.ptsNanos)
        assertEquals(100L, source.nextFrame(convert = false)!!.ptsNanos)
        assertEquals(200L, source.nextFrame(convert = false)!!.ptsNanos)
        assertNull(source.nextFrame(convert = false))
        assertEquals(2, source.maxStartedIndex.get())
    }

    @Test
    fun `convertLast materializes the frame that was decoded bare`() {
        val source = ScriptedFrameSource(frameCount = 5, periodNanos = 100, width = 2, height = 2)
        source.nextFrame(convert = false)
        source.nextFrame(convert = false)
        val target = ByteArray(2 * 2 * 4)
        val frame = source.convertLast(target)
        assertEquals(100L, frame.ptsNanos)
        assertTrue(frame.rgba === target, "a matching target must receive the pixels")
        assertEquals(1.toByte(), target[0])
    }

    @Test
    fun `seek lands at-or-before and reopens a drained stream`() {
        val source = ScriptedFrameSource(frameCount = 10, periodNanos = 100)
        while (source.nextFrame(convert = false) != null) { /* drain */ }
        source.seekTo(250)
        assertEquals(200L, source.nextFrame(convert = false)!!.ptsNanos, "at-or-before the target")
        source.seekTo(5_000)
        assertNull(source.nextFrame(convert = false), "a seek past the end stays drained")
    }

    @Test
    fun `a player runs end to end over the scripted seam`() {
        val frames = AtomicLong(0)
        val clock = AudioClock(48_000) { frames.get() }
        val source = ScriptedFrameSource(frameCount = 10)
        val player = VideoPlayer(
            Path.of("scripted"), false, false, clock, null, 1, null,
        ) { source }
        player.use {
            val deadline = System.currentTimeMillis() + 10_000
            var first: VideoPlayer.FrameSlot? = null
            while (first == null && System.currentTimeMillis() < deadline) {
                first = it.acquireFrame()
                Thread.sleep(10)
            }
            assertNotNull(first, "frame 0 is due at media time 0")
            assertEquals(0L, first.ptsNanos)
            assertEquals(4, first.width)
        }
    }
}
