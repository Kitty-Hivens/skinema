package dev.hivens.skinema.subtitles

import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.SubtitleTrack
import dev.hivens.skinema.libav.VideoDecoder
import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pipeline under a manual clock: no wall-time guessing, every
 * transition handshaken. Render asserts stay font-variance-proof
 * (presence/geometry-stability, never pixels).
 */
class SubtitlePipelineTest {

    private val dir: Path = Files.createTempDirectory("skinema-subs-test")
    private val frames = AtomicLong(0)
    private val clock = AudioClock(48_000) { frames.get() }

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun framesFor(ms: Long): Long = ms * 48

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    // Cues: 1-3s "First line", 5-7s "Second line".
    private fun writeSrt(name: String): Path = dir.resolve(name).also {
        Files.writeString(
            it,
            "1\n00:00:01,000 --> 00:00:03,000\nFirst line\n\n2\n00:00:05,000 --> 00:00:07,000\nSecond line\n",
        )
    }

    private fun writeAss(name: String): Path = dir.resolve(name).also {
        Files.writeString(
            it,
            """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 480

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,24,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,First line
            Dialogue: 0,0:00:05.00,0:00:07.00,Default,,0,0,0,,Second line
            """.trimIndent(),
        )
    }

    /** mkv: testsrc2 video + one subtitle stream of [codec]. */
    private fun fixture(name: String, subs: Path, codec: String, seconds: Int): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
        "-i", subs.toString(),
        "-map", "0:v", "-map", "1", "-t", seconds.toString(),
        // Keyframes every 5s: real files carry dense cue points, and a
        // reposition must land INSIDE the file for the replay paths to
        // mean anything (a single keyframe would re-read from zero).
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "50",
        "-c:s", codec,
    )

    private fun trackOf(path: Path): SubtitleTrack =
        VideoDecoder.open(path).use { it.subtitleTracks().single() }

    private class Latest {
        var overlay: SubtitleOverlay? = null
        fun poll(pipeline: SubtitlePipeline): SubtitleOverlay? {
            pipeline.acquire()?.let { overlay = it }
            return overlay
        }
    }

    /**
     * The canvas announcement is idempotent, and the surface posts it from a
     * draw scope that runs on every painted frame.
     *
     * The handler compares too, and by then the cost is already paid: the
     * command is on an unbounded queue and this thread has been woken to read
     * it. A pump that reads a non-empty queue as work pending then refills a
     * packet at a time and never reaches its own render cadence -- so the
     * comparison has to happen where the call is made.
     *
     * Both directions, because a guard that swallowed a real resize would be
     * the worse defect: text would rasterize at the old size for good.
     */
    @Test
    fun `announcing the same canvas size twice queues one command`() {
        Fixtures.assumeDecodeEnvironment()
        // Subtitles, not just decode. setCanvasSize returns early on a dead
        // pipeline by contract, and a bundle with no subtitle decoder kills
        // this one during open() -- so on the core tier the property under
        // test is not merely absent, it is unobservable. Gated on decode
        // alone the test raced that teardown: it passed on every linux row
        // and lost on macos-arm64, reporting a working dedup as broken.
        Fixtures.assumeSubtitleRendering()
        val path = fixture("canvas.mkv", writeSrt("canvas.srt"), "srt", 10)
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), 64 to 48)
        try {
            // Said out loud, so a pipeline that died for some other reason
            // reports itself rather than arriving as a dedup failure. That is
            // how the race above had to be read out of a CI log.
            assertTrue(awaitTrue { pipeline.canvasSets > 0 || !pipeline.isDead }, "the pipeline died before it opened")
            assertFalse(pipeline.isDead, "a dead pipeline answers no announcement, so this proves nothing")

            repeat(120) { pipeline.setCanvasSize(800, 600) }
            assertEquals(1, pipeline.canvasSets, "a steady window must post once, not once a frame")

            pipeline.setCanvasSize(801, 600)
            assertEquals(2, pipeline.canvasSets, "a width that changed must get through")
            pipeline.setCanvasSize(801, 601)
            assertEquals(3, pipeline.canvasSets, "and so must a height")

            repeat(120) { pipeline.setCanvasSize(801, 601) }
            assertEquals(3, pipeline.canvasSets, "settling at a new size posts nothing more")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a cue appears inside its window and clears after it`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val path = fixture("window.mkv", writeSrt("window.srt"), "srt", 10)
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), 64 to 48)
        try {
            val latest = Latest()
            frames.set(framesFor(2_000))
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isNotEmpty() == true },
                "the cue must render inside its window",
            )
            frames.set(framesFor(4_000))
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isEmpty() == true },
                "past the window the overlay must clear",
            )
        } finally {
            pipeline.close()
        }
    }

    /**
     * THE replay pin: a backward seek must show the line exactly once.
     * Converted codecs re-number ReadOrder from a flushed counter -- an
     * unflushed track dedups the replay away (line vanishes) or, with
     * the counter alive, stacks a duplicate (libass collision handling
     * moves it up: the bbox grows taller). Geometry stability is the
     * discriminator; rasterization is deterministic on one machine.
     */
    private fun backwardSeekScenario(name: String, subs: Path, codec: String) {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val path = fixture(name, subs, codec, 10)
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), 64 to 48)
        try {
            val latest = Latest()
            frames.set(framesFor(2_000))
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isNotEmpty() == true },
                "the first showing must render",
            )
            val firstShown = latest.poll(pipeline)!!
            val genFirst = firstShown.generation
            val firstHeight = firstShown.patches.single().height
            val firstWidth = firstShown.patches.single().width

            // Move past the second cue and HANDSHAKE on its publish: the
            // seek must land against a different last render, or
            // detect_change rightfully publishes nothing.
            frames.set(framesFor(6_000))
            assertTrue(
                awaitTrue {
                    val o = latest.poll(pipeline)
                    o != null && o.generation > genFirst && o.patches.isNotEmpty()
                },
                "the second cue must render before the seek",
            )
            // The manual clock anchors at the CURRENT frame counter:
            // set the frames first or the seek lands in the past.
            val genBeforeSeek = latest.poll(pipeline)!!.generation
            frames.set(framesFor(2_000))
            clock.seek(2_000_000_000L)
            pipeline.seek(2_000_000_000L)
            assertTrue(awaitTrue { pipeline.pendingSeeks.get() == 0 }, "the seek must land")
            // Stale-proof: only a FRESH publish satisfies the await --
            // the pre-seek overlay lingers in the mailbox otherwise.
            assertTrue(
                awaitTrue {
                    val o = latest.poll(pipeline)
                    o != null && o.generation > genBeforeSeek && o.patches.isNotEmpty()
                },
                "the line must show again after the backward seek",
            )
            val second = latest.poll(pipeline)!!.patches.single()
            assertEquals(firstHeight, second.height, "a duplicated line stacks taller, a dropped one never shows")
            assertEquals(firstWidth, second.width)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a backward seek replays a converted track exactly once`() =
        backwardSeekScenario("replay-srt.mkv", writeSrt("replay.srt"), "srt")

    @Test
    fun `a backward seek replays a native ass track exactly once`() =
        backwardSeekScenario("replay-ass.mkv", writeAss("replay.ass"), "ass")

    @Test
    fun `a forward seek past the fed window keeps converted cues alive`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        // The converted-codec trap in its real shape: the decoder counter
        // resets on flush, so the cue at the LANDING re-numbers from zero
        // and collides with a long-fed early cue's ReadOrder -- without
        // the track flush libass dedups the NEW event away and the
        // landing plays bare.
        val srt = dir.resolve("farcue.srt")
        Files.writeString(
            srt,
            "1\n00:00:01,000 --> 00:00:03,000\nEarly line\n\n2\n00:00:40,000 --> 00:00:42,000\nLate line\n",
        )
        val path = fixture("farcue.mkv", srt, "srt", 45)
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), 64 to 48)
        try {
            val latest = Latest()
            frames.set(framesFor(2_000))
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isNotEmpty() == true },
                "the early cue must render (and burn its ReadOrder)",
            )
            val genBeforeSeek = latest.poll(pipeline)!!.generation
            frames.set(framesFor(41_000))
            clock.seek(41_000_000_000L)
            pipeline.seek(41_000_000_000L)
            assertTrue(awaitTrue { pipeline.pendingSeeks.get() == 0 }, "the seek must land")
            assertTrue(
                awaitTrue {
                    val o = latest.poll(pipeline)
                    o != null && o.generation > genBeforeSeek && o.patches.isNotEmpty()
                },
                "the landing cue must survive the ReadOrder collision",
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a loop wrap recovers events a forward seek flushed away`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        // Converted codec: the forward seek flushes the track; the events
        // before the landing are gone. A wrap (backward clock jump with
        // no command) must reposition and re-feed them, or lap 2 plays
        // bare.
        val path = fixture("wrap.mkv", writeSrt("wrap.srt"), "srt", 45)
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), 64 to 48)
        try {
            val latest = Latest()
            frames.set(framesFor(40_000))
            clock.seek(40_000_000_000L)
            pipeline.seek(40_000_000_000L)
            assertTrue(awaitTrue { pipeline.pendingSeeks.get() == 0 }, "the flushing seek must land")

            // The wrap: time falls back with no seek command.
            val genBeforeWrap = latest.poll(pipeline)?.generation ?: 0L
            frames.set(framesFor(100))
            clock.seek(100_000_000L)
            Thread.sleep(100)
            frames.set(framesFor(2_000))
            clock.seek(2_000_000_000L)
            assertTrue(
                awaitTrue {
                    val o = latest.poll(pipeline)
                    o != null && o.generation > genBeforeWrap && o.patches.isNotEmpty()
                },
                "lap 2 must re-show the flushed cue",
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `the demux horizon trails the clock, not the file`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        // Cues live in the first seconds; a refill gated on SUBTITLE pts
        // would chase the next cue to EOF and read the whole minute.
        val path = fixture("horizon.mkv", writeSrt("horizon.srt"), "srt", 60)
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), 64 to 48)
        try {
            assertTrue(
                awaitTrue { pipeline.lastDemuxedPtsNanos > 20_000_000_000L },
                "the refill must run ahead of the clock",
            )
            Thread.sleep(300)
            val high = pipeline.lastDemuxedPtsNanos
            assertTrue(
                high < 36_000_000_000L,
                "the refill must stop at the horizon, read to ${high / 1_000_000_000}s",
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a standing clock publishes nothing new`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val path = fixture("standing.mkv", writeSrt("standing.srt"), "srt", 10)
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), 64 to 48)
        try {
            val latest = Latest()
            frames.set(framesFor(2_000))
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isNotEmpty() == true },
                "the cue must render",
            )
            val generation = latest.poll(pipeline)!!.generation
            // ~20 render ticks with nothing changing: detect_change must
            // gate every one of them, or static dialogue costs a blend
            // and a publish per tick.
            Thread.sleep(300)
            latest.poll(pipeline)
            assertEquals(generation, latest.overlay!!.generation, "an unchanged render must not publish")
        } finally {
            pipeline.close()
        }
    }

    private fun pgsMkv(name: String): Path {
        val sup = dir.resolve("$name.sup")
        // The muxer normalizes the input to start at zero; authoring
        // from zero keeps the schedule deterministic. Window: [0, 2s).
        Files.write(sup, SupBuilder.build(showMs = 0, clearMs = 2_000))
        return Fixtures.generate(
            dir.resolve("$name.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", sup.toString(),
            "-map", "0:v", "-map", "1", "-t", "6",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "50",
            "-c:s", "copy",
        )
    }

    @Test
    fun `a pgs window shows its bitmap and clears on schedule`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeBitmapSubtitles()
        val path = pgsMkv("pgs-window")
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), null)
        try {
            val latest = Latest()
            frames.set(framesFor(1_000))
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isNotEmpty() == true },
                "the bitmap must show inside its window",
            )
            val overlay = latest.poll(pipeline)!!
            assertEquals(320, overlay.canvasWidth, "the canvas is the pgs plane")
            assertEquals(240, overlay.canvasHeight)
            val patch = overlay.patches.single()
            assertEquals(10, patch.x)
            assertEquals(20, patch.y)
            assertEquals(32, patch.width)
            assertEquals(16, patch.height)
            val center = (8 * 32 + 16) * 4
            assertEquals(255, patch.rgba[center + 3].toInt() and 0xFF, "opaque where the rect is")
            assertTrue((patch.rgba[center].toInt() and 0xFF) > 200, "the palette entry is white-ish")

            frames.set(framesFor(3_000))
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isEmpty() == true },
                "the clear set must empty the overlay",
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a seek lands inside a pgs window through the preroll`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeBitmapSubtitles()
        val path = pgsMkv("pgs-seek")
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), null)
        try {
            val latest = Latest()
            // Cold-start straight into the middle of the window: the
            // display set began earlier and only the preroll replay can
            // recover it.
            frames.set(framesFor(1_000))
            clock.seek(1_000_000_000L)
            pipeline.seek(1_000_000_000L)
            assertTrue(awaitTrue { pipeline.pendingSeeks.get() == 0 }, "the seek must land")
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isNotEmpty() == true },
                "the mid-window landing must show the bitmap",
            )

            // And out again: past the schedule nothing may linger.
            frames.set(framesFor(5_000))
            clock.seek(5_000_000_000L)
            pipeline.seek(5_000_000_000L)
            assertTrue(awaitTrue { pipeline.pendingSeeks.get() == 0 })
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isEmpty() == true },
                "a stale bitmap must not survive the seek",
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `an attached font feeds libass without breaking the open`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        // A real system font attached the anime-mkv way; the smoke is
        // that the pipeline opens, registers it and still renders.
        val font = Fixtures.hostFont()
        org.junit.jupiter.api.Assumptions.assumeTrue(font != null, "this machine ships no font to attach")
        val path = Fixtures.generate(
            dir.resolve("fonted.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", writeAss("fonted.ass").toString(),
            "-map", "0:v", "-map", "1", "-t", "5",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s", "ass",
            "-attach", font!!.toAbsolutePath().toString(), "-metadata:s:t:0", "mimetype=font/ttf",
        )
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), 64 to 48)
        try {
            val latest = Latest()
            frames.set(framesFor(2_000))
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isNotEmpty() == true },
                "the cue must render with the attachment registered",
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a track that cannot open dies clear and harmless`() {
        Fixtures.assumeDecodeEnvironment()
        val path = fixture("dead.mkv", writeSrt("dead.srt"), "srt", 5)
        clock.start(0)
        // Stream 0 is the VIDEO stream; opening it as a subtitle track
        // must fail closed.
        val bogus = SubtitleTrack(
            id = 0, streamIndex = 0, language = null, title = null,
            codecName = "h264", isText = false, isDefault = false, isForced = false,
        )
        val pipeline = SubtitlePipeline(path, clock, bogus, null)
        try {
            assertTrue(awaitTrue { pipeline.isDead }, "the pipeline must fail closed")
            assertTrue(
                awaitTrue { pipeline.acquire()?.patches?.isEmpty() == true },
                "death publishes a clear",
            )
            assertEquals(0, pipeline.pendingSeeks.get())
        } finally {
            pipeline.close()
        }
    }

    /**
     * A seek reaches this side BEFORE the clock does: the video landing is
     * a keyframe jump plus a decode-forward run, seconds on sparse
     * keyframes, and only then is the clock re-anchored. Gated on the clock
     * alone, a backward seek read forward to the OLD position plus a whole
     * horizon -- 85 seconds of packets for a jump from 60s back to 5s, fed
     * through a decoder and a libass track the reposition had just flushed.
     */
    @Test
    fun `a backward seek reads ahead of its target, not of the clock it left`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val path = fixture("stale-horizon.mkv", writeSrt("stale-horizon.srt"), "srt", 120)
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), 64 to 48)
        try {
            frames.set(framesFor(60_000))
            clock.seek(60_000_000_000L)
            assertTrue(
                awaitTrue { pipeline.lastDemuxedPtsNanos > 80_000_000_000L },
                "the refill must first run ahead of the clock, at ${pipeline.lastDemuxedPtsNanos / 1_000_000}ms",
            )

            // The seek as the player issues it: announced here, with the
            // clock still standing where the landing has not moved it from.
            pipeline.seek(5_000_000_000L)
            assertTrue(awaitTrue { pipeline.pendingSeeks.get() == 0 }, "the seek must land")
            Thread.sleep(500)
            val high = pipeline.lastDemuxedPtsNanos
            assertTrue(
                high < 45_000_000_000L,
                "the target plus one horizon is 35s; read to ${high / 1_000_000_000}s",
            )
        } finally {
            pipeline.close()
        }
    }

    /**
     * The bitmap schedule holds decoded pixels -- they convert once, at
     * ingest -- so what it keeps behind the playhead is paid for in memory
     * for nothing. It used to keep a minute of played-out windows, which on
     * ordinary dialogue density is dozens of them; at 1080p rect sizes that
     * is tens of megabytes of subtitles nobody can ever see again.
     */
    @Test
    fun `the bitmap schedule does not keep windows the clock has passed`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeBitmapSubtitles()
        val sup = dir.resolve("dense.sup")
        // A window every two seconds, visible for one, over two minutes.
        Files.write(sup, SupBuilder.buildMany(count = 60, periodMs = 2_000, visibleMs = 1_000))
        val path = Fixtures.generate(
            dir.resolve("dense.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", sup.toString(),
            "-map", "0:v", "-map", "1", "-t", "120",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "50",
            "-c:s", "copy",
        )
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), null)
        try {
            var peak = 0L
            for (secs in 1..90) {
                frames.set(framesFor(secs * 1_000L))
                Thread.sleep(15)
                peak = maxOf(peak, pipeline.scheduledBitmapBytes)
            }
            // 32x16 RGBA per window. What the read-ahead horizon holds is
            // deliberate -- 30s of it, so about fifteen windows here; the
            // bound is what separates that from a schedule that also keeps
            // its past, which measured at forty-six.
            val windows = peak / (32 * 16 * 4)
            assertTrue(windows > 0, "the schedule must actually hold something to bound")
            assertTrue(
                windows < 28,
                "the schedule peaked at $windows windows -- it is keeping what the clock has passed",
            )
        } finally {
            pipeline.close()
        }
    }

    /**
     * The player announces a seek to this side before the clock reaches the
     * target -- it queues the command as the seek is issued, while the clock
     * only moves once the audio thread reaches its own copy, between blocking
     * writes. So the landing arrives here as a large backward step with no
     * command behind it, which is exactly the shape of a loop wrap, and the
     * wrap rule repositioned a second time to a target it had already reached:
     * another demuxer seek, another ten-second preroll replay, and for a
     * converted codec another track flush, which blanks the screen for a tick
     * at the moment the seek completes.
     */
    @Test
    fun `an announced seek landing is not mistaken for a loop wrap`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val path = fixture("landing.mkv", writeSrt("landing.srt"), "srt", 120)
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), 64 to 48)
        try {
            frames.set(framesFor(60_000))
            assertTrue(awaitTrue { pipeline.repositions == 0 && pipeline.lastDemuxedPtsNanos > 0 }, "playback must run")

            // The announcement, with the clock still standing where the
            // landing has not moved it from.
            pipeline.seek(5_000_000_000L)
            assertTrue(awaitTrue { pipeline.pendingSeeks.get() == 0 }, "the seek must land")
            val afterSeek = pipeline.repositions
            assertEquals(1, afterSeek, "the announced seek repositions once")

            // The landing: the clock arrives where it was told to go, a full
            // 55 seconds backward and with nothing queued behind it. Both
            // halves are needed -- the device position AND the deliberate
            // re-anchor, because the mastered clock has a monotonic floor and
            // a device that merely reports a lower position cannot move it
            // back. That is what the audio pipeline does at its own landing.
            frames.set(framesFor(5_000))
            clock.seek(5_000_000_000L)
            Thread.sleep(300)
            assertEquals(
                afterSeek,
                pipeline.repositions,
                "the clock arriving where it was sent is not a wrap",
            )
        } finally {
            pipeline.close()
        }
    }

    /**
     * The read-ahead horizon bounds the schedule in time, and time is not
     * what it costs: bitmap pixels convert once, at ingest, so a horizon
     * holds however much the stream chose to put in it. Dialogue PGS is a
     * few megabytes; a full-plane 1080p signs track is eight per
     * presentation set, several a second, and the thirty seconds ahead plus
     * the ten of preroll a seek replays arrive before one window is evicted.
     *
     * The budget is a constructor parameter for the same reason the audio
     * pipeline's stall bound is: the real one is measured in tens of
     * megabytes, and a fixture that reached it would have to be enormous.
     */
    @Test
    fun `the bitmap schedule stops reading ahead once it is holding too much`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeBitmapSubtitles()
        val sup = dir.resolve("budget.sup")
        Files.write(sup, SupBuilder.buildMany(count = 60, periodMs = 2_000, visibleMs = 1_000))
        val path = Fixtures.generate(
            dir.resolve("budget.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", sup.toString(),
            "-map", "0:v", "-map", "1", "-t", "120",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "50",
            "-c:s", "copy",
        )
        clock.start(0)
        val window = 32 * 16 * 4
        // Four windows of budget, against a horizon that holds about fifteen.
        val pipeline = SubtitlePipeline(path, clock, trackOf(path), null, maxScheduledBitmapBytes = 4L * window)
        try {
            var peak = 0L
            var sawPatches = false
            val latest = Latest()
            for (secs in 1..60) {
                frames.set(framesFor(secs * 1_000L))
                Thread.sleep(15)
                peak = maxOf(peak, pipeline.scheduledBitmapBytes)
                if (latest.poll(pipeline)?.patches?.isNotEmpty() == true) sawPatches = true
            }
            val windows = peak / window
            assertTrue(windows > 0, "the schedule must actually hold something to bound")
            assertTrue(
                windows <= 6,
                "the schedule peaked at $windows windows against a budget of four",
            )
            // A budget that stopped the pipeline rather than its read-ahead
            // would satisfy the bound above and show nothing.
            assertTrue(sawPatches, "subtitles must still be published under the budget")
        } finally {
            pipeline.close()
        }
    }

    /**
     * A pipeline that failed closed keeps its reference in the player, and
     * the player announces every seek to whatever it is holding. Each one
     * queued a command on a thread that had already exited and raised a
     * counter nobody would ever lower, so a scrubbed timeline leaked one
     * per press for as long as the file was open.
     */
    @Test
    fun `a dead pipeline accepts no more work`() {
        Fixtures.assumeDecodeEnvironment()
        val path = fixture("deaf.mkv", writeSrt("deaf.srt"), "srt", 5)
        clock.start(0)
        val bogus = SubtitleTrack(
            id = 0, streamIndex = 0, language = null, title = null,
            codecName = "h264", isText = false, isDefault = false, isForced = false,
        )
        val pipeline = SubtitlePipeline(path, clock, bogus, null)
        try {
            assertTrue(awaitTrue { pipeline.isDead }, "the pipeline must fail closed")
            // Past the flag, so this is the steady state and not the race.
            Thread.sleep(50)
            repeat(500) { pipeline.seek(it * 1_000_000L) }
            pipeline.setCanvasSize(100, 100)
            Thread.sleep(50)
            assertEquals(0, pipeline.pendingSeeks.get(), "a dead pipeline took the seeks anyway")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `the attachment rule takes fonts and leaves everything else`() {
        // Either half may be the one that says so: containers disagree about
        // whether the mimetype or the filename carries the truth, and anime
        // releases in the wild use both. What must never pass is the cover
        // art and the chapter thumbnails riding the same attachment stream.
        assertTrue(SubtitlePipeline.isFontAttachment("font/ttf", null))
        assertTrue(SubtitlePipeline.isFontAttachment("application/x-truetype-font", "whatever"))
        assertTrue(SubtitlePipeline.isFontAttachment("APPLICATION/VND.MS-OPENTYPE", null))
        assertTrue(SubtitlePipeline.isFontAttachment(null, "Typeset.OTF"))
        assertTrue(SubtitlePipeline.isFontAttachment(null, "collection.ttc"))
        assertTrue(SubtitlePipeline.isFontAttachment("application/octet-stream", "signs.ttf"))

        assertFalse(SubtitlePipeline.isFontAttachment(null, null))
        assertFalse(SubtitlePipeline.isFontAttachment("image/png", "cover.png"))
        assertFalse(SubtitlePipeline.isFontAttachment("application/octet-stream", "notes.txt"))
        assertFalse(SubtitlePipeline.isFontAttachment("", ""))
    }

    @Test
    fun `a bitmap rect is sized from its geometry, or refused`() {
        // The rect comes from a decoder, so a real file cannot reach the
        // refusals -- which is exactly why the decision is held here rather
        // than through a fixture. What it guards is the narrowing: the size
        // is computed in Long and used as an Int, so a rect claiming an
        // implausible geometry would pick its allocation out of a truncated
        // number.
        assertEquals(1920 * 1080, SubtitlePipeline.indexPlaneBytes(1920, 1080, 1920))
        // Padded rows: the last one carries only its width.
        assertEquals(2048 * 99 + 100, SubtitlePipeline.indexPlaneBytes(100, 100, 2048))

        assertNull(SubtitlePipeline.indexPlaneBytes(0, 100, 100), "no width is no rect")
        assertNull(SubtitlePipeline.indexPlaneBytes(100, 0, 100), "no height is no rect")
        assertNull(SubtitlePipeline.indexPlaneBytes(100, 100, 0), "no stride is no rect")
        assertNull(SubtitlePipeline.indexPlaneBytes(-1, 100, 100))
        // Beyond any subtitle, and beyond what an Int would carry honestly.
        assertNull(SubtitlePipeline.indexPlaneBytes(100, 100_000, 100_000), "an implausible plane is refused")
        assertNull(SubtitlePipeline.indexPlaneBytes(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))

        // The plane is not what gets held. It converts to RGBA at four bytes
        // a pixel, and the only thing tying the two together is a linesize
        // the same rect supplies -- so a geometry that would be an enormous
        // allocation can arrive inside a plane of a few kilobytes.
        assertNull(
            SubtitlePipeline.indexPlaneBytes(8192, 8192, 1),
            "16 KiB of plane must not buy 256 MiB of pixels",
        )
        // width * height * 4 is Int arithmetic: this one lands on exactly
        // 2^31, which is a negative array size.
        assertNull(
            SubtitlePipeline.indexPlaneBytes(65_536, 8_192, 1),
            "a geometry whose RGBA size overflows an Int is refused",
        )
        // A stride narrower than the row is not a stride.
        assertNull(SubtitlePipeline.indexPlaneBytes(100, 100, 99), "a linesize below the width is no rect")
    }

    /**
     * "Nobody said" and "said zero" are different answers, and a fine time
     * base makes the second one common: at 90 kHz -- mov_text in mp4, every
     * stream in MPEG-TS -- a duration under 90 units divides away to nothing.
     */
    @Test
    fun `a duration that floors to zero is no duration at all`() {
        // 90 kHz: a full second survives, and 89 units do not.
        assertEquals(
            1_000L,
            SubtitlePipeline.declaredDurationMs(90_000, 1, 90_000, 0, 0),
        )
        assertNull(
            SubtitlePipeline.declaredDurationMs(89, 1, 90_000, 0, 0),
            "a sub-millisecond packet duration is not a length",
        )
        // ...and the display times still get their say when it does.
        assertEquals(
            400L,
            SubtitlePipeline.declaredDurationMs(89, 1, 90_000, 100, 500),
            "a floored packet duration must not shadow a declared window",
        )
        // A millisecond grid, where the packet's own number carries.
        assertEquals(
            15_000L,
            SubtitlePipeline.declaredDurationMs(15_000, 1, 1_000, 0, 0),
        )
        assertNull(SubtitlePipeline.declaredDurationMs(0, 1, 1_000, 0, 0), "nothing said is null")
    }

    /**
     * A schedule shows the last window covering the moment, so a window that
     * outlives its successor is not hidden by it -- only postponed. It
     * reappeared the instant the shorter one closed.
     */
    @Test
    fun `a window ends where the next one begins, whatever it declared`() {
        // Ten seconds declared, replaced after two: it ends at two.
        assertEquals(2_000L, SubtitlePipeline.windowTruncatedAt(10_000L, 2_000L))
        // Open-ended, the case that was already handled.
        assertEquals(2_000L, SubtitlePipeline.windowTruncatedAt(Long.MAX_VALUE, 2_000L))
        // A window that ends before its successor keeps its own end.
        assertEquals(1_000L, SubtitlePipeline.windowTruncatedAt(1_000L, 2_000L))
        // Back to back: no change either way.
        assertEquals(2_000L, SubtitlePipeline.windowTruncatedAt(2_000L, 2_000L))
    }

    @Test
    fun `a declared window is honoured however long it is`() {
        // Measured against the real thing: an srt cue reading 20s to 35s
        // reports a packet duration of 15000 ms, and the old ladder threw
        // that away -- it treated anything at or past the ten-second default
        // as "nobody knows" and left the cue open-ended, so a long title card
        // cleared on the next event or never.
        val start = 1_000_000_000L
        assertEquals(
            start + 15_000L * 1_000_000,
            SubtitlePipeline.bitmapWindowEnd(start, 0, 0, 15_000),
            "fifteen seconds is fifteen seconds",
        )
        assertEquals(
            start + 10_000L * 1_000_000,
            SubtitlePipeline.bitmapWindowEnd(start, 0, 0, 10_000),
            "and so is exactly ten, which used to be the sentinel",
        )
        // The cue's own window wins over the packet's, when it has one.
        assertEquals(
            start + 400L * 1_000_000,
            SubtitlePipeline.bitmapWindowEnd(start, 100, 500, 9_000),
        )
        // Nothing said: up until whatever comes next.
        assertEquals(Long.MAX_VALUE, SubtitlePipeline.bitmapWindowEnd(start, 0, 0, null))
        assertEquals(Long.MAX_VALUE, SubtitlePipeline.bitmapWindowEnd(start, 0, 0, 0))
    }
}

/**
 * The track switch, which is a player gesture rather than a pipeline one:
 * selecting another track spawns a fresh pipeline and lets the old one die
 * asynchronously. Its own mailbox dies with it, so what the consumer holds
 * is the OLD track's overlay until the newcomer publishes something -- and
 * a newcomer with nothing at the playhead has nothing to publish, possibly
 * for minutes. Measured against a track whose next cue was fifteen seconds
 * out, the previous track's line simply never went away.
 */
class SubtitleTrackSwitchTest {

    private val dir: java.nio.file.Path = Files.createTempDirectory("skinema-subs-switch")

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

    @Test
    fun `switching to a track with nothing at the playhead clears the old cue`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val a = dir.resolve("alpha.srt")
        Files.writeString(a, "1\n00:00:01,000 --> 00:00:06,000\nAlpha line\n")
        val b = dir.resolve("bravo.srt")
        Files.writeString(b, "1\n00:00:20,000 --> 00:00:25,000\nBravo line\n")
        val path = Fixtures.generate(
            dir.resolve("two-tracks.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", a.toString(), "-i", b.toString(),
            "-map", "0:v", "-map", "1", "-map", "2", "-t", "30",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "50",
            "-c:s", "srt",
        )
        VideoPlayer(path, loop = false, audio = false).use { player ->
            assertTrue(awaitTrue { player.subtitleTracks.size == 2 }, "both tracks must surface")
            val ids = player.subtitleTracks.map { it.id }
            player.selectSubtitleTrack(ids[0])
            assertTrue(
                awaitTrue(8_000) {
                    player.acquireFrame()
                    player.acquireSubtitles()?.patches?.isNotEmpty() == true
                },
                "the first track's cue must render before the switch means anything",
            )

            player.selectSubtitleTrack(ids[1])
            assertTrue(
                awaitTrue(3_000) {
                    player.acquireFrame()
                    player.acquireSubtitles()?.patches?.isEmpty() == true
                },
                "the new track has nothing here, so the old track's line must go",
            )
        }
    }

}
