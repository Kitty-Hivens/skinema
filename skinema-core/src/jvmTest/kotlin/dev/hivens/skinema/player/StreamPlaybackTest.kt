package dev.hivens.skinema.player

import dev.hivens.skinema.audio.PacedPcmSink
import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.MediaInput
import dev.hivens.skinema.libav.MediaSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The player over a [MediaSource] that can seek: every side opens its own
 * demuxer through a cursor, so everything a file does, a stream does too.
 * Each source here fails the test if two calls into it ever overlap, which
 * is the promise the cursors exist to keep.
 */
class StreamPlaybackTest {

    private val dir: Path = Files.createTempDirectory("skinema-stream-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /** A whole file in memory, seekable, counting overlapping calls and its own close. */
    private class MemorySource(private val data: ByteArray) : MediaSource {
        private var pos = 0
        private val inside = AtomicInteger(0)
        val overlaps = AtomicInteger(0)
        val reads = AtomicInteger(0)

        @Volatile
        var closed = false

        private inline fun <T> guarded(block: () -> T): T {
            if (inside.incrementAndGet() > 1) overlaps.incrementAndGet()
            try {
                return block()
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun read(dst: ByteArray, offset: Int, length: Int): Int = guarded {
            reads.incrementAndGet()
            if (pos >= data.size) return@guarded -1
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, dst, offset, n)
            pos += n
            n
        }

        override fun seek(position: Long): Long = guarded {
            if (position < 0 || position > data.size) return@guarded -1L
            pos = position.toInt()
            position
        }

        override fun size(): Long = guarded { data.size.toLong() }

        override fun close() {
            closed = true
        }
    }

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /** Ten frames a second with a keyframe every ten, sound, and a subtitle cue. */
    private fun fullFixture(name: String, seconds: Int = 6, sampleRate: Int = 48_000): Path {
        val srt = dir.resolve("$name.srt")
        Files.writeString(srt, "1\n00:00:00,300 --> 00:00:05,000\nHello stream\n")
        return Fixtures.generate(
            dir.resolve("$name.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=$sampleRate",
            "-i", srt.toString(),
            "-map", "0:v", "-map", "1:a", "-map", "2", "-t", "$seconds",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-g", "10",
            "-c:a", "flac", "-c:s", "srt",
        )
    }

    private fun stream(path: Path) = MemorySource(Files.readAllBytes(path))

    @Test
    fun `a seekable stream plays its picture and its sound`() {
        Fixtures.assumeDecodeEnvironment()
        val source = stream(fullFixture("plays"))
        VideoPlayer(MediaInput.Stream(source), loop = false, audio = true, sink = PacedPcmSink(4_800)).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "a picture must arrive, state=${p.state}")
            assertTrue(awaitTrue { p.audioTracks.isNotEmpty() }, "the sound track must be listed")
            assertTrue(p.seekable, "a stream that seeks says so")
            val before = p.positionNanos()
            Thread.sleep(600)
            val moved = p.positionNanos() - before
            assertTrue(moved in 300_000_000L..900_000_000L, "the clock must run on the sound: moved $moved")
            assertEquals(0, source.overlaps.get(), "calls into the stream overlapped")
        }
    }

    @Test
    fun `seeks and steps on a seekable stream land where they do on a file`() {
        Fixtures.assumeDecodeEnvironment()
        val source = stream(fullFixture("seeks"))
        VideoPlayer(MediaInput.Stream(source), loop = false, startPaused = true).use { p ->
            assertTrue(awaitTrue { p.state == VideoPlayer.State.Paused }, "the paused start must settle")
            p.seek(2_500_000_000L, exact = true)
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 2_500_000_000L }, "an exact seek lands on its frame")
            assertTrue(awaitTrue { p.state == VideoPlayer.State.Paused })
            p.seek(2_500_000_000L, exact = false)
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 2_000_000_000L }, "an inexact seek lands on the keyframe")
            assertTrue(awaitTrue { p.state == VideoPlayer.State.Paused })
            p.stepForward()
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 2_100_000_000L }, "a step forward moves one frame")
            p.stepBackward()
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 2_000_000_000L }, "a step back moves one frame")
            assertEquals(0, source.overlaps.get(), "calls into the stream overlapped")
        }
    }

    @Test
    fun `a seekable stream turns its lap`() {
        Fixtures.assumeDecodeEnvironment()
        val source = stream(fullFixture("laps", seconds = 1))
        VideoPlayer(MediaInput.Stream(source), loop = true).use { p ->
            assertTrue(awaitTrue { p.positionNanos() > 600_000_000L }, "the first lap must play")
            assertTrue(awaitTrue { p.positionNanos() < 400_000_000L }, "the lap must turn, position=${p.positionNanos()}")
            assertEquals(VideoPlayer.State.Playing, p.state)
        }
    }

    @Test
    fun `a sound track switch on a seekable stream takes`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("two.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100",
            "-map", "0:v", "-map", "1:a", "-map", "2:a", "-t", "6",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "flac",
        )
        val source = stream(video)
        VideoPlayer(MediaInput.Stream(source), loop = true, audio = true, sink = PacedPcmSink(4_800)).use { p ->
            assertTrue(awaitTrue { p.audioTracks.size == 2 }, "both tracks must be listed")
            val second = p.audioTracks[1].streamIndex
            p.selectAudioTrack(second)
            assertTrue(awaitTrue { p.activeAudioTrack == second }, "the switch must take")
            assertEquals(44_100, p.activeAudioFormat?.sampleRate, "the second track's own rate")
            assertEquals(0, source.overlaps.get(), "calls into the stream overlapped")
        }
    }

    @Test
    fun `embedded subtitles render from a seekable stream`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val source = stream(fullFixture("subs"))
        VideoPlayer(MediaInput.Stream(source), loop = true).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            val id = p.subtitleTracks.single().id
            p.selectSubtitleTrack(id)
            assertTrue(awaitTrue { p.activeSubtitleTrack == id }, "the selection must land")
            var seen = false
            assertTrue(
                awaitTrue {
                    p.acquireSubtitles()?.let { seen = seen || it.patches.isNotEmpty() }
                    seen
                },
                "the cue must reach the overlay",
            )
            assertEquals(0, source.overlaps.get(), "calls into the stream overlapped")
        }
    }

    @Test
    fun `a switch goes from a file to a stream and back, and lets the stream go`() {
        Fixtures.assumeDecodeEnvironment()
        val file = fullFixture("file")
        // Another rate than the file, so the sound read after the switch says which input it came from.
        val source = stream(fullFixture("switched", sampleRate = 44_100))
        VideoPlayer(MediaInput.File(file), loop = true, audio = true, sink = PacedPcmSink(4_800)).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "the file must play")
            val toStream = MediaInput.Stream(source)
            p.setSource(toStream)
            assertTrue(awaitTrue { p.source == toStream }, "the stream must be taken, refusal=${p.sourceFailure}")
            val before = source.reads.get()
            assertTrue(awaitTrue { p.state == VideoPlayer.State.Playing })
            assertTrue(awaitTrue { source.reads.get() > before }, "the stream must be read past its open")
            assertTrue(awaitTrue { p.activeAudioFormat?.sampleRate == 44_100 }, "the sound must be the stream's, got ${p.activeAudioFormat}")
            p.setSource(MediaInput.File(file))
            assertTrue(awaitTrue { p.source == MediaInput.File(file) }, "the file must be taken again")
            assertTrue(awaitTrue { source.closed }, "the stream must be closed once nothing reads it")
            assertEquals(0, source.overlaps.get(), "calls into the stream overlapped")
        }
    }

    @Test
    fun `a close with a read blocked inside a seekable stream returns, and closes it`() {
        Fixtures.assumeDecodeEnvironment()
        val bytes = Files.readAllBytes(fullFixture("stalls", seconds = 8))
        val stall = bytes.size / 3
        // Answers seeks anywhere, and blocks in any read past a third of the
        // file until it is closed: a network source gone silent.
        val source = object : MediaSource {
            private var pos = 0

            @Volatile
            var closed = false

            override fun read(dst: ByteArray, offset: Int, length: Int): Int {
                if (pos >= stall) {
                    while (!closed) Thread.sleep(5)
                    throw IOException("closed under a read")
                }
                val n = minOf(length, stall - pos)
                System.arraycopy(bytes, pos, dst, offset, n)
                pos += n
                return n
            }

            override fun seek(position: Long): Long {
                if (position < 0 || position > bytes.size) return -1
                pos = position.toInt()
                return position
            }

            override fun size(): Long = bytes.size.toLong()

            override fun close() {
                closed = true
            }
        }
        val p = VideoPlayer(MediaInput.Stream(source), loop = false, audio = true, sink = PacedPcmSink(4_800))
        assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
        var lastFrameAt = System.nanoTime()
        val stallDeadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() - lastFrameAt < 1_500_000_000L && System.nanoTime() < stallDeadline) {
            if (p.acquireFrame() != null) lastFrameAt = System.nanoTime()
            Thread.sleep(10)
        }
        val started = System.nanoTime()
        p.close()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(source.closed, "the stream must be closed")
        assertTrue(tookMs < 4_000, "the close must not wait on the blocked read: ${tookMs}ms")
    }

    @Test
    fun `closing the player closes the stream`() {
        Fixtures.assumeDecodeEnvironment()
        val source = stream(fullFixture("closes"))
        val p = VideoPlayer(MediaInput.Stream(source), loop = true, audio = true, sink = PacedPcmSink(4_800))
        assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
        p.close()
        assertTrue(source.closed, "the player owns the stream and closes it")
    }
}
