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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The half of ASS where the file carries its own rendering rather than the
 * renderer imposing a style: vector drawings, animation, karaoke, clipping.
 *
 * The rest of the subtitle suite proves the pipeline -- when a cue is up, what
 * a seek replays, how bitmap windows close. None of it would notice if libass
 * stopped honouring an override tag, because a line of text still arrives
 * either way. What these assert is the geometry the tags dictate, which is the
 * only part of "it rendered correctly" that can be checked without pinning
 * pixels or a font.
 *
 * A vector drawing is the ideal subject for exactly that reason: it uses no
 * font at all, so its bounds are the file's arithmetic and nothing else's. The
 * tolerances below absorb libass's own rounding, not a font's metrics.
 */
class RichAssRenderingTest {

    private val dir: Path = Files.createTempDirectory("skinema-rich-ass-test")
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

    /**
     * A script at 640x480 carrying [events] verbatim, so a test writes the
     * override tags it is about and nothing else.
     */
    private fun writeAss(name: String, vararg events: String): Path = dir.resolve(name).also {
        val header = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 480

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,24,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,2,10,10,10,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent()
        Files.writeString(it, header + "\n" + events.joinToString("\n") + "\n")
    }

    /** mkv: a small video plus the script, muxed as ASS so the tags survive. */
    private fun fixture(name: String, subs: Path, seconds: Int): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
        "-i", subs.toString(),
        "-map", "0:v", "-map", "1", "-t", seconds.toString(),
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        "-c:s", "ass",
    )

    private fun trackOf(path: Path): SubtitleTrack =
        VideoDecoder.open(path).use { it.subtitleTracks().single() }

    /**
     * The pipeline over [path], rendering at the script's own 640x480 so the
     * coordinates a test writes are the coordinates it reads back.
     */
    private fun pipeline(path: Path) = SubtitlePipeline(path, clock, trackOf(path), 640 to 480)

    private class Latest {
        var overlay: SubtitleOverlay? = null
        fun poll(pipeline: SubtitlePipeline): SubtitleOverlay? {
            pipeline.acquire()?.let { overlay = it }
            return overlay
        }
    }

    /**
     * The single blended patch on screen once the clock has been moved to
     * [atMs] AND something newer has been published for it.
     *
     * The generation is what makes the second reading a reading: the mailbox
     * holds the last overlay for as long as nothing replaces it, so waiting
     * only for "a patch exists" is satisfied instantly by the previous one --
     * which is how the first version of the animation assertion watched a
     * shape refuse to move that had in fact moved.
     */
    private fun patchAt(pipeline: SubtitlePipeline, atMs: Long, latest: Latest): SubtitlePatch? {
        val before = latest.overlay?.generation ?: -1L
        frames.set(framesFor(atMs))
        awaitTrue {
            latest.poll(pipeline)
            val overlay = latest.overlay
            overlay != null && overlay.generation > before && overlay.patches.isNotEmpty()
        }
        return latest.overlay?.patches?.firstOrNull()
    }

    /**
     * Geometry within libass's own padding.
     *
     * A rasterised box is not the path's bounding box: measured here, a
     * 200x100 drawing arrives as 208x112 and a 100x50 one as 112x64. The
     * slack is what absorbs that, and it is deliberately smaller than any
     * difference the assertions are about -- a tag being honoured or not
     * moves these numbers by a hundred pixels, not by fourteen.
     */
    private fun assertNear(expected: Int, actual: Int, what: String, slack: Int = 16) {
        assertTrue(
            actual in (expected - slack)..(expected + slack),
            "$what should be about $expected, was $actual",
        )
    }

    /**
     * A vector drawing renders at the size the file states.
     *
     * The drawing mode is where ASS stops being text: the glyphs are the
     * file's own path commands, so nothing about this depends on a font being
     * installed, and the bounds are arithmetic. A renderer that dropped \p
     * would still publish an overlay -- of the literal path commands as text --
     * which is why the assertion is the rectangle's size rather than presence.
     */
    @Test
    fun `a vector drawing renders at the bounds the file states`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val path = fixture(
            "drawing.mkv",
            writeAss(
                "drawing.ass",
                "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,," +
                    "{\\an7\\pos(100,100)\\bord0\\shad0\\p1}m 0 0 l 200 0 200 100 0 100{\\p0}",
            ),
            5,
        )
        clock.start(0)
        val pipeline = pipeline(path)
        try {
            val patch = assertNotNull(patchAt(pipeline, 2_000, Latest()), "the drawing must render inside its window")
            assertNear(200, patch.width, "drawing width")
            assertNear(100, patch.height, "drawing height")
            assertNear(100, patch.x, "drawing x")
            assertNear(100, patch.y, "drawing y")
        } finally {
            pipeline.close()
        }
    }

    /**
     * An animated line is somewhere else later in its own window.
     *
     * \move is time inside a single cue, which the pipeline has no other test
     * for: every timing assertion elsewhere is about when a cue starts and
     * stops. A renderer that ignored the tag would put the shape at its start
     * position and leave it there, and the cue would still come and go on
     * time.
     */
    @Test
    fun `an animated drawing moves across its own cue`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val path = fixture(
            "move.mkv",
            writeAss(
                "move.ass",
                "Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,," +
                    "{\\an7\\bord0\\shad0\\move(50,50,400,300)\\p1}m 0 0 l 100 0 100 50 0 50{\\p0}",
            ),
            7,
        )
        clock.start(0)
        val pipeline = pipeline(path)
        try {
            val latest = Latest()
            // A tenth of a second into a four-second travel, so the shape is
            // barely off its start rather than on it -- an assertion on the
            // start coordinates exactly would be asserting the sample time.
            val start = assertNotNull(patchAt(pipeline, 1_100, latest), "the drawing must render at the start")
            assertNear(59, start.x, "start x")
            assertNear(56, start.y, "start y")

            val end = assertNotNull(patchAt(pipeline, 4_900, latest), "the drawing must still render at the end")
            assertTrue(end.x - start.x > 250, "the shape must travel across x, went ${start.x} -> ${end.x}")
            assertTrue(end.y - start.y > 180, "the shape must travel across y, went ${start.y} -> ${end.y}")
            // It moves, it does not grow: a renderer that scaled instead of
            // translating would satisfy the two above on its own.
            assertNear(100, end.width, "width through the move")
            assertNear(50, end.height, "height through the move")
        } finally {
            pipeline.close()
        }
    }

    /**
     * A karaoke line keeps changing while it stands.
     *
     * \k is the one tag whose whole output is a sweep: the text does not move
     * and the window does not change, so the only observable is that the
     * overlay is republished as the highlight advances. A renderer that
     * dropped the tag draws the line once and never again, which is why the
     * assertion counts republications rather than looking at a rectangle.
     */
    @Test
    fun `a karaoke line is republished as its highlight advances`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val path = fixture(
            "karaoke.mkv",
            writeAss(
                "karaoke.ass",
                "Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,," +
                    "{\\bord0\\shad0\\k100}Ka{\\k100}ra{\\k100}o{\\k100}ke",
            ),
            7,
        )
        clock.start(0)
        val pipeline = pipeline(path)
        try {
            val latest = Latest()
            assertNotNull(patchAt(pipeline, 1_500, latest), "the karaoke line must render")
            val generations = mutableListOf<Long>()
            for (at in listOf(1_500L, 2_500L, 3_500L, 4_500L)) {
                frames.set(framesFor(at))
                // Each sweep step is a fresh render with the same geometry, so
                // what says it happened is the mailbox's own generation.
                awaitTrue(3_000) {
                    latest.poll(pipeline)
                    (latest.overlay?.generation ?: 0L) > (generations.lastOrNull() ?: -1L)
                }
                latest.overlay?.let { generations += it.generation }
            }
            assertTrue(
                generations.zipWithNext().all { (a, b) -> b > a },
                "the line must be republished as the highlight advances, saw $generations",
            )
        } finally {
            pipeline.close()
        }
    }

    /**
     * The attachment walk picks the fonts out and leaves everything else.
     *
     * Anime mkv ships its typesetting faces as attachments, and they have to
     * reach libass before the renderer's font provider starts, or the file
     * renders in whatever the system happened to have. Both ways of getting
     * the walk wrong end as text in the wrong face rather than as a failure:
     * passing over a font because the container spelled its type the other
     * way, and handing over a cover image because it rode in the same
     * attachment stream. So the fixture carries one of each -- a font
     * declared by mimetype alone, and a plain-text attachment -- and the
     * count has to be one.
     */
    @Test
    fun `attached fonts are taken and other attachments are not`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val font = systemFont()
        org.junit.jupiter.api.Assumptions.assumeTrue(font != null, "no system font to attach")
        val notes = dir.resolve("notes.txt").also { Files.writeString(it, "not a font\n") }
        val subs = writeAss(
            "attach.ass",
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\bord0\\shad0}Typeset",
        )
        val path = Fixtures.generate(
            dir.resolve("attach.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", subs.toString(),
            "-map", "0:v", "-map", "1", "-t", "5",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s", "ass",
            // The font is declared by mimetype only, which is the half of
            // [isFontAttachment] a filename-led container would not exercise.
            "-attach", font.toString(), "-metadata:s:t:0", "mimetype=application/x-truetype-font",
            "-attach", notes.toString(), "-metadata:s:t:1", "mimetype=text/plain",
        )
        clock.start(0)
        val pipeline = pipeline(path)
        try {
            assertNotNull(patchAt(pipeline, 2_000, Latest()), "the line must render")
            assertEquals(1, pipeline.attachedFonts, "the font is taken and the text attachment is not")
            // And it got there in time. A face added after the renderer is
            // built is not in the provider it built, so the file renders in
            // whatever the system had -- no error, no missing text, just the
            // wrong shapes, which only a font no machine has installed could
            // tell apart from the right ones.
            assertTrue(
                pipeline.fontsAddedBeforeRenderer,
                "attachments must reach libass before the renderer builds its font provider",
            )
        } finally {
            pipeline.close()
        }
    }

    /** A TrueType face this machine already has, or null if none is where it is looked for. */
    private fun systemFont(): Path? = sequenceOf(
        "/usr/share/fonts/TTF/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans.ttf",
        "/System/Library/Fonts/Supplemental/Arial.ttf",
        "C:\\Windows\\Fonts\\arial.ttf",
    ).map(Path::of).firstOrNull { Files.exists(it) }

    /**
     * A clip constrains what is drawn.
     *
     * The same drawing twice, once masked, so the assertion is a comparison
     * rather than an absolute: whatever libass rounds, the clipped rectangle
     * has to be the smaller one. A renderer that ignored \clip would draw both
     * at full size and the two would match.
     */
    @Test
    fun `a clip rectangle constrains the drawn region`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val shape = "\\p1}m 0 0 l 200 0 200 200 0 200{\\p0}"
        val path = fixture(
            "clip.mkv",
            writeAss(
                "clip.ass",
                "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\an7\\pos(100,100)\\bord0\\shad0$shape",
                "Dialogue: 0,0:00:05.00,0:00:07.00,Default,,0,0,0,," +
                    "{\\an7\\pos(100,100)\\bord0\\shad0\\clip(100,100,200,150)$shape",
            ),
            9,
        )
        clock.start(0)
        val pipeline = pipeline(path)
        try {
            val latest = Latest()
            val whole = assertNotNull(patchAt(pipeline, 2_000, latest), "the unclipped drawing must render")
            assertNear(200, whole.width, "unclipped width")
            assertNear(200, whole.height, "unclipped height")

            val clipped = assertNotNull(patchAt(pipeline, 6_000, latest), "the clipped drawing must render")
            assertTrue(
                clipped.width < whole.width && clipped.height < whole.height,
                "the clip must shrink the drawn region, ${whole.width}x${whole.height} -> ${clipped.width}x${clipped.height}",
            )
            assertNear(100, clipped.width, "clipped width")
            assertNear(50, clipped.height, "clipped height")
        } finally {
            pipeline.close()
        }
    }
}
