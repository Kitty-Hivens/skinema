package dev.hivens.skinema.player

import dev.hivens.skinema.audio.PacedPcmSink
import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Files whose picture and sound do not end together, which is an ordinary
 * thing -- any cut where the sound stops early, any clip laid over a longer
 * track. Each of these froze the timeline or moved it somewhere nobody
 * asked for, and each reported Playing while doing it.
 *
 * All of them need a line that plays in real time: [dev.hivens.skinema.audio.FakePcmSink]
 * calls every written frame played, so a tail never exists to be mishandled,
 * and BoundedPcmSink only moves when a test hands it frames.
 */
class MismatchedStreamEndsTest {

    private val dir: Path = Files.createTempDirectory("skinema-mismatched-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun ms(nanos: Long) = nanos / 1_000_000

    /** Picture [video] seconds long over sound [audio] seconds long. */
    private fun mismatched(name: String, video: String, audio: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-t", video, "-i", "testsrc2=size=64x64:rate=10",
        "-f", "lavfi", "-t", audio, "-i", "sine=frequency=440:sample_rate=44100",
        "-map", "0:v", "-map", "1:a",
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        "-c:a", "flac",
    )

    /**
     * A track switch after the playing track ran out. The switch left
     * [dev.hivens.skinema.audio.AudioPipeline]'s ended flag standing, which
     * stopped the line the switch had just opened, re-attached the mastered
     * clock to that stopped line's frozen position, and idled the pump for
     * good. The timeline died where it stood while state reported Playing,
     * and only a seek got out of it. The seek path had been given this
     * bookkeeping; the switch never was.
     */
    @Test
    fun `switching to a longer track after the short one ended keeps the timeline running`() {
        Fixtures.assumeDecodeEnvironment()
        val file = Fixtures.generate(
            dir.resolve("switch-after-end.mkv"),
            "-f", "lavfi", "-t", "30", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-t", "1", "-i", "sine=frequency=440:sample_rate=44100",
            "-f", "lavfi", "-t", "30", "-i", "sine=frequency=880:sample_rate=48000",
            "-map", "0:v", "-map", "1:a", "-map", "2:a",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "flac", "-disposition:a:0", "default",
        )
        val sink = PacedPcmSink(bufferFrames = 8_820)
        VideoPlayer(file, loop = false, audio = true, sink = sink).use { player ->
            try {
                assertTrue(awaitTrue { player.audioTracks.size == 2 }, "both tracks must surface")
                assertTrue(
                    awaitTrue(8_000) { player.acquireFrame(); player.positionNanos() > 1_300_000_000L },
                    "the one-second track must play out while the picture carries on",
                )
                player.selectAudioTrack(2)
                assertTrue(awaitTrue(4_000) { player.activeAudioTrack == 2 }, "the switch must land")

                val before = player.positionNanos()
                var frames = 0
                repeat(50) {
                    Thread.sleep(10)
                    if (player.acquireFrame() != null) frames++
                }
                val advanced = player.positionNanos() - before
                assertTrue(
                    advanced > 300_000_000L,
                    "the timeline must run on the new track, advanced ${ms(advanced)}ms in 500ms",
                )
                assertTrue(frames > 0, "the picture must keep flowing, saw $frames frames")
            } finally {
                sink.release()
            }
        }
    }

    /**
     * A seek into the stretch where only picture is left. The sound has
     * nothing at the target, so it declines to place the timeline -- where a
     * finished file rests is the player's to say -- and the player never said
     * it, because the clock re-anchor on the landing is reserved for players
     * that own their clock. The landing frame published against a timeline
     * still standing at the pre-seek position, so the picture froze for the
     * length of the jump while a scrubber showed the playhead back where it
     * had been.
     */
    @Test
    fun `a seek into the picture-only tail puts the timeline at the target`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = PacedPcmSink(bufferFrames = 8_820)
        VideoPlayer(mismatched("picture-tail.mkv", "4", "2"), loop = false, audio = true, sink = sink).use { player ->
            try {
                assertTrue(
                    awaitTrue(5_000) { player.acquireFrame(); player.positionNanos() > 200_000_000L },
                    "playback must run first",
                )
                player.seek(3_500_000_000L)
                var landed = -1L
                assertTrue(
                    awaitTrue(5_000) {
                        player.acquireFrame()?.let { landed = it.ptsNanos }
                        landed >= 3_500_000_000L
                    },
                    "the landing must publish, saw ${ms(landed)}ms",
                )
                assertTrue(
                    awaitTrue(1_000) { player.positionNanos() >= 3_400_000_000L },
                    "the timeline must follow the picture past the end of the sound, at ${ms(player.positionNanos())}ms",
                )
                var next = -1L
                assertTrue(
                    awaitTrue(1_000) {
                        player.acquireFrame()?.let { next = it.ptsNanos }
                        next > landed
                    },
                    "the picture must keep moving in the tail, stuck at ${ms(landed)}ms",
                )
            } finally {
                sink.release()
            }
        }
    }

    /**
     * The same seek on a looping player. The sound answered a target past
     * its last sample by restarting itself from zero and dragging the
     * mastered clock with it -- the sound deciding where a lap ends, which
     * is the mistake the orderly end of a track was already cured of. The
     * picture stood on the frame it had jumped to for a whole lap while the
     * track replayed underneath it, out of sync.
     */
    @Test
    fun `a looping seek past the end of the sound does not restart the lap`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = PacedPcmSink(bufferFrames = 8_820)
        VideoPlayer(mismatched("loop-tail.mkv", "4", "2"), loop = true, audio = true, sink = sink).use { player ->
            try {
                assertTrue(
                    awaitTrue(5_000) { player.acquireFrame(); player.positionNanos() > 200_000_000L },
                    "playback must run first",
                )
                player.seek(3_500_000_000L)
                var landed = -1L
                assertTrue(
                    awaitTrue(5_000) {
                        player.acquireFrame()?.let { landed = it.ptsNanos }
                        landed >= 3_500_000_000L
                    },
                    "the landing must publish, saw ${ms(landed)}ms",
                )
                assertTrue(
                    awaitTrue(1_000) { player.positionNanos() >= 3_400_000_000L },
                    "the sound's own end must not rewind the lap, at ${ms(player.positionNanos())}ms",
                )
                // The high-water mark, not the last frame acquired: the mailbox
                // keeps only the freshest, so a consumer that misses a tick
                // as the lap wraps reads the NEXT lap's opening frame, and a
                // last-value check can then never come true again. That is
                // what this caught on a slow runner -- the tail HAD played,
                // and the reading had already moved past it.
                //
                // The budget stays inside one lap on purpose. Given a whole
                // one, a seek that wrongly restarted the lap would reach this
                // mark too, just from the other end.
                var reached = -1L
                assertTrue(
                    awaitTrue(2_500) {
                        player.acquireFrame()?.let { if (it.ptsNanos > reached) reached = it.ptsNanos }
                        reached >= 3_800_000_000L
                    },
                    "the lap must play out its last half-second, reached ${ms(reached)}ms",
                )
            } finally {
                sink.release()
            }
        }
    }

    /**
     * A drag of the timeline into the stretch where only sound is left. The
     * demuxer runs out of frames at the target, and running out of FRAMES was
     * read as running out of FILE: the player ended on the spot, snapped the
     * picture back to the first frame and dropped the rest of the track. The
     * end of one stream is not the end of the file, which is the rule the
     * ordinary EOF path already keeps.
     */
    @Test
    fun `a seek past the last frame plays out the sound that is left`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = PacedPcmSink(bufferFrames = 8_820)
        VideoPlayer(mismatched("past-picture.mkv", "1", "4"), loop = false, audio = true, sink = sink).use { player ->
            try {
                assertTrue(
                    awaitTrue(5_000) { player.acquireFrame(); player.positionNanos() > 200_000_000L },
                    "playback must run first",
                )
                player.seek(2_000_000_000L)
                // Two seconds of sound are left from there; the file is not
                // over until they have been played.
                Thread.sleep(400)
                assertIs<VideoPlayer.State.Playing>(player.state, "the file is not over yet")
                assertTrue(
                    awaitTrue(8_000) { player.acquireFrame(); player.state is VideoPlayer.State.Ended },
                    "playback must end once the sound runs out, state=${player.state}",
                )
                assertEquals(player.durationNanos, player.positionNanos(), "the end is the duration")
            } finally {
                sink.release()
            }
        }
    }

    /**
     * Pause and resume where only sound is left. Resume re-anchors the sound
     * to the frame on screen, which is right while the picture is publishing
     * and wrong the moment it has run out: the last frame's pts stands still
     * while media time keeps going, so every resume dragged the playhead back
     * to the end of the picture and replayed the sound from there. The tail
     * became unreachable by pausing.
     */
    @Test
    fun `pause and resume in the sound-only tail does not rewind the timeline`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = PacedPcmSink(bufferFrames = 8_820)
        VideoPlayer(mismatched("sound-tail.mkv", "1", "4"), loop = false, audio = true, sink = sink).use { player ->
            try {
                assertTrue(
                    awaitTrue(15_000) { player.acquireFrame(); player.positionNanos() > 2_500_000_000L },
                    "the sound must outlive the picture and carry the timeline",
                )
                val before = player.positionNanos()
                player.pause()
                assertTrue(awaitTrue(3_000) { player.state is VideoPlayer.State.Paused }, "the pause must land")
                player.resume()
                assertTrue(awaitTrue(3_000) { player.state is VideoPlayer.State.Playing }, "the resume must land")
                Thread.sleep(200)
                val after = player.positionNanos()
                assertTrue(
                    after >= before - 100_000_000L,
                    "resume must carry on from ${ms(before)}ms, not rewind to ${ms(after)}ms",
                )
            } finally {
                sink.release()
            }
        }
    }
}
