package dev.hivens.skinema.compose

import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.hivens.skinema.player.VideoPlayer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The composable a consumer puts on screen, rendered headlessly.
 *
 * Coverage said every instruction of its body had never run: the geometry
 * beside it is tested, the wiring that uses it was not -- the frame-clock
 * loop that drains the player's mailbox, the Skia images it owns across
 * recompositions, and the draw itself.
 *
 * Rendered through [ImageComposeScene] rather than the UI-test harness on
 * purpose. That harness synchronises on composition going idle, and this
 * surface's frame loop never completes, so it never does; the scene renders
 * on a frame time this test hands it and asks nothing about idleness.
 */
class VideoSurfaceRenderTest {

    private val dir: Path = Files.createTempDirectory("skinema-surface-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun ffmpegAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor() == 0
    }.getOrDefault(false)

    /** The same, plus a subtitle track, so the surface's text half has work. */
    private fun subbedClip(): Path {
        val srt = dir.resolve("subs.srt")
        Files.writeString(srt, "1\n00:00:00,200 --> 00:00:09,000\nTypeset\n")
        val out = dir.resolve("subbed.mkv")
        val p = ProcessBuilder(
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "color=c=red:size=64x48:rate=10", "-i", srt.toString(),
            "-map", "0:v", "-map", "1", "-t", "9",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s", "srt",
            out.toString(),
        ).redirectErrorStream(true).start()
        val log = p.inputStream.readAllBytes().decodeToString()
        check(p.waitFor() == 0) { "ffmpeg failed: $log" }
        return out
    }

    /** A solid red clip, so "did it paint" is a question about one pixel. */
    private fun redClip(): Path {
        val out = dir.resolve("red.mp4")
        val p = ProcessBuilder(
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "color=c=red:size=64x64:rate=10", "-t", "2",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            out.toString(),
        ).redirectErrorStream(true).start()
        val log = p.inputStream.readAllBytes().decodeToString()
        check(p.waitFor() == 0) { "ffmpeg failed: $log" }
        return out
    }

    /**
     * The state helper is the fallback branch of every consumer's player
     * cell -- what it shows while a file opens, and what it shows when one
     * fails. Core keeps the state in a plain volatile a composition cannot
     * watch, so this polls it on the frame clock; that poll had never run.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the state helper follows the player onto the frame clock`() {
        assumeTrue(ffmpegAvailable(), "no ffmpeg CLI -- the fixture cannot be built")
        val video = redClip()
        assumeTrue(runCatching { VideoPlayer(video, loop = true).close(); true }.getOrDefault(false), "no natives")

        VideoPlayer(video, loop = true).use { player ->
            var seen: VideoPlayer.State? = null
            ImageComposeScene(16, 16, Density(1f)) {
                seen = rememberPlayerState(player)
            }.use { scene ->
                var frame = 0L
                fun pump(until: () -> Boolean): Boolean {
                    val deadline = System.currentTimeMillis() + 20_000
                    while (!until() && System.currentTimeMillis() < deadline) {
                        scene.render(frame)
                        frame += 16_000_000L
                        Thread.sleep(10)
                    }
                    return until()
                }
                assertTrue(pump { seen is VideoPlayer.State.Playing }, "must reach Playing, saw $seen")
                // The change has to happen AFTER the first composition read the
                // state, or the initial value alone satisfies the assertion and
                // the poll -- the only thing under test -- is never needed.
                player.pause()
                assertTrue(pump { seen is VideoPlayer.State.Paused }, "the poll must carry the change, saw $seen")
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the surface paints the player's frames`() {
        assumeTrue(ffmpegAvailable(), "no ffmpeg CLI -- the fixture cannot be built")
        val video = redClip()
        // A player that cannot open its file leaves the surface drawing
        // nothing, which would fail this for the wrong reason.
        assumeTrue(runCatching { VideoPlayer(video, loop = true).close(); true }.getOrDefault(false), "no natives")

        VideoPlayer(video, loop = true).use { player ->
            ImageComposeScene(64, 64, Density(1f)) {
                VideoSurface(player, Modifier.size(64.dp))
            }.use { scene ->
                var painted = false
                var frame = 0L
                val deadline = System.currentTimeMillis() + 20_000
                while (!painted && System.currentTimeMillis() < deadline) {
                    // Each render resumes the surface's withFrameNanos, which
                    // is the only thing that drains the mailbox.
                    val image = scene.render(frame)
                    frame += 16_000_000L
                    val bitmap = image.peekPixels()
                    if (bitmap != null) {
                        val argb = bitmap.getColor(32, 32)
                        val r = (argb shr 16) and 0xFF
                        val g = (argb shr 8) and 0xFF
                        val b = argb and 0xFF
                        painted = r > 150 && g < 90 && b < 90
                    }
                    if (!painted) Thread.sleep(10)
                }
                assertTrue(painted, "the surface must put the decoded picture on the canvas")
            }
        }
    }

    /**
     * The surface tells the player what size to rasterize text at, and that
     * post is what papers over anything that loses the size elsewhere -- it
     * had never executed, along with the whole subtitle half of the draw.
     * A surface far larger than the video makes the answer unambiguous: the
     * canvas the overlay reports has to be the surface's rect, not the
     * video's own 64x48.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the surface tells the player what size to rasterize text at`() {
        assumeTrue(ffmpegAvailable(), "no ffmpeg CLI -- the fixture cannot be built")
        val video = subbedClip()
        assumeTrue(runCatching { VideoPlayer(video, loop = true).close(); true }.getOrDefault(false), "no natives")

        VideoPlayer(video, loop = true).use { player ->
            var canvas = 0 to 0
            ImageComposeScene(240, 180, Density(1f)) {
                VideoSurface(player, Modifier.size(240.dp, 180.dp))
            }.use { scene ->
                var frame = 0L
                val deadline = System.currentTimeMillis() + 25_000
                var selected = false
                while (canvas.first < 200 && System.currentTimeMillis() < deadline) {
                    if (!selected) {
                        player.subtitleTracks.firstOrNull()?.let {
                            player.selectSubtitleTrack(it.id)
                            selected = true
                        }
                    }
                    scene.render(frame)
                    frame += 16_000_000L
                    player.acquireSubtitles()?.let { canvas = it.canvasWidth to it.canvasHeight }
                    Thread.sleep(10)
                }
                // Skipped rather than failed where libass is absent: the
                // overlay then has no text to carry a canvas on, which is a
                // missing capability and not a broken surface.
                assumeTrue(selected, "no subtitle track to select")
                assertTrue(
                    canvas.first >= 200,
                    "the overlay must rasterize at the surface's rect, not the video's, saw $canvas",
                )
            }
        }
    }
}
