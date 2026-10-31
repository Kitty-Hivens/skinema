package dev.hivens.skinema.libav

import dev.hivens.skinema.audio.PcmEncoding
import dev.hivens.skinema.audio.PcmFormat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AudioDecoderDeviceTest {

    @Test
    fun aSecondOfAACDecodesToAboutASecondOfSamplesAtItsOwnRate() {
        val d = AudioDecoder.openOrNull(DeviceFixtures.path("sine-aac.m4a")) ?: fail("an audio decoder")
        d.use {
            var frames = 0L
            var format: PcmFormat? = null
            while (true) {
                val chunk = d.nextChunk() ?: break
                format = chunk.format
                frames += chunk.byteCount / chunk.format.bytesPerFrame
            }
            val f = format ?: fail("at least one chunk")
            assertEquals(48_000, f.sampleRate)
            assertEquals(1, f.channels)
            assertTrue(abs(frames - 48_000) < 3_000, "decoded $frames frames")
            val duration = d.durationNanos ?: fail("a declared duration")
            assertTrue(abs(duration - 1_000_000_000L) < 100_000_000, "duration $duration")
        }
    }

    @Test
    fun convertLastAsNarrowsTheChunkInHandAndEveryChunkAfterIt() {
        val d = AudioDecoder.openOrNull(DeviceFixtures.path("sine-aac.m4a")) ?: fail("an audio decoder")
        d.use {
            val first = d.nextChunk() ?: fail("a chunk")
            val sourceFrames = first.byteCount / first.format.bytesPerFrame
            val wanted = PcmFormat.floor(first.sampleRate)
            val converted = d.convertLastAs(wanted)
            assertEquals(wanted, converted.format)
            assertEquals(sourceFrames * 4, converted.byteCount)
            assertEquals(first.ptsNanos, converted.ptsNanos)
            val next = d.nextChunk() ?: fail("a second chunk")
            assertEquals(wanted, next.format)
        }
    }

    @Test
    fun a24BitFLACConvertsToFloatWithoutLosingItsShape() {
        val d = AudioDecoder.openOrNull(DeviceFixtures.path("tone-flac24.flac")) ?: fail("an audio decoder")
        d.use {
            val first = d.nextChunk() ?: fail("a chunk")
            assertEquals(44_100, first.sampleRate)
            assertEquals(2, first.format.channels)
            val wide = first.format.copy(encoding = PcmEncoding.F32LE, significantBits = 32)
            val converted = d.convertLastAs(wide)
            assertEquals(wide, converted.format)
            assertEquals(first.byteCount / first.format.bytesPerFrame * 8, converted.byteCount)
        }
    }

    @Test
    fun aFileWithNoSoundOpensAsNone() {
        assertNull(AudioDecoder.openOrNull(DeviceFixtures.path("red-h264.mp4")))
    }

    @Test
    fun tracksAreEnumeratedAndANonAudioIndexIsRefused() {
        val path = DeviceFixtures.path("red-with-sound.mp4")
        val d = AudioDecoder.openOrNull(path) ?: fail("an audio decoder")
        val audioIndex = d.use {
            assertEquals(1, d.tracks.size)
            assertEquals(d.streamIndex, d.tracks.single().streamIndex)
            d.streamIndex
        }
        val videoIndex = if (audioIndex == 0) 1 else 0
        assertFailsWith<LibavException> { AudioDecoder.openOrNull(path, videoIndex) }
    }

    @Test
    fun aSeekResumesNearItsTarget() {
        val d = AudioDecoder.openOrNull(DeviceFixtures.path("sine-aac.m4a")) ?: fail("an audio decoder")
        d.use {
            d.nextChunk()
            d.seekTo(500_000_000L)
            val chunk = d.nextChunk() ?: fail("a chunk after the seek")
            assertTrue(chunk.ptsNanos in 400_000_000L..520_000_000L, "resumed at ${chunk.ptsNanos}")
        }
    }
}
