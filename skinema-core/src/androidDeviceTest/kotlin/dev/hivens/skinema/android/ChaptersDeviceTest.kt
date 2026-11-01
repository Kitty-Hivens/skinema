package dev.hivens.skinema.android

import androidx.test.platform.app.InstrumentationRegistry
import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.Chapter
import dev.hivens.skinema.libav.DeviceFixtures
import dev.hivens.skinema.libav.VideoDecoder
import dev.hivens.skinema.player.VideoPlayer
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ChaptersDeviceTest {

    /** What every chapters fixture was written with. */
    private val written = listOf(
        Triple(0L, 500_000_000L, "Intro"),
        Triple(500_000_000L, 1_000_000_000L, "Drop"),
    )

    private fun List<Chapter>.plain() = map { Triple(it.startNanos, it.endNanos, it.title) }

    private fun read(name: String) = Chapters.read(Input.File(DeviceFixtures.path(name))).plain()

    @Test
    fun everyContainerThatCarriesChaptersGivesThemBack() {
        for (ext in listOf("mp4", "mov", "mkv", "webm")) {
            assertEquals(written, read("chapters.$ext"), "chapters.$ext")
        }
    }

    @Test
    fun theQuickTimeTrackAndTheNeroListAreEachReadOnTheirOwn() {
        assertEquals(written, read("chapters-track.mp4"), "the chapter track alone")
        // Nero writes starts only, so the ends are the next start and the movie's duration.
        assertEquals(written, read("chapters-nero.mp4"), "the Nero list alone")
    }

    @Test
    fun theDecodersAndThePlayerReportThem() {
        VideoDecoder.open(DeviceFixtures.path("chapters.mkv")).use { assertEquals(written, it.chapters().plain()) }
        VideoDecoder.open(DeviceFixtures.memorySource("chapters.mp4")).use { assertEquals(written, it.chapters().plain(), "through a MediaSource") }
        val audio = assertNotNull(AudioDecoder.openOrNull(DeviceFixtures.path("chapters.m4a")))
        audio.use { assertEquals(written, it.chapters.plain()) }
        VideoPlayer(DeviceFixtures.path("chapters.mov"), loop = false).use { p ->
            val deadline = System.nanoTime() + 5_000_000_000L
            while (p.chapters.isEmpty()) {
                if (System.nanoTime() > deadline) fail("no chapters on the player")
                Thread.sleep(10)
            }
            assertEquals(written, p.chapters.plain())
            // A switch takes the new file's chapters, none here.
            p.setSource(DeviceFixtures.path("red-h264.mp4"))
            while (p.chapters.isNotEmpty()) {
                if (System.nanoTime() > deadline + 5_000_000_000L) fail("the old file's chapters stayed: source ${p.source}, refusal ${p.sourceFailure}, state ${p.state}")
                Thread.sleep(10)
            }
            assertEquals("red-h264.mp4", p.source.fileName.toString())
        }
    }

    @Test
    fun matroskaChaptersPastTheClustersAreFoundThroughTheSeekHead() {
        val info = el(0x1549A966, uint(0x2AD7B1, 1_000_000), el(0x4489, ByteBuffer.allocate(8).putDouble(3000.0).array()))
        val cluster = el(0x1F43B675, uint(0xE7, 0))
        val chapters = el(
            0x1043A770,
            // The first edition, not the default one: passed over.
            el(0x45B9, atom(0, "Other edition")),
            el(
                0x45B9, uint(0x45DB, 1),
                atom(0, "A"),
                atom(1_000_000_000L, "Hidden", el(0x98, byteArrayOf(1))),
                atom(1_500_000_000L, "B"),
            ),
        )
        fun seekHead(position: Long) = el(0x114D9B74, el(0x4DBB, el(0x53AB, id(0x1043A770)), uint(0x53AC, position)))
        val position = seekHead(0).size + info.size + cluster.size.toLong()
        // A segment of unknown size, as a live muxer writes one.
        val segment = id(0x18538067) + byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1) + seekHead(position) + info + cluster + chapters
        val file = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("late-chapters.mkv")
        file.writeBytes(el(0x1A45DFA3, el(0x4282, "matroska".toByteArray())) + segment)
        // No ends written: each ends at the next shown start, the last at the duration.
        assertEquals(
            listOf(Triple(0L, 1_500_000_000L, "A"), Triple(1_500_000_000L, 3_000_000_000L, "B")),
            Chapters.read(Input.File(file.toPath())).plain(),
        )
    }

    private fun id(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array().dropWhile { it == 0.toByte() }.toByteArray()

    /** An element with an eight-byte size, which EBML allows for any size. */
    private fun el(id: Long, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, b -> acc + b }
        val size = ByteBuffer.allocate(8).putLong(body.size.toLong()).array().also { it[0] = 0x01 }
        return id(id) + size + body
    }

    private fun uint(id: Long, value: Long) = el(id, ByteBuffer.allocate(8).putLong(value).array())

    private fun atom(start: Long, title: String, vararg extra: ByteArray) =
        el(0xB6, uint(0x91, start), el(0x80, el(0x85, title.toByteArray())), *extra)

    @Test
    fun aChapterTrackClaimingBillionsOfSamplesPerChunkIsReadInBoundedTime() {
        // One chunk table of 100 000 entries and one stsc entry saying each
        // chunk holds 2^31 - 1 samples, for a track that has one. Expanded as
        // claimed, that is 2e14 steps, and the open that reads it never returns.
        val n = 100_000
        fun u32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
        fun box(type: String, vararg body: ByteArray): ByteArray {
            val payload = body.fold(ByteArray(0)) { acc, b -> acc + b }
            return u32(8 + payload.size) + type.toByteArray(Charsets.ISO_8859_1) + payload
        }
        fun full(type: String, vararg body: ByteArray) = box(type, ByteArray(4), *body)
        val stbl = box(
            "stbl",
            full("stts", u32(1), u32(1), u32(1000)),
            full("stsz", u32(0), u32(1), u32(10)),
            full("stsc", u32(1), u32(1), u32(Int.MAX_VALUE), u32(1)),
            full("stco", u32(n), ByteArray(4 * n)),
        )
        val trak = box(
            "trak",
            full("tkhd", ByteArray(8), u32(1), ByteArray(68)),
            box("tref", box("chap", u32(1))),
            box("mdia", full("mdhd", ByteArray(8), u32(1000), u32(1000), ByteArray(4)), box("minf", stbl)),
        )
        val moov = box("moov", full("mvhd", ByteArray(8), u32(1000), u32(1000), ByteArray(80)), trak)
        val file = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("hostile-stsc.mp4")
        file.writeBytes(box("ftyp", "isomisom".toByteArray()) + moov)
        var result: List<Triple<Long, Long, String?>>? = null
        val reader = Thread { result = Chapters.read(Input.File(file.toPath())).plain() }.apply {
            isDaemon = true
            start()
            join(5_000)
        }
        assertTrue(!reader.isAlive, "still reading after 5 s")
        // The one sample the track has, untitled: its bytes are no text sample.
        assertEquals(listOf(Triple(0L, 1_000_000_000L, null)), result)
    }

    /** A chapter-track MP4 built from [stbl]'s tables, for the hostile layouts below. */
    private fun chapterTrackFile(name: String, vararg tables: ByteArray, mdat: ByteArray = ByteArray(0)): java.io.File {
        fun u32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
        fun box(type: String, vararg body: ByteArray): ByteArray {
            val payload = body.fold(ByteArray(0)) { acc, b -> acc + b }
            return u32(8 + payload.size) + type.toByteArray(Charsets.ISO_8859_1) + payload
        }
        fun full(type: String, vararg body: ByteArray) = box(type, ByteArray(4), *body)
        val trak = box(
            "trak",
            full("tkhd", ByteArray(8), u32(1), ByteArray(68)),
            box("tref", box("chap", u32(1))),
            box("mdia", full("mdhd", ByteArray(8), u32(1000), u32(1000), ByteArray(4)), box("minf", box("stbl", *tables))),
        )
        val moov = box("moov", full("mvhd", ByteArray(8), u32(1000), u32(1000), ByteArray(80)), trak)
        val file = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve(name)
        file.writeBytes(box("ftyp", "isomisom".toByteArray()) + moov + mdat)
        return file
    }

    private fun readWithin(file: java.io.File, millis: Long): List<Triple<Long, Long, String?>> {
        var result: List<Triple<Long, Long, String?>>? = null
        val reader = Thread { result = Chapters.read(Input.File(file.toPath())).plain() }.apply {
            isDaemon = true
            start()
            join(millis)
        }
        assertTrue(!reader.isAlive, "still reading after ${millis / 1000} s")
        return result ?: fail("no answer")
    }

    @Test
    fun stscEntriesOutOfOrderWalkEachChunkOnce() {
        // A hundred thousand chunks and a hundred thousand stsc entries whose
        // first chunks alternate between the first and past the last: walked
        // from each entry's own first chunk, every entry walked the table, 5e9
        // steps. One sample, so the answer is one untitled chapter.
        val n = 100_000
        fun u32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
        val stsc = ByteBuffer.allocate(8 + n * 12).putInt(0).putInt(n).apply {
            for (e in 0 until n) putInt(if (e % 2 == 0) 1 else n + 1).putInt(0).putInt(1)
        }.array()
        fun full(type: String, body: ByteArray) = u32(12 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + ByteArray(4) + body
        val file = chapterTrackFile(
            "hostile-stsc-order.mp4",
            full("stts", u32(1) + u32(1) + u32(1000)),
            full("stsz", u32(0) + u32(1) + u32(10)),
            u32(8 + stsc.size) + "stsc".toByteArray() + stsc,
            full("stco", u32(n) + ByteArray(4 * n)),
        )
        assertEquals(emptyList(), readWithin(file, 5_000).filter { it.third != null })
    }

    @Test
    fun aHundredThousandChaptersSharingOneLongTitleStayBounded() {
        // Every chapter's sample is the same 64 KiB title: kept whole, that is
        // six and a half billion characters. Titles stop at a total bound, and
        // the chapters past it keep their times.
        val n = 100_000
        fun u32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
        fun full(type: String, body: ByteArray) = u32(12 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + ByteArray(4) + body
        val title = ByteArray(65_536) { 'a'.code.toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xFE.toByte() }
        val stcoBody = ByteBuffer.allocate(4 + 4 * n).putInt(n)
        val probe = chapterTrackFile(
            "hostile-titles.mp4",
            full("stts", u32(1) + u32(n) + u32(10)),
            full("stsz", u32(65_536) + u32(n)),
            full("stsc", u32(1) + u32(1) + u32(1) + u32(1)),
            full("stco", stcoBody.array()),
        )
        // The title sits after the moov, in an mdat box of its own.
        val titleAt = probe.length() + 8
        val stco = ByteBuffer.allocate(4 + 4 * n).putInt(n).apply { repeat(n) { putInt(titleAt.toInt()) } }.array()
        val file = chapterTrackFile(
            "hostile-titles.mp4",
            full("stts", u32(1) + u32(n) + u32(10)),
            full("stsz", u32(65_536) + u32(n)),
            full("stsc", u32(1) + u32(1) + u32(1) + u32(1)),
            full("stco", stco),
            mdat = u32(8 + title.size) + "mdat".toByteArray() + title,
        )
        val chapters = readWithin(file, 10_000)
        assertEquals(n, chapters.size)
        assertTrue(chapters.first().third != null, "the first chapters are titled")
        assertEquals(null, chapters.last().third, "the last are not, past the bound")
    }

    @Test
    fun aFileWithoutChaptersOrNotAContainerHasNone() {
        for (name in listOf("red-h264.mp4", "green-vp9.webm", "subs.mkv", "sine-aac.m4a", "still.png", "tone-flac24.flac")) {
            assertTrue(read(name).isEmpty(), name)
        }
    }
}
