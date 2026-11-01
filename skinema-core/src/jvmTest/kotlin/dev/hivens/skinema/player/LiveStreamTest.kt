package dev.hivens.skinema.player

import dev.hivens.skinema.audio.PacedPcmSink
import dev.hivens.skinema.encode.TranscodeConfig
import dev.hivens.skinema.encode.Transcoder
import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.MediaInput
import dev.hivens.skinema.libav.MediaSource
import dev.hivens.skinema.libav.VideoDecoder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The player over a [MediaSource] that reads forward only, the shape of a
 * live stream: one demuxer reads it for the picture, the sound and the
 * subtitles at once, and what cannot be done on such a stream is refused by
 * doing nothing, with [VideoPlayer.seekable] saying so.
 */
class LiveStreamTest {

    private val dir: Path = Files.createTempDirectory("skinema-live-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /**
     * Forward only: no seek, no size. Optionally raises after [failAfter]
     * bytes, or blocks after [blockAfter] bytes until closed, the two ways a
     * network stream goes wrong.
     */
    private class LiveSource(
        private val data: ByteArray,
        private val failAfter: Int = Int.MAX_VALUE,
        private val blockAfter: Int = Int.MAX_VALUE,
    ) : MediaSource {
        private var pos = 0
        val reads = AtomicInteger(0)

        @Volatile
        var closed = false

        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            reads.incrementAndGet()
            if (pos >= failAfter) throw IOException("the stream dropped at $pos")
            if (pos >= blockAfter) {
                while (!closed) Thread.sleep(5)
                throw IOException("closed under a read")
            }
            if (pos >= data.size) return -1
            val n = minOf(length, data.size - pos, failAfter - pos, blockAfter - pos)
            System.arraycopy(data, pos, dst, offset, n)
            pos += n
            return n
        }

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

    private fun fixture(name: String, seconds: Int = 4, sampleRate: Int = 48_000, secondTrack: Boolean = false): Path {
        val srt = dir.resolve("$name.srt")
        Files.writeString(srt, "1\n00:00:00,200 --> 00:00:30,000\nLive cue\n")
        val audio = if (secondTrack) {
            listOf(
                "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100",
                "-map", "0:v", "-map", "1:a", "-map", "3:a", "-map", "2",
            )
        } else {
            listOf("-map", "0:v", "-map", "1:a", "-map", "2")
        }
        return Fixtures.generate(
            dir.resolve("$name.mkv"),
            *(
                listOf(
                    "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=$sampleRate",
                    "-i", srt.toString(),
                ) + audio + listOf(
                    "-t", "$seconds",
                    "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-g", "10",
                    "-c:a", "flac", "-c:s", "srt",
                    // As a live muxer writes it: no duration up front.
                    "-live", "1",
                )
                ).toTypedArray(),
        )
    }

    private fun live(path: Path, failAfter: Int = Int.MAX_VALUE, blockAfter: Int = Int.MAX_VALUE) =
        LiveSource(Files.readAllBytes(path), failAfter, blockAfter)

    @Test
    fun `a live stream plays its picture on the sound's clock and ends at its end, loop or not`() {
        Fixtures.assumeDecodeEnvironment()
        val source = live(fixture("plays", seconds = 3))
        VideoPlayer(MediaInput.Stream(source), loop = true, audio = true, sink = PacedPcmSink(4_800)).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "a picture must arrive, state=${p.state}")
            assertFalse(p.seekable, "a forward-only stream says it cannot seek")
            assertTrue(awaitTrue { p.audioTracks.isNotEmpty() }, "the sound must be listed")
            val before = p.positionNanos()
            Thread.sleep(600)
            val moved = p.positionNanos() - before
            assertTrue(moved in 300_000_000L..900_000_000L, "the clock must run on the sound: moved $moved")
            var last = -1L
            assertTrue(
                awaitTrue {
                    p.acquireFrame()?.let { last = it.ptsNanos }
                    p.state == VideoPlayer.State.Ended
                },
                "a live stream cannot turn, so it ends: state=${p.state}",
            )
            assertTrue(last >= 2_000_000_000L, "the picture played on to near the end, last=$last")
            // The end of the stream, not a lap turned back to zero on the way.
            assertTrue(p.positionNanos() >= 2_000_000_000L, "the clock stopped where the stream did: ${p.positionNanos()}")
        }
    }

    @Test
    fun `seeks and the step back do nothing on a live stream, and a step forward works`() {
        Fixtures.assumeDecodeEnvironment()
        val source = live(fixture("seeks", seconds = 6))
        VideoPlayer(MediaInput.Stream(source), loop = false, startPaused = true).use { p ->
            assertTrue(awaitTrue { p.state == VideoPlayer.State.Paused }, "the paused start must settle")
            assertTrue(awaitTrue { p.acquireFrame() != null }, "the poster must be up")
            p.seek(3_000_000_000L)
            p.seekBy(2_000_000_000L)
            Thread.sleep(300)
            assertEquals(VideoPlayer.State.Paused, p.state, "nothing moved the player")
            assertTrue(p.positionNanos() < 200_000_000L, "the position stayed put: ${p.positionNanos()}")
            p.stepForward()
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 100_000_000L }, "a step forward takes the next frame")
            // Off the first frame, where a step back would have somewhere to go.
            p.stepBackward()
            Thread.sleep(400)
            assertEquals(null, p.acquireFrame()?.ptsNanos, "a step back put up no other frame")
            // Decoding toward an earlier frame on a stream that cannot go back
            // reads it to its end, which would end the player.
            assertEquals(VideoPlayer.State.Paused, p.state, "a step back leaves the player paused where it was")
            assertTrue(kotlin.math.abs(p.positionNanos() - 100_000_000L) < 20_000_000L, "and left the position on the frame shown: ${p.positionNanos()}")
        }
    }

    @Test
    fun `a sound track switch on a live stream takes from the next packets`() {
        Fixtures.assumeDecodeEnvironment()
        val source = live(fixture("two", seconds = 8, secondTrack = true))
        VideoPlayer(MediaInput.Stream(source), loop = false, audio = true, sink = PacedPcmSink(4_800)).use { p ->
            assertTrue(awaitTrue { p.audioTracks.size == 2 }, "both tracks must be listed: ${p.audioTracks}")
            val second = p.audioTracks[1].streamIndex
            p.selectAudioTrack(second)
            assertTrue(awaitTrue { p.activeAudioTrack == second }, "the switch must take")
            assertEquals(44_100, p.activeAudioFormat?.sampleRate, "the second track's own rate")
            assertEquals(VideoPlayer.State.Playing, p.state)
        }
    }

    @Test
    fun `embedded subtitles render from a live stream`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        // Long enough that the cue's packet is read after the selection lands.
        val source = live(fixture("subs", seconds = 20))
        VideoPlayer(MediaInput.Stream(source), loop = false).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            val id = p.subtitleTracks.single().id
            p.selectSubtitleTrack(id)
            assertTrue(awaitTrue { p.activeSubtitleTrack == id }, "the selection must land")
            assertTrue(awaitTrue { p.state == VideoPlayer.State.Playing })
        }
    }

    @Test
    fun `a switch goes from a file to a live stream and back, and closes the stream`() {
        Fixtures.assumeDecodeEnvironment()
        val file = fixture("file")
        val source = live(fixture("switched", seconds = 8, sampleRate = 44_100))
        VideoPlayer(MediaInput.File(file), loop = true, audio = true, sink = PacedPcmSink(4_800)).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "the file must play")
            assertTrue(p.seekable)
            val toStream = MediaInput.Stream(source)
            p.setSource(toStream)
            assertTrue(awaitTrue { p.source == toStream }, "the stream must be taken, refusal=${p.sourceFailure}")
            assertTrue(awaitTrue { p.state == VideoPlayer.State.Playing })
            assertFalse(p.seekable)
            assertTrue(awaitTrue { p.activeAudioFormat?.sampleRate == 44_100 }, "the sound must be the stream's")
            p.setSource(MediaInput.File(file))
            assertTrue(awaitTrue { p.source == MediaInput.File(file) }, "the file must be taken again")
            assertTrue(p.seekable)
            assertTrue(awaitTrue { source.closed }, "the stream must be closed once the player is off it")
        }
    }

    @Test
    fun `a live stream that raises fails the player by name`() {
        Fixtures.assumeDecodeEnvironment()
        val path = fixture("drops", seconds = 8)
        val source = live(path, failAfter = (Files.size(path) / 2).toInt())
        VideoPlayer(MediaInput.Stream(source), loop = false).use { p ->
            assertTrue(awaitTrue(15_000) { p.state is VideoPlayer.State.Failed }, "the drop must fail the player, state=${p.state}")
            val cause = assertIs<VideoPlayer.State.Failed>(p.state).cause
            var c: Throwable? = cause
            while (c != null && c !is IOException) c = c.cause
            assertNotNull(c, "the source's own error must be the cause, got $cause")
        }
    }

    @Test
    fun `a close with a read blocked inside the stream returns, and closes it`() {
        Fixtures.assumeDecodeEnvironment()
        val path = fixture("stalls", seconds = 8)
        val source = live(path, blockAfter = (Files.size(path) / 3).toInt())
        val p = VideoPlayer(MediaInput.Stream(source), loop = false, audio = true, sink = PacedPcmSink(4_800))
        assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
        // Until what the lanes held has played out and the picture stands: the
        // sides are then waiting on empty lanes behind the blocked read, and
        // nothing but the close itself can get them out.
        var lastFrameAt = System.nanoTime()
        val stallDeadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() - lastFrameAt < 1_500_000_000L && System.nanoTime() < stallDeadline) {
            if (p.acquireFrame() != null) lastFrameAt = System.nanoTime()
            Thread.sleep(10)
        }
        assertTrue(System.nanoTime() < stallDeadline, "the picture must stand once the stream stalls")
        val started = System.nanoTime()
        p.close()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(source.closed, "the stream must be closed")
        assertTrue(tookMs < 4_000, "the close must not wait on the blocked read: ${tookMs}ms")
        assertTrue(awaitTrue(3_000) { p.state is VideoPlayer.State.Closed }, "the teardown must finish: ${p.state}")
    }

    @Test
    fun `a subtitle track turned off and on again on a live stream comes back`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val source = live(fixture("toggles", seconds = 20))
        VideoPlayer(MediaInput.Stream(source), loop = false).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            val id = p.subtitleTracks.single().id
            // Faster than the leaving reader lets go of its lane.
            p.selectSubtitleTrack(id)
            p.selectSubtitleTrack(null)
            p.selectSubtitleTrack(id)
            assertTrue(awaitTrue { p.activeSubtitleTrack == id }, "the selection must land")
            Thread.sleep(500)
            assertEquals(id, p.activeSubtitleTrack, "the track must still be live, not dead on a lane already taken")
        }
    }

    @Test
    fun `a switch replaced before it is made closes the stream it carried`() {
        Fixtures.assumeDecodeEnvironment()
        val first = live(fixture("first", seconds = 6))
        val second = live(fixture("second", seconds = 6))
        VideoPlayer(MediaInput.File(fixture("base")), loop = true).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "the file must play")
            val toSecond = MediaInput.Stream(second)
            p.setSource(MediaInput.Stream(first))
            p.setSource(toSecond)
            assertTrue(awaitTrue { p.source == toSecond }, "the later switch must be the one that stands")
            assertTrue(awaitTrue { first.closed }, "the stream nobody is going to play must be closed")
        }
    }

    @Test
    fun `a stream handed to a closed player is closed`() {
        Fixtures.assumeDecodeEnvironment()
        val p = VideoPlayer(MediaInput.File(fixture("closed")), loop = true)
        assertTrue(awaitTrue { p.acquireFrame() != null }, "the file must play")
        p.close()
        val late = live(fixture("late"))
        p.setSource(MediaInput.Stream(late))
        assertTrue(late.closed, "the player owns what it was handed, closed or not")
    }

    @Test
    fun `a stream that throws when asked whether it can seek is closed and fails the player`() {
        var closed = false
        val broken = object : MediaSource {
            override fun read(dst: ByteArray, offset: Int, length: Int): Int = -1
            override fun seek(position: Long): Long = throw IOException("no answer")
            override fun close() {
                closed = true
            }
        }
        VideoPlayer(MediaInput.Stream(broken), loop = false).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Failed }, "the player must fail: ${p.state}")
        }
        assertTrue(closed, "the stream must be closed")
    }

    @Test
    fun `the picture comes back after time ran on without it`() {
        Fixtures.assumeDecodeEnvironment()
        val source = live(fixture("hidden", seconds = 10))
        VideoPlayer(
            MediaInput.Stream(source), loop = false, audio = true, sink = PacedPcmSink(4_800),
            unwatched = WhenUnwatched.KeepTime,
        ).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            p.reportVisible(false)
            Thread.sleep(1_500)
            p.reportVisible(true)
            val at = p.positionNanos()
            var back = -1L
            assertTrue(
                awaitTrue {
                    p.acquireFrame()?.let { back = it.ptsNanos }
                    back >= at - 1_100_000_000L && back <= at + 1_500_000_000L && back > 1_000_000_000L
                },
                "the picture must rejoin near the clock: frame $back against $at",
            )
        }
    }

    @Test
    fun `a live stream transcodes and is closed after`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeLibraryEncoder("aac")
        // Whichever video encoder this bundle carries: the decode tier has no
        // libx264. Sized past SVT-AV1's minimum.
        val encoder = listOf("libx264", "libsvtav1").firstOrNull { Fixtures.libraryHasEncoder(it) }
        assumeTrue(encoder != null, "this bundle carries no video encoder")
        val input = Fixtures.generate(
            dir.resolve("transcoded.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=128x128:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a", "-t", "2",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-g", "10", "-c:a", "flac",
        )
        val source = live(input)
        val out = dir.resolve("transcoded-out.mp4")
        Transcoder.open(MediaInput.Stream(source), out, TranscodeConfig(videoCodec = encoder!!, audioCodec = "aac")).use { it.run() }
        assertTrue(source.closed, "the transcoder owns the stream and closes it")
        VideoDecoder.open(out).use { d ->
            assertEquals(20, generateSequence { d.nextFrame(convert = false) }.count(), "two seconds at ten frames a second")
        }
        assertNotNull(AudioDecoder.openOrNull(out), "the output carries the sound").close()
    }
}
