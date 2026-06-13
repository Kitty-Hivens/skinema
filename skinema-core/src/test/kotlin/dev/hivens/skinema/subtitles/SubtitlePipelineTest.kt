package dev.hivens.skinema.subtitles

import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.SubtitleTrack
import dev.hivens.skinema.libav.VideoDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
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
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
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
}
