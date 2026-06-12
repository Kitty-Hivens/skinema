package dev.hivens.skinema.ass

import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.subtitles.blendAssPatches
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke over the live binding. Asserts stay font-variant-proof: image
 * presence, bounds and the change flag -- never pixels (every CI runner
 * rasterizes with whatever fonts it has).
 */
class AssRenderingTest {

    private val arena = Arena.ofConfined()

    @AfterTest
    fun cleanup() {
        arena.close()
    }

    private val header = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 640
        PlayResY: 480

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Arial,24,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent()

    @Test
    fun `a cue renders inside the frame and the change flag settles`() {
        Fixtures.assumeSubtitleRendering()
        val library = Ass.libraryInit()
        assertTrue(library != MemorySegment.NULL, "library must initialize")
        try {
            val renderer = Ass.rendererInit(library)
            assertTrue(renderer != MemorySegment.NULL)
            try {
                Ass.setFrameSize(renderer, 640, 480)
                Ass.setStorageSize(renderer, 640, 480)
                Ass.setFonts(renderer, arena.allocateFrom("sans-serif"))
                val track = Ass.newTrack(library)
                assertTrue(track != MemorySegment.NULL)
                try {
                    val head = arena.allocateFrom(header)
                    Ass.processCodecPrivate(track, head, header.toByteArray().size)
                    // The mkv chunk form: ReadOrder,Layer,Style,... -- no timing.
                    val chunk = "1,0,Default,,0,0,0,,Hello subs"
                    Ass.processChunk(track, arena.allocateFrom(chunk), chunk.toByteArray().size, 500, 1_500)

                    val change = arena.allocate(JAVA_INT)
                    val midCue = Ass.parseImages(Ass.renderFrame(renderer, track, 1_000, change))
                    assertTrue(midCue.isNotEmpty(), "mid-cue render must produce images (are fonts installed?)")
                    for (p in midCue) {
                        assertTrue(p.width in 1..640 && p.height in 1..480, "image ${p.width}x${p.height} sane")
                        assertTrue(p.dstX >= 0 && p.dstY >= 0 && p.dstX + p.width <= 640 && p.dstY + p.height <= 480,
                            "image at (${p.dstX},${p.dstY}) ${p.width}x${p.height} must sit inside 640x480")
                        assertEquals(p.stride * (p.height - 1) + p.width, p.alpha.size, "the exact guaranteed allocation")
                    }

                    Ass.renderFrame(renderer, track, 1_000, change)
                    assertEquals(0, change.get(JAVA_INT, 0), "an identical instant must not re-rasterize")

                    val pastCue = Ass.parseImages(Ass.renderFrame(renderer, track, 5_000, change))
                    assertTrue(pastCue.isEmpty(), "past the cue nothing renders")

                    val blended = assertNotNull(blendAssPatches(midCue), "the mid-cue images blend to a patch")
                    assertTrue(blended.rgba.any { it.toInt() != 0 }, "the patch carries visible pixels")
                } finally {
                    Ass.freeTrack(track)
                }
            } finally {
                Ass.rendererDone(renderer)
            }
        } finally {
            Ass.libraryDone(library)
        }
    }
}
