package dev.hivens.skinema.libav

import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Files whose timestamps do not start at zero, which is ordinary: a capture
 * remuxed from transport stream, anything cut with an output offset. Every
 * timestamp is normalized against the container's start_time so the position
 * runs from zero -- and the duration has to describe the same timeline, or
 * the two disagree by exactly that offset.
 */
class OffsetTimelineTest {

    private val dir: Path = Files.createTempDirectory("skinema-offset-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun offset(
        name: String,
        seconds: String,
        offsetSeconds: String = "10",
        extra: List<String> = emptyList(),
    ): Path = Fixtures.generate(
        dir.resolve(name),
        *(
            listOf(
                "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10", "-t", seconds,
                "-output_ts_offset", offsetSeconds,
                "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "10",
            ) + extra
            ).toTypedArray(),
    )

    /**
     * FFmpeg computes a duration of its own only when the demuxer left one
     * unset, and it computes end_time minus start_time -- a span. One the
     * demuxer DID set is taken verbatim, and Matroska's is the last timestamp:
     * measured on five seconds muxed at a ten-second offset, mkv and webm
     * declared 15 s where mp4 and mpegts declared 5.
     */
    @Test
    fun `every container reports the span it can actually play`() {
        Fixtures.assumeDecodeEnvironment()
        // Both sides of the rule. mkv and webm are the containers that
        // declare a duration and no per-stream one, so the offset has to be
        // taken out of it; mp4, mov and mpegts declare per-stream lengths and
        // must be left exactly alone. A fix that helped the first pair by
        // hurting the second would pass a test that only listed the first.
        val cases = buildList {
            add("mkv" to offset("off.mkv", "5"))
            add("mp4" to offset("off.mp4", "5"))
            add("mov" to offset("off.mov", "5"))
            add("ts" to offset("off.ts", "5"))
            if (Fixtures.hasCliEncoder("libvpx-vp9")) {
                add("webm" to offset("off.webm", "5", extra = listOf("-c:v", "libvpx-vp9", "-b:v", "200k")))
            }
            // The control that actually exercises the rule's condition. The
            // others above carry a duration SMALLER than their offset, so an
            // arithmetic guard alone already leaves them alone and the case
            // proves nothing. Six seconds of footage at a two-second offset
            // is the shape where a rule that simply subtracted would take
            // four seconds off a file that plays six.
            add("mp4 long" to offset("long.mp4", "6", offsetSeconds = "2"))
            add("mov long" to offset("long.mov", "6", offsetSeconds = "2"))
        }
        for ((label, file) in cases) {
            VideoDecoder.open(file).use { d ->
                val declared = assertNotNull(d.durationNanos(), "$label declared no duration")
                val pts = generateSequence { d.nextFrame(convert = false)?.ptsNanos }.toList()
                assertTrue(pts.isNotEmpty(), "$label decoded nothing")
                // Ten frames a second, so the last frame is shown until one
                // frame period past its own timestamp.
                val span = pts.max() + 100_000_000L
                val off = declared - span
                assertTrue(
                    off in -150_000_000L..150_000_000L,
                    "$label declares ${declared / 1_000_000}ms for ${span / 1_000_000}ms of footage",
                )
            }
        }
    }

    /**
     * And what an overstated duration costs. The lap is held open until media
     * time reaches the file's own end, so a duration carrying the offset held
     * the last frame on screen for the length of that offset, every lap,
     * with the state still reporting Playing.
     */
    @Test
    fun `an offset file loops on its own length`() {
        Fixtures.assumeDecodeEnvironment()
        // One second of footage at a ten-second offset: a lap that measures
        // its own length takes about a second, one that measures the declared
        // duration takes eleven.
        val file = offset("loop.mkv", "1")
        VideoPlayer(file, loop = true, audio = false).use { player ->
            fun awaitWrap(deadlineMs: Long): Long? {
                val deadline = System.currentTimeMillis() + deadlineMs
                var last = -1L
                while (System.currentTimeMillis() < deadline) {
                    player.acquireFrame()
                    val now = player.positionNanos()
                    if (last > 500_000_000L && now < last - 200_000_000L) return System.nanoTime()
                    last = now
                    Thread.sleep(5)
                }
                return null
            }
            val first = assertNotNull(awaitWrap(8_000), "the first lap must come round")
            val second = assertNotNull(awaitWrap(8_000), "the second lap must come round")
            val lapMs = (second - first) / 1_000_000
            assertTrue(
                lapMs < 3_000,
                "a one-second lap took ${lapMs}ms -- the lap is waiting out the container's offset",
            )
        }
    }
}
