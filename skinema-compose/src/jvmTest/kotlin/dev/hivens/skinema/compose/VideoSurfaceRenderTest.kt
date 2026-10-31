package dev.hivens.skinema.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.hivens.skinema.player.VideoPlayer
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The composable a consumer puts on screen, rendered headlessly.
 *
 * Coverage said every instruction of its body had never run. The geometry
 * beside it is tested, and the wiring that uses it was not: the loop that
 * drains the player's mailbox, the Skia images it owns across recompositions,
 * and the draw itself.
 *
 * Rendered through [ImageComposeScene] rather than the UI-test harness on
 * purpose. That harness synchronises on composition going idle, and this
 * surface's loop never completes, so it never does. The scene renders on a
 * frame time this test hands it and asks nothing about idleness. It also
 * answers the question the surface's redraw rule is about: whether anything
 * is asking for a frame at all, which is [ImageComposeScene.hasInvalidations].
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
     * watch, so this waits on the player's change count. That wait had never
     * run.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the state helper follows the player`() {
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

    /**
     * The letterbox bars under [VideoScale.Fit]. The surface draws pixels and
     * nothing else by default, so a consumer composing nothing behind it got
     * whatever the window happened to hold there; a colour makes the bars the
     * surface's own. Sixty-four square of red into bounds half as tall leaves
     * a third of the width bare on each side, which is where this looks.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `Fit paints its letterbox in the colour it was given`() {
        assumeTrue(ffmpegAvailable(), "no ffmpeg CLI -- the fixture cannot be built")
        val video = redClip()
        assumeTrue(runCatching { VideoPlayer(video, loop = true).close(); true }.getOrDefault(false), "no natives")

        VideoPlayer(video, loop = true).use { player ->
            ImageComposeScene(64, 32, Density(1f)) {
                VideoSurface(
                    player,
                    Modifier.size(width = 64.dp, height = 32.dp),
                    scale = VideoScale.Fit,
                    background = Color.Blue,
                )
            }.use { scene ->
                var painted = false
                var frame = 0L
                val deadline = System.currentTimeMillis() + 20_000
                while (!painted && System.currentTimeMillis() < deadline) {
                    val image = scene.render(frame)
                    frame += 16_000_000L
                    val bitmap = image.peekPixels()
                    if (bitmap != null) {
                        val bar = bitmap.getColor(2, 16)
                        val picture = bitmap.getColor(32, 16)
                        val barBlue = bar and 0xFF
                        val barRed = (bar shr 16) and 0xFF
                        val pictureRed = (picture shr 16) and 0xFF
                        if (pictureRed > 200) {
                            // The picture landed, so the bar beside it is
                            // whatever the surface put there -- checked in the
                            // same render, or a torn read could pass on a
                            // frame the video had not reached yet.
                            assertTrue(barBlue > 200 && barRed < 60, "the bar must be the given colour, got $bar")
                            painted = true
                        }
                    }
                    Thread.sleep(10)
                }
                assertTrue(painted, "the surface never painted a frame to check the bars against")
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
                    // Each render lets the surface past the frame it waits on
                    // once it has handed a picture over, which is what lets it
                    // take the next one.
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
            var selected = false
            var activeSince = 0L
            val deadline = System.currentTimeMillis() + 25_000
            ImageComposeScene(240, 180, Density(1f)) {
                VideoSurface(player, Modifier.size(240.dp, 180.dp))
            }.use { scene ->
                var frame = 0L
                // Render only until the track has been ACTIVE for a second --
                // the post lives in the draw and needs nothing from the overlay.
                // A second of renders rather than a count of them: a render
                // re-runs the surface's draw only when the surface invalidated
                // it, which it does once the new track's first overlay arrives,
                // and a count of renders could run out before that on a slow
                // machine. Then close the scene: the surface takes overlays from
                // the mailbox itself, and a mailbox with one slot has one winner
                // per publish. Two readers made this a coin flip that a slower
                // machine lost. Stopping the renders used to be enough, back when
                // the surface read only on the frame clock. It now reads when the
                // player changes, so it has to be gone.
                //
                // Counting draws from the SELECT rather than from the track
                // going active was the other half of that coin, and the worse
                // half: selection is a command for the decode thread, which
                // builds the pipeline, and the surface posts nothing until it
                // exists. A slow build meant every counted draw saw no track,
                // the size was never posted, and rendering then stopped for
                // good -- so the wait below could not have succeeded however
                // long it was given.
                while (System.currentTimeMillis() < deadline) {
                    if (!selected) {
                        player.subtitleTracks.firstOrNull()?.let {
                            player.selectSubtitleTrack(it.id)
                            selected = true
                        }
                    }
                    scene.render(frame)
                    frame += 16_000_000L
                    if (player.activeSubtitleTrack != null && activeSince == 0L) activeSince = System.currentTimeMillis()
                    if (activeSince != 0L && System.currentTimeMillis() - activeSince > 1_000) break
                    Thread.sleep(10)
                }
            }
            assumeTrue(selected, "no subtitle track to select")
            assertTrue(
                activeSince != 0L,
                "the track never became active, so the surface never posted a size to wait for",
            )

            // The surface is gone, so nothing is taking frames any more and the
            // player would otherwise notice: an unread mailbox is an unwatched
            // player, and an unwatched player stops the clock the subtitle side
            // renders against. This test is a consumer that stopped taking
            // pictures and still wants the player to run, which is what saying
            // so exists for.
            player.setPresenting(true)

            while (canvas.first < 200 && System.currentTimeMillis() < deadline) {
                player.acquireSubtitles()?.let { canvas = it.canvasWidth to it.canvasHeight }
                Thread.sleep(10)
            }
            assertTrue(
                canvas.first >= 200,
                "the overlay must rasterize at the surface's rect, not the video's, saw $canvas",
            )
        }
    }

    /** A solid red clip at [fps], [seconds] long. */
    private fun redClipAt(fps: Int, seconds: Int): Path {
        val out = dir.resolve("red-$fps.mp4")
        val p = ProcessBuilder(
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "color=c=red:size=64x64:rate=$fps", "-t", "$seconds",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            out.toString(),
        ).redirectErrorStream(true).start()
        val log = p.inputStream.readAllBytes().decodeToString()
        check(p.waitFor() == 0) { "ffmpeg failed: $log" }
        return out
    }

    /**
     * Whether anything is asking the scene for a frame. A state write reaches
     * [ImageComposeScene.hasInvalidations] only once snapshot changes have been
     * applied, and this scene has no pump of its own that would apply them, so
     * without the call a surface invalidating by writing state would read as
     * asking for nothing.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.asksForFrame(): Boolean {
        Snapshot.sendApplyNotifications()
        return hasInvalidations()
    }

    private fun redAt(image: Image, x: Int, y: Int): Boolean {
        val argb = image.peekPixels()?.getColor(x, y) ?: return false
        return (argb shr 16) and 0xFF > 150 && (argb shr 8) and 0xFF < 90 && argb and 0xFF < 90
    }

    /**
     * The redraw rule itself: a surface on a paused player asks for no frames.
     * It used to ask for one on every refresh of the display, forever, to poll
     * a mailbox nothing was publishing into.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a surface on a paused player asks for no frames`() {
        assumeTrue(ffmpegAvailable(), "no ffmpeg CLI, so the fixture cannot be built")
        val video = redClip()
        assumeTrue(runCatching { VideoPlayer(video, loop = true).close(); true }.getOrDefault(false), "no natives")

        VideoPlayer(video, loop = true, startPaused = true).use { player ->
            ImageComposeScene(64, 64, Density(1f)) {
                VideoSurface(player, Modifier.size(64.dp))
            }.use { scene ->
                var frame = 0L
                var painted = false
                val deadline = System.currentTimeMillis() + 20_000
                while (!painted && System.currentTimeMillis() < deadline) {
                    painted = redAt(scene.render(frame), 32, 32)
                    frame += 16_000_000L
                    Thread.sleep(10)
                }
                assertTrue(painted, "the poster frame must be drawn first, or there is nothing to settle after")
                // Whatever the poster set off (the take after the draw, the
                // empty one after that) is given a moment and a few frames.
                repeat(5) {
                    scene.render(frame)
                    frame += 16_000_000L
                    Thread.sleep(50)
                }
                val settleDeadline = System.currentTimeMillis() + 2_000
                var quietSince = System.currentTimeMillis()
                while (System.currentTimeMillis() < settleDeadline) {
                    if (scene.asksForFrame()) {
                        scene.render(frame)
                        frame += 16_000_000L
                        quietSince = System.currentTimeMillis()
                    }
                    Thread.sleep(10)
                }
                assertTrue(
                    System.currentTimeMillis() - quietSince >= 1_000,
                    "a paused surface must stop asking for frames, last asked ${System.currentTimeMillis() - quietSince}ms ago",
                )
            }
        }
    }

    /** The same for the state helper: a spinner's worth of state is not a reason to redraw the window. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the state helper asks for no frames while nothing changes`() {
        assumeTrue(ffmpegAvailable(), "no ffmpeg CLI, so the fixture cannot be built")
        val video = redClip()
        assumeTrue(runCatching { VideoPlayer(video, loop = true).close(); true }.getOrDefault(false), "no natives")

        VideoPlayer(video, loop = true, startPaused = true).use { player ->
            var seen: VideoPlayer.State? = null
            ImageComposeScene(16, 16, Density(1f)) {
                seen = rememberPlayerState(player)
            }.use { scene ->
                var frame = 0L
                val deadline = System.currentTimeMillis() + 20_000
                while (seen !is VideoPlayer.State.Paused && System.currentTimeMillis() < deadline) {
                    scene.render(frame)
                    frame += 16_000_000L
                    Thread.sleep(10)
                }
                assertIs<VideoPlayer.State.Paused>(seen, "the helper must reach the paused state first")
                scene.render(frame)
                frame += 16_000_000L
                Thread.sleep(300)
                assertFalse(scene.asksForFrame(), "nothing changed, so nothing may ask for a frame")
            }
        }
    }

    /**
     * While playing, the frames asked for follow the file rather than whoever
     * is drawing. The caller here is willing to draw every two milliseconds,
     * which is a 500 Hz display, and a 10 fps clip has ten pictures a second to
     * give it. The old surface asked for every one of those refreshes.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a playing surface asks for frames at the file's rate, not the display's`() {
        assumeTrue(ffmpegAvailable(), "no ffmpeg CLI, so the fixture cannot be built")
        val video = redClipAt(fps = 10, seconds = 10)
        assumeTrue(runCatching { VideoPlayer(video, loop = true).close(); true }.getOrDefault(false), "no natives")

        VideoPlayer(video, loop = true).use { player ->
            ImageComposeScene(64, 64, Density(1f)) {
                VideoSurface(player, Modifier.size(64.dp))
            }.use { scene ->
                var frame = 0L
                var painted = false
                val paintDeadline = System.currentTimeMillis() + 20_000
                while (!painted && System.currentTimeMillis() < paintDeadline) {
                    painted = redAt(scene.render(frame), 32, 32)
                    frame += 2_000_000L
                    Thread.sleep(2)
                }
                assertTrue(painted, "playback must reach the screen before its rate can be measured")

                var renders = 0
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 2_000) {
                    if (scene.asksForFrame()) {
                        scene.render(frame)
                        renders++
                    }
                    frame += 2_000_000L
                    Thread.sleep(2)
                }
                // Two seconds of a 10 fps clip is twenty pictures. The bound
                // leaves room for a state change or two and for a loop wrap, and
                // stays far below the several hundred refreshes on offer.
                assertTrue(renders in 5..60, "expected about twenty frames asked for in two seconds, saw $renders")
            }
        }
    }

    /**
     * The one thing the frame clock still decides. A window that stops drawing
     * leaves the surface's request for a frame unanswered, the surface reports
     * the window out of sight, and drawing again brings it back. Waiting on the
     * player instead of polling on every refresh must not have turned a hidden
     * window into one that keeps a player decoding for nobody.
     *
     * The clip is silent, so the default policy pauses it.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a surface that stops being drawn lets the player notice, and drawing again revives it`() {
        assumeTrue(ffmpegAvailable(), "no ffmpeg CLI, so the fixture cannot be built")
        val video = redClipAt(fps = 30, seconds = 20)
        assumeTrue(runCatching { VideoPlayer(video, loop = true).close(); true }.getOrDefault(false), "no natives")

        VideoPlayer(video, loop = true).use { player ->
            ImageComposeScene(64, 64, Density(1f)) {
                VideoSurface(player, Modifier.size(64.dp))
            }.use { scene ->
                var frame = 0L
                fun drawWhile(condition: () -> Boolean, deadlineMs: Long) {
                    val deadline = System.currentTimeMillis() + deadlineMs
                    while (condition() && System.currentTimeMillis() < deadline) {
                        if (scene.asksForFrame()) scene.render(frame)
                        frame += 16_000_000L
                        Thread.sleep(8)
                    }
                }
                var painted = false
                val paintDeadline = System.currentTimeMillis() + 20_000
                while (!painted && System.currentTimeMillis() < paintDeadline) {
                    painted = redAt(scene.render(frame), 32, 32)
                    frame += 16_000_000L
                    Thread.sleep(8)
                }
                assertTrue(painted, "playback must reach the screen first")
                val start = System.currentTimeMillis()
                drawWhile({ System.currentTimeMillis() - start < 1_000 }, 2_000)
                assertIs<VideoPlayer.State.Playing>(player.state)

                // Hidden: nothing is drawn from here on.
                val hiddenDeadline = System.currentTimeMillis() + 15_000
                while (player.state !is VideoPlayer.State.Paused && System.currentTimeMillis() < hiddenDeadline) {
                    Thread.sleep(50)
                }
                assertIs<VideoPlayer.State.Paused>(player.state, "a window that draws nothing must be noticed")

                // Shown again.
                drawWhile({ player.state !is VideoPlayer.State.Playing }, 10_000)
                assertIs<VideoPlayer.State.Playing>(player.state, "drawing again must bring the player back")
            }
        }
    }

    /**
     * The case the mailbox notice could not see, and the reason the surface
     * reports at all. Measured under XWayland on Hyprland, a window on a
     * workspace that is not on screen does not stop drawing: it draws about
     * once a second. The surface then read once a second, which to the player
     * looked like a slow consumer, and a hidden window kept a 4K decode
     * running for nobody. Drawn here on the same schedule.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a window that draws once a second is taken for hidden, and drawing at full rate brings it back`() {
        assumeTrue(ffmpegAvailable(), "no ffmpeg CLI, so the fixture cannot be built")
        val video = redClipAt(fps = 30, seconds = 20)
        assumeTrue(runCatching { VideoPlayer(video, loop = true).close(); true }.getOrDefault(false), "no natives")

        VideoPlayer(video, loop = true).use { player ->
            ImageComposeScene(64, 64, Density(1f)) {
                VideoSurface(player, Modifier.size(64.dp))
            }.use { scene ->
                var frame = 0L
                var painted = false
                val paintDeadline = System.currentTimeMillis() + 20_000
                while (!painted && System.currentTimeMillis() < paintDeadline) {
                    painted = redAt(scene.render(frame), 32, 32)
                    frame += 16_000_000L
                    Thread.sleep(8)
                }
                assertTrue(painted, "playback must reach the screen first")

                val hiddenDeadline = System.currentTimeMillis() + 15_000
                while (player.state !is VideoPlayer.State.Paused && System.currentTimeMillis() < hiddenDeadline) {
                    scene.render(frame)
                    frame += 1_000_000_000L
                    Thread.sleep(1_000)
                }
                assertIs<VideoPlayer.State.Paused>(player.state, "a window drawing once a second must be taken for hidden")

                val shownDeadline = System.currentTimeMillis() + 10_000
                while (player.state !is VideoPlayer.State.Playing && System.currentTimeMillis() < shownDeadline) {
                    if (scene.asksForFrame()) scene.render(frame)
                    frame += 16_000_000L
                    Thread.sleep(8)
                }
                assertIs<VideoPlayer.State.Playing>(player.state, "drawing at full rate must bring the player back")
            }
        }
    }
}
