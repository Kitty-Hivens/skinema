package dev.hivens.skinema.player

import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real-time smoke tests: they assert reachable states and frame delivery
 * with generous deadlines, never exact timing -- shared CI runners stall.
 */
class VideoPlayerTest {

    private val dir: Path = Files.createTempDirectory("skinema-player-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun shortVideo(name: String, seconds: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", seconds,
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
    )

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun `plays a non-looping video to Ended and serves frames on the way`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("ended.mp4", "0.5"), loop = false).use { player ->
            var frames = 0
            var lastWidth = 0
            awaitTrue {
                player.acquireFrame()?.let {
                    frames++
                    lastWidth = it.width
                }
                player.state is VideoPlayer.State.Ended
            }
            assertIs<VideoPlayer.State.Ended>(player.state)
            assertTrue(frames >= 1, "at least one frame must be served, got $frames")
            assertEquals(64, lastWidth)
        }
    }

    @Test
    fun `looping playback wraps back to the first frame`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("loop.mp4", "0.3"), loop = true).use { player ->
            var zeroPtsSeen = 0
            awaitTrue {
                player.acquireFrame()?.let { if (it.ptsNanos == 0L) zeroPtsSeen++ }
                zeroPtsSeen >= 2
            }
            assertTrue(zeroPtsSeen >= 2, "pts 0 must come around again on loop, saw it $zeroPtsSeen time(s)")
        }
    }

    @Test
    fun `a missing file surfaces as Failed, not an exception`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(dir.resolve("missing.mp4"), loop = false).use { player ->
            awaitTrue { player.state is VideoPlayer.State.Failed }
            assertIs<VideoPlayer.State.Failed>(player.state)
        }
    }

    @Test
    fun `seek revives an Ended player at the requested frame`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("revive.mp4", "0.5"), loop = false).use { player ->
            awaitTrue { player.state is VideoPlayer.State.Ended }
            player.seek(200_000_000L)
            assertTrue(
                awaitTrue { player.acquireFrame()?.ptsNanos == 200_000_000L },
                "the seeked frame must be published",
            )
            assertIs<VideoPlayer.State.Playing>(player.state)
        }
    }
}
