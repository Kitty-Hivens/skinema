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

    // Told the device is stopped, or the clock fills the gaps between frame
    // counts with wall time -- up to sixty milliseconds past every value set
    // here, on its own.
    private val clock = AudioClock(48_000) { frames.get() }.also { it.setDeviceRunning(false) }

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

    /** The same, with a text track beside it to answer the kind question against. */
    private fun dvbAndTextFixture(name: String): Path {
        val sup = dir.resolve("$name.sup")
        Files.write(sup, SupBuilder.build(showMs = 1_000, clearMs = 3_000))
        val srt = dir.resolve("$name.srt")
        Files.writeString(srt, "1\n00:00:01,000 --> 00:00:03,000\nText beside it\n")
        return Fixtures.generate(
            dir.resolve(name),
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10",
            "-i", sup.toString(),
            "-i", srt.toString(),
            "-map", "0:v", "-map", "1:s", "-map", "2:s", "-t", "5", "-copyts",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s:0", "dvbsub", "-c:s:1", "srt",
        )
    }

    /**
     * The same cue in MPEG-TS, which is what a DVB recording actually is.
     *
     * Kept separate from the Matroska fixture because only this one can see
     * the parser: TS carries each subtitle unit inside a PES payload that
     * begins with a two-byte prefix, and the decoder refuses anything not
     * starting at a segment sync byte. Matroska stores the unit already
     * stripped, so a fixture in that container decodes either way -- which is
     * exactly how a bundle without the parser passed a green suite while
     * decoding nothing at all from the format's own container.
     */
    private fun dvbTsFixture(name: String): Path {
        val sup = dir.resolve("$name.sup")
        Files.write(sup, SupBuilder.build(showMs = 1_000, clearMs = 3_000))
        return Fixtures.generate(
            dir.resolve(name),
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10",
            "-i", sup.toString(),
            "-map", "0:v", "-map", "1:s", "-t", "5", "-copyts",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s", "dvbsub", "-f", "mpegts",
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
        val path = dvbAndTextFixture("kind.mkv")
        val tracks = VideoDecoder.open(path).use { it.subtitleTracks() }
        val dvb = assertNotNull(tracks.firstOrNull { it.codecName == "dvb_subtitle" }, "the DVB track must enumerate")
        val text = assertNotNull(tracks.firstOrNull { it.codecName == "subrip" }, "the text track must enumerate")
        // The decoder is named dvbsub and the codec is named dvb_subtitle --
        // the same split as movtext against mov_text. What a track reports is
        // the codec's name, and what a bundle is asked for is the decoder's.
        assertFalse(dvb.isText, "DVB subtitles are composed pixels, not text")
        // Asked beside a track that IS text, because false is also what this
        // answers for a codec it knows nothing about: alone, the assertion
        // cannot tell a considered answer from a default one.
        assertTrue(text.isText, "and the text track beside it must still read as text")
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
            // Before the cue, and the wait is a handshake rather than a sleep:
            // an empty screen only means the schedule is holding the pixels
            // back if the demuxer has actually read past them, and the
            // pipeline says how far it has read.
            frames.set(framesFor(500))
            assertTrue(
                awaitTrue { pipeline.lastDemuxedPtsNanos > 2_000_000_000L },
                "the demuxer must have read past the cue for its absence to mean anything",
            )
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
            // The rectangle the PGS builder wrote, which survives the
            // re-encode into DVB segments exactly: 32x16 at 10,20. Asserted
            // rather than bounded, because a bound of "not larger than the
            // screen" is a bound against the number this test handed in as the
            // storage size, and every wrong answer fits inside it.
            assertEquals(10, patch.x, "the cue keeps its x through the re-encode")
            assertEquals(20, patch.y, "the cue keeps its y through the re-encode")
            assertEquals(32, patch.width, "the cue keeps its width through the re-encode")
            assertEquals(16, patch.height, "the cue keeps its height through the re-encode")
        } finally {
            pipeline.close()
        }
    }

    /**
     * And the same, out of MPEG-TS -- the container a DVB recording comes in.
     *
     * This is the one that needs the parser in the bundle, and the only test
     * here that can tell whether it is there: the Matroska fixture above
     * decodes with or without it. A bundle missing it produces no pixels at
     * all from this file, so the assertion is the same one and the difference
     * is entirely in the container.
     */
    @Test
    fun `a DVB cue decodes out of MPEG-TS as well as Matroska`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeDvbSubtitles()
        // The mpegts demuxer rides the extended-formats feature, not the
        // subtitle one, so a bundle can carry the decoder and not the reader.
        Fixtures.assumeFormats()
        Fixtures.assumeEncoder("dvbsub")
        val path = dvbTsFixture("broadcast.ts")
        val track = VideoDecoder.open(path).use { it.subtitleTracks().single() }
        clock.start(0)
        val pipeline = SubtitlePipeline(path, clock, track, 320 to 240)
        try {
            val latest = Latest()
            frames.set(framesFor(2_500))
            assertTrue(
                awaitTrue { latest.poll(pipeline)?.patches?.isNotEmpty() == true },
                "the cue must decode out of the broadcast container too",
            )
            val patch = assertNotNull(latest.overlay?.patches?.firstOrNull())
            assertTrue(patch.width > 0 && patch.height > 0, "a drawn cue has pixels, got ${patch.width}x${patch.height}")
        } finally {
            pipeline.close()
        }
    }
}
