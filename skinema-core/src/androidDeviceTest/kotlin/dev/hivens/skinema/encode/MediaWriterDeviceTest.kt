package dev.hivens.skinema.encode

import androidx.test.platform.app.InstrumentationRegistry
import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.DeviceFixtures
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.VideoDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class MediaWriterDeviceTest {

    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("skinema-writer").apply {
        deleteRecursively()
        mkdirs()
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun out(name: String): Path = dir.resolve(name).toPath()

    private fun solid(w: Int, h: Int, r: Int, g: Int, b: Int) = ByteArray(w * h * 4).also {
        for (i in 0 until w * h) {
            it[i * 4] = r.toByte(); it[i * 4 + 1] = g.toByte(); it[i * 4 + 2] = b.toByte(); it[i * 4 + 3] = 0xFF.toByte()
        }
    }

    /** A second of a 440 Hz tone as S16LE stereo at [rate]. */
    private fun tone(rate: Int): ByteArray {
        val buf = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until rate) {
            val s = (sin(2 * PI * 440 * i / rate) * 12_000).toInt().toShort()
            buf.putShort(s); buf.putShort(s)
        }
        return buf.array()
    }

    /** Frame count, and the centre colour of the fifth frame, read before the decoder reuses its pixels. */
    private fun decodeVideo(path: Path): Pair<Int, IntArray?> {
        VideoDecoder.open(path, HwAccel.AUTO).use { d ->
            var count = 0
            var colour: IntArray? = null
            while (true) {
                val f = d.nextFrame() ?: break
                count++
                if (count == 5) {
                    val i = ((f.height / 2) * f.width + f.width / 2) * 4
                    colour = IntArray(3) { f.rgba[i + it].toInt() and 0xFF }
                }
            }
            return count to colour
        }
    }

    private fun audioSeconds(path: Path): Double {
        val d = AudioDecoder.openOrNull(path) ?: fail("no sound in $path")
        d.use {
            var frames = 0L
            var rate = 0
            while (true) {
                val c = d.nextChunk() ?: break
                frames += c.byteCount / c.format.bytesPerFrame
                rate = c.sampleRate
            }
            return frames.toDouble() / rate
        }
    }

    private fun writeRed(path: Path, codec: String, frames: Int = 30) {
        MediaWriter.open(path, VideoEncodeConfig(codec, 320, 240, 30)).use { w ->
            val red = solid(320, 240, 255, 0, 0)
            repeat(frames) { i -> w.writeFrame(red, i * 1_000_000_000L / 30) }
            w.finish()
        }
    }

    @Test
    fun theH264FamilyWritesAFileThatDecodesBack() {
        val path = out("family.mp4")
        writeRed(path, "h264")
        val (count, colour) = decodeVideo(path)
        assertEquals(30, count)
        val c = colour!!
        assertTrue(c[0] > 220 && c[1] < 40 && c[2] < 40, "red, got ${c.toList()}")
    }

    @Test
    fun aDesktopEncoderNameMapsOntoItsCodec() {
        val path = out("libx264.mp4")
        writeRed(path, "libx264", frames = 10)
        assertEquals(10, decodeVideo(path).first)
    }

    @Test
    fun hevcIsWrittenOrRefusedByName() {
        val path = out("hevc.mp4")
        try {
            writeRed(path, "hevc", frames = 10)
            assertEquals(10, decodeVideo(path).first)
        } catch (e: LibavException) {
            assertTrue("video/hevc" in e.message.orEmpty(), "the refusal names the codec: ${e.message}")
        }
    }

    @Test
    fun hdIsConvertedAndTaggedSoItReadsBackTheSameColour() {
        val path = out("hd.mp4")
        MediaWriter.open(path, VideoEncodeConfig("h264", 1280, 720, 30)).use { w ->
            // A saturated green, where the two matrices part furthest: read
            // through the wrong one it comes back near (3, 172, 28).
            val green = solid(1280, 720, 16, 200, 32)
            repeat(8) { i -> w.writeFrame(green, i * 33_333_333L) }
            w.finish()
        }
        val c = decodeVideo(path).second!!
        for ((ch, want) in listOf(16, 200, 32).withIndex()) assertTrue(abs(c[ch] - want) <= 14, "green, got ${c.toList()}")
    }

    @Test
    fun videoAndSoundGoIntoOneFile() {
        val path = out("av.mp4")
        MediaWriter.open(path, VideoEncodeConfig("h264", 320, 240, 30), AudioEncodeConfig("aac", 48_000)).use { w ->
            val red = solid(320, 240, 255, 0, 0)
            val pcm = tone(48_000)
            repeat(30) { i ->
                w.writeAudio(pcm.copyOfRange(i * 6_400, (i + 1) * 6_400))
                w.writeFrame(red, i * 1_000_000_000L / 30)
            }
            w.finish()
        }
        assertEquals(30, decodeVideo(path).first)
        assertTrue(abs(audioSeconds(path) - 1.0) < 0.1, "a second of sound")
    }

    /**
     * Every picture before any sound, so the video encoder hands out packets
     * before the audio track exists. The platform muxer takes every track
     * before its first sample, so those packets wait; this is the order that
     * makes them.
     */
    @Test
    fun packetsFromBeforeTheLastTrackExistsAreKept() {
        val path = out("picture-first.mp4")
        MediaWriter.open(path, VideoEncodeConfig("h264", 320, 240, 30), AudioEncodeConfig("aac", 48_000)).use { w ->
            val red = solid(320, 240, 255, 0, 0)
            repeat(30) { i -> w.writeFrame(red, i * 1_000_000_000L / 30) }
            w.writeAudio(tone(48_000))
            w.finish()
        }
        assertEquals(30, decodeVideo(path).first)
    }

    @Test
    fun everySoundOnlyContainerWritesASecondOfSound() {
        for ((name, codec) in listOf("tone.m4a" to "aac", "tone.ogg" to "opus", "tone.flac" to "flac", "tone.wav" to "pcm_s16le")) {
            val path = out(name)
            MediaWriter.open(path, AudioEncodeConfig(codec, 48_000)).use { w ->
                w.writeAudio(tone(48_000))
                w.finish()
            }
            val seconds = audioSeconds(path)
            assertTrue(abs(seconds - 1.0) < 0.1, "$name holds a second of sound, got $seconds")
        }
    }

    @Test
    fun aWriterClosedWithoutFinishingStillLeavesAReadableFile() {
        val path = out("unfinished.mp4")
        val w = MediaWriter.open(path, VideoEncodeConfig("h264", 320, 240, 30))
        val red = solid(320, 240, 255, 0, 0)
        repeat(10) { i -> w.writeFrame(red, i * 33_333_333L) }
        w.close()
        assertEquals(10, decodeVideo(path).first)
    }

    @Test
    fun whatTheWriterCannotDoIsRefusedByName() {
        assertTrue("Matroska" in assertFailsWith<LibavException> { MediaWriter.open(out("x.mkv"), VideoEncodeConfig("h264", 320, 240, 30)) }.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { MediaWriter.open(out("odd.mp4"), VideoEncodeConfig("h264", 321, 240, 30)) }
        MediaWriter.open(out("sound.m4a"), AudioEncodeConfig("aac", 48_000)).use { w ->
            assertFailsWith<LibavException> { w.writeFrame(ByteArray(4), 0) }
        }
        assertFailsWith<LibavException> { MediaWriter.open(out("x.mp4"), VideoEncodeConfig("prores", 320, 240, 30)) }
    }

    @Test
    fun theTranscoderRunsOnTheAndroidDecodersAndWriter() {
        val path = out("transcoded.mp4")
        Transcoder.open(DeviceFixtures.path("red-with-sound.mp4"), path, TranscodeConfig(videoCodec = "h264", audioCodec = "aac")).use { it.run() }
        val (count, colour) = decodeVideo(path)
        assertEquals(30, count)
        val c = colour!!
        assertTrue(c[0] > 220 && c[1] < 40, "red, got ${c.toList()}")
        assertTrue(audioSeconds(path) > 0.8, "the sound came through")
    }
}
