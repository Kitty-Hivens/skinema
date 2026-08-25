package dev.hivens.skinema.subtitles

import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.VideoDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DVB subtitles -- the broadcast bitmap format (ETSI EN 300 743), as carried
 * by DVB-T/S/C recordings.
 *
 * They reach the consumer through the branch PGS and VobSub already use:
 * composed pixels with their own palette, no fonts, no libass. That is the
 * whole reason this needed a decoder in the bundle and no code -- and it is
 * also why it needs a test of its own, because "the bitmap path works" was
 * only ever asserted against one codec.
 *
 * The fixture is a transcode rather than an encode from text: ffmpeg refuses
 * text-to-bitmap outright ("subtitle encoding currently only possible from
 * text to text or bitmap to bitmap"), so the source is the PGS stream this
 * suite already synthesises byte by byte, re-encoded into DVB segments.
 */
class DvbSubtitleTest {

    private val dir: Path = Files.createTempDirectory("skinema-dvb-test")
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
     * 320x240 video with one DVB subtitle track, carrying the rectangle the
     * PGS builder writes, re-encoded into DVB segments.
     *
     * What the fixture can and cannot say is the encoder's doing, and it is
     * worth writing down rather than rediscovering. The segments it produces
     * are valid -- the decoder reads them -- but only the first cue gets an
     * honest timestamp, and only under -copyts: without it the stream is
     * rebased to zero, and every packet after the first comes out at
     * 0xFFFFFFFF milliseconds, the "unset" value, which the muxer stores
     * literally. So the fixture is one cue that begins when it says it does
     * and never ends. The clearing half of the schedule is asserted against
     * PGS, which shares the code.
     */
    private fun dvbFixture(name: String): Path {
        val sup = dir.resolve("$name.sup")
        Files.write(sup, SupBuilder.build(showMs = 1_000, clearMs = 3_000))
        return Fixtures.generate(
            dir.resolve(name),
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10",
            "-i", sup.toString(),
            "-map", "0:v", "-map", "1:s", "-t", "5", "-copyts",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s", "dvbsub",
        )
    }

    private class Latest {
        var overlay: SubtitleOverlay? = null
        fun poll(pipeline: SubtitlePipeline): SubtitleOverlay? {
            pipeline.acquire()?.let { overlay = it }
            return overlay
        }
    }

    /**
     * The track enumerates as pictures, which is what decides its whole path.
     *
     * A subtitle codec is text or bitmap, and the answer comes from the
     * library's own descriptor rather than a list here -- so a codec that
     * arrived after that list was written is exactly the case the descriptor
     * exists for. Getting it wrong is silent: a bitmap track called text
     * selects cleanly, runs a thread and draws nothing at all.
     */
    @Test
    fun `a DVB track enumerates as a bitmap track`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeDvbSubtitles()
        Fixtures.assumeEncoder("dvbsub")
        val path = dvbFixture("kind.mkv")
        val track = VideoDecoder.open(path).use { it.subtitleTracks().single() }
        // The decoder is named dvbsub and the codec is named dvb_subtitle --
        // the same split as movtext against mov_text. What a track reports is
        // the codec's name, and what a bundle is asked for is the decoder's.
        assertEquals("dvb_subtitle", track.codecName)
        assertFalse(track.isText, "DVB subtitles are composed pixels, not text")
    }

    /**
     * And the pixels arrive when the stream says, not before.
     *
     * The screen is empty until the cue's own timestamp, then carries it --
     * which is the decoder, the demux gate and the display schedule agreeing,
     * across a codec the bitmap branch had never been asked to carry.
     */
    @Test
    fun `a DVB cue is drawn from the moment the stream states`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeDvbSubtitles()
        Fixtures.assumeEncoder("dvbsub")
        val path = dvbFixture("window.mkv")
        val track = VideoDecoder.open(path).use { it.subtitleTracks().single() }
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, track, 320 to 240)
        try {
            val latest = Latest()
            // Before the cue: the pipeline has read far past this point (the
            // demux horizon is thirty seconds), so an empty screen here is the
            // schedule holding the pixels back rather than not having them.
            frames.set(framesFor(500))
            Thread.sleep(300)
            latest.poll(pipeline)
            assertTrue(
                latest.overlay?.patches.isNullOrEmpty(),
                "nothing may be drawn before the cue's own timestamp",
            )

            frames.set(framesFor(2_000))
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isNotEmpty() == true },
                "the cue must be drawn once its timestamp has passed",
            )
            val patch = assertNotNull(latest.overlay?.patches?.firstOrNull())
            assertTrue(patch.width > 0 && patch.height > 0, "a drawn cue has pixels, got ${patch.width}x${patch.height}")
            assertTrue(
                patch.width <= 320 && patch.height <= 240,
                "the cue cannot be larger than the screen it composes onto, got ${patch.width}x${patch.height}",
            )
        } finally {
            pipeline.close()
        }
    }
}
