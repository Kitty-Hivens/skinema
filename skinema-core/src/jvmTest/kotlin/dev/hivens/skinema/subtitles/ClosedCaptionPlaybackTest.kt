package dev.hivens.skinema.subtitles

import dev.hivens.skinema.libav.ClosedCaptionFixture
import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Closed captions end to end: a file with no subtitle stream in it renders
 * text, because the captions were inside the video all along.
 *
 * This is the whole point of the different path. Every other subtitle kind is
 * a stream the pipeline demuxes for itself; CEA-608 is SEI the video decoder
 * lifts off each frame, so the track cannot be enumerated until a frame has
 * been through and the payloads have to reach the subtitle side from the
 * decode thread rather than from a container.
 */
class ClosedCaptionPlaybackTest {

    private val dir: Path = Files.createTempDirectory("skinema-cc-playback")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun awaitTrue(deadlineMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /**
     * The track appears only once captions have actually been seen, which is
     * the honest answer to a question the container cannot be asked. A file
     * without them must never advertise one.
     */
    @Test
    fun `a captioned file grows a caption track, and a plain one does not`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeClosedCaptions()
        val captioned = ClosedCaptionFixture.generate(dir, "cc.mp4", text = "HI")
        val plain = Fixtures.generate(
            dir.resolve("plain.mp4"),
            "-f", "lavfi", "-i", "color=c=navy:s=320x240:r=25:d=2",
            "-c:v", "libx264", "-preset", "ultrafast",
        )

        VideoPlayer(captioned, loop = false).use { player ->
            assertTrue(
                awaitTrue { player.subtitleTracks.any { it.codecName == "eia_608" } },
                "the caption track must appear once a frame carries captions, saw ${player.subtitleTracks.size}",
            )
        }

        VideoPlayer(plain, loop = false).use { player ->
            // Long enough that a file WITH captions would have advertised one
            // several times over, so this is a statement rather than a race.
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Ended }, "the plain file must play out")
            assertTrue(
                player.subtitleTracks.none { it.codecName == "eia_608" },
                "a file without captions advertised a caption track",
            )
        }
    }

    /** And selecting it puts the text on screen, through the ordinary overlay. */
    @Test
    fun `selecting the caption track renders its text`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        Fixtures.assumeClosedCaptions()
        val captioned = ClosedCaptionFixture.generate(dir, "render.mp4", text = "HI", seconds = "4")

        VideoPlayer(captioned, loop = true).use { player ->
            assertTrue(
                awaitTrue { player.subtitleTracks.any { it.codecName == "eia_608" } },
                "the caption track must appear",
            )
            val track = assertNotNull(player.subtitleTracks.firstOrNull { it.codecName == "eia_608" })
            player.setSubtitleCanvasSize(640, 480)
            player.selectSubtitleTrack(track.id)

            assertTrue(
                awaitTrue { player.activeSubtitleTrack == track.id },
                "the caption track must become the active one, got ${player.activeSubtitleTrack}",
            )
            var patches = 0
            assertTrue(
                awaitTrue(20_000) {
                    player.acquireSubtitles()?.let { patches = maxOf(patches, it.patches.size) }
                    patches > 0
                },
                "no caption pixels reached the overlay",
            )
        }
    }
}
