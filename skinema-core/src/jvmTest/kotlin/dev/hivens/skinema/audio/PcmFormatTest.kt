package dev.hivens.skinema.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The negotiation's decision, asked directly.
 *
 * [formatLadder] is pure on purpose: exercised only through a device, its order
 * would be provable on one machine's answers and nowhere else, and the order is
 * the whole of it. What a sink does with the rungs is the pipeline's business
 * and is tested there.
 */
class PcmFormatTest {

    private fun source(
        channels: Int = 2,
        layout: String = "stereo",
        encoding: PcmEncoding = PcmEncoding.S16LE,
        significantBits: Int = encoding.bytesPerSample * 8,
    ) = PcmFormat(48_000, channels, layout, encoding, significantBits)

    @Test
    fun `the file's own shape is offered first and the floor last`() {
        val ladder = formatLadder(source(6, "5.1", PcmEncoding.F32LE), ChannelPreference.Source)

        assertEquals(source(6, "5.1", PcmEncoding.F32LE), ladder.first(), "the file's own shape leads")
        assertEquals(PcmFormat.floor(48_000), ladder.last(), "and every ladder ends somewhere every sink takes")
        assertTrue(ladder.size > 2, "there must be something between the two, got $ladder")
    }

    /**
     * Channels rank above width because a listener hears the difference. A
     * device that takes six channels of sixteen bits is a better answer than
     * two channels of thirty-two, and the order says so.
     */
    @Test
    fun `every rung that keeps the channels comes before any that folds`() {
        val ladder = formatLadder(source(6, "5.1", PcmEncoding.F32LE), ChannelPreference.Source)

        val lastSixChannel = ladder.indexOfLast { it.channels == 6 }
        val firstFold = ladder.indexOfFirst { it.channels != 6 }
        assertTrue(lastSixChannel < firstFold, "a fold appeared before the last full-channel rung: $ladder")
    }

    @Test
    fun `asking for stereo never offers the file's own channels`() {
        val ladder = formatLadder(source(6, "5.1", PcmEncoding.F32LE), ChannelPreference.Stereo)

        assertTrue(ladder.all { it.channels == 2 }, "a stereo request must not reach for six, got $ladder")
        assertTrue(ladder.all { it.layout == "stereo" }, "and the fold is named, got $ladder")
    }

    /**
     * Float carries twenty-four bits of an integer exactly and not one more.
     * That makes it a lossless carrier for a 24-bit file and a lossy one for a
     * 32-bit file, and the ladder is the only place that difference can be
     * acted on: by the time a sink has taken a rung, the samples are already in
     * it.
     */
    @Test
    fun `float is offered for a 24-bit source and withheld from a 32-bit one`() {
        val twentyFour = formatLadder(source(encoding = PcmEncoding.S32LE, significantBits = 24), ChannelPreference.Source)
        assertTrue(
            twentyFour.any { it.encoding == PcmEncoding.F32LE },
            "float carries 24 bits exactly and should be offered, got $twentyFour",
        )

        val thirtyTwo = formatLadder(source(encoding = PcmEncoding.S32LE, significantBits = 32), ChannelPreference.Source)
        assertTrue(
            thirtyTwo.none { it.encoding == PcmEncoding.F32LE },
            "float would drop the bottom eight bits of a 32-bit source, got $thirtyTwo",
        )
    }

    @Test
    fun `a float source is still offered the format it already is`() {
        val ladder = formatLadder(source(encoding = PcmEncoding.F32LE), ChannelPreference.Source)

        assertEquals(PcmEncoding.F32LE, ladder.first().encoding, "the rule about float carriers is about narrowing")
    }

    @Test
    fun `a rung never claims more real bits than it can hold`() {
        val ladder = formatLadder(source(encoding = PcmEncoding.F64LE, significantBits = 64), ChannelPreference.Source)

        for (rung in ladder) {
            assertTrue(
                rung.significantBits <= rung.encoding.bytesPerSample * 8,
                "$rung claims more significant bits than it has room for",
            )
        }
    }

    @Test
    fun `a mono source stays mono and is not widened on the way`() {
        val ladder = formatLadder(source(1, "mono"), ChannelPreference.Source)

        assertEquals(1, ladder.first().channels, "the file's own shape leads, and it is one channel")
        assertEquals("mono", ladder.first().layout)
    }

    @Test
    fun `the floor is what every sink is promised`() {
        val floor = PcmFormat.floor(44_100)

        assertEquals(2, floor.channels)
        assertEquals("stereo", floor.layout)
        assertEquals(PcmEncoding.S16LE, floor.encoding)
        assertEquals(4, floor.bytesPerFrame, "S16LE stereo is four bytes a frame")
    }

    /**
     * The frame size is arithmetic every side of the seam does, so it is stated
     * once rather than repeated: a decoder sizes its buffer with it, a sink
     * sizes its line, and the tail accounting counts in it.
     */
    @Test
    fun `a frame is one sample per channel of the encoding's width`() {
        assertEquals(12, source(6, "5.1", PcmEncoding.S16LE).bytesPerFrame)
        assertEquals(24, source(6, "5.1", PcmEncoding.F32LE).bytesPerFrame)
        assertEquals(1, source(1, "mono", PcmEncoding.U8).bytesPerFrame)
    }

    @Test
    fun `a format that cannot describe anything is refused where it is built`() {
        assertFailsWith<IllegalArgumentException>("a rate of zero is not a format") {
            PcmFormat(0, 2, "stereo", PcmEncoding.S16LE, 16)
        }
        assertFailsWith<IllegalArgumentException>("no channels is not a format") {
            PcmFormat(48_000, 0, "stereo", PcmEncoding.S16LE, 16)
        }
        assertFailsWith<IllegalArgumentException>("more real bits than the encoding holds is not a format") {
            PcmFormat(48_000, 2, "stereo", PcmEncoding.S16LE, 24)
        }
    }
}
