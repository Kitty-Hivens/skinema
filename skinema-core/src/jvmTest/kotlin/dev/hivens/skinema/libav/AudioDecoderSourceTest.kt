package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Custom-AVIO input on the audio side: decode an audio-only stream through
 * a [MediaSource] -- the shape of a music-radio feed -- and match the file
 * decode chunk for chunk. A second case checks the no-audio path (which
 * must still release the avio context and return null).
 */
class AudioDecoderSourceTest {

    private val dir: Path = Files.createTempDirectory("skinema-audio-source-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private class ByteArraySource(private val data: ByteArray) : MediaSource {
        private var pos = 0
        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, dst, offset, n)
            pos += n
            return n
        }
        override fun seek(position: Long): Long {
            if (position < 0 || position > data.size) return -1
            pos = position.toInt()
            return position
        }
        override fun size(): Long = data.size.toLong()
    }

    @Test
    fun `an audio stream fed through a MediaSource decodes identically to the path`() {
        Fixtures.assumeDecodeEnvironment()
        val audio = Fixtures.generate(
            dir.resolve("tone.flac"),
            "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "flac",
        )
        val viaPath = AudioDecoder.openOrNull(audio)!!
            .use { d -> generateSequence { d.nextChunk()?.ptsNanos }.toList() }
        val bytes = Files.readAllBytes(audio)
        val viaSource = AudioDecoder.openOrNull(ByteArraySource(bytes))!!
            .use { d -> generateSequence { d.nextChunk()?.ptsNanos }.toList() }
        assertTrue(viaPath.isNotEmpty(), "the path decode should yield chunks")
        assertEquals(viaPath, viaSource, "the byte-fed decode must match the file decode chunk for chunk")
    }

    @Test
    fun `a source with no audio stream returns null`() {
        Fixtures.assumeDecodeEnvironment()
        val videoOnly = Fixtures.generate(
            dir.resolve("silent.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=32x32:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-an",
        )
        val bytes = Files.readAllBytes(videoOnly)
        assertNull(AudioDecoder.openOrNull(ByteArraySource(bytes)), "no audio stream must be null, not a failure")
    }
}
