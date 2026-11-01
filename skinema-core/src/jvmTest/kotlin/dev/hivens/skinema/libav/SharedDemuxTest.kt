package dev.hivens.skinema.libav

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The one demuxer a forward-only stream is read through, held to its read-ahead, parking and lanes. */
class SharedDemuxTest {

    private val dir: Path = Files.createTempDirectory("skinema-demux-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private class Forward(private val data: ByteArray, private val failAfter: Int = Int.MAX_VALUE) : MediaSource {
        private var pos = 0
        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (pos >= failAfter) throw IOException("dropped at $pos")
            if (pos >= data.size) return -1
            val n = minOf(length, data.size - pos, failAfter - pos)
            System.arraycopy(data, pos, dst, offset, n)
            pos += n
            return n
        }
    }

    /** Twenty seconds at ten frames a second, a keyframe every ten, with sound. */
    private fun fixture(): Path = Fixtures.generate(
        dir.resolve("long.mkv"),
        "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
        "-map", "0:v", "-map", "1:a", "-t", "20",
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-g", "10", "-c:a", "flac",
    )

    private fun waitQuiet() = Thread.sleep(500)

    @Test
    fun `nobody reading, the read-ahead stops at a bound rather than at the end`() {
        Fixtures.assumeDecodeEnvironment()
        SharedDemux.open(Forward(Files.readAllBytes(fixture()))).use { demux ->
            // The sides arrive a moment after the open, as they do in a player:
            // the lanes made ready for them bound the reading in between.
            waitQuiet()
            val video = demux.lane(demux.bestVideo)
            val audio = demux.lane(demux.bestAudio)
            demux.settle()
            waitQuiet()
            // Twenty seconds exist: 200 pictures and some 800 FLAC frames of
            // about 24 ms. The bound is about a second per lane, give or take
            // the interleave, so both lanes stay a small part of that.
            assertTrue(video.depth < 30, "the picture lane read ${video.depth} packets ahead")
            assertTrue(audio.depth < 120, "the sound lane read ${audio.depth} packets ahead")
        }
    }

    @Test
    fun `a parked lane keeps only what follows its latest keyframe`() {
        Fixtures.assumeDecodeEnvironment()
        SharedDemux.open(Forward(Files.readAllBytes(fixture()))).use { demux ->
            val video = demux.lane(demux.bestVideo)
            val audio = demux.lane(demux.bestAudio)
            demux.settle()
            video.parked = true
            // The sound is read the whole way, so the picture lane sees every
            // packet go by and keeps a keyframe's worth at most.
            Arena.ofConfined().use { a ->
                val packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                try {
                    while (audio.take(packet) == 0) Libav.avPacketUnref(packet)
                } finally {
                    val p = a.allocate(ADDRESS)
                    p.set(ADDRESS, 0, packet)
                    Libav.avPacketFree(p)
                }
            }
            assertTrue(video.depth in 1..10, "a parked lane holds one keyframe interval at most, held ${video.depth}")
            val first = Arena.ofConfined().use { a ->
                val packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                try {
                    assertEquals(0, video.take(packet))
                    packet.get(JAVA_INT, LibavAbi.Packet.FLAGS) and LibavAbi.Packet.FLAG_KEY
                } finally {
                    val p = a.allocate(ADDRESS)
                    p.set(ADDRESS, 0, packet)
                    Libav.avPacketFree(p)
                }
            }
            assertTrue(first != 0, "what a parked lane hands out first is a keyframe")
        }
    }

    @Test
    fun `settling drops the lanes nobody took`() {
        Fixtures.assumeDecodeEnvironment()
        SharedDemux.open(Forward(Files.readAllBytes(fixture()))).use { demux ->
            // A player without sound takes the picture alone, and the sound lane
            // made ready at the open goes. A sound lane taken after that is a
            // fresh one: it starts where the stream is, past the second the
            // picture lane holds, not at the start the ready lane had kept.
            demux.lane(demux.bestVideo)
            waitQuiet()
            demux.settle()
            val audio = demux.lane(demux.bestAudio)
            val firstPts = Arena.ofConfined().use { a ->
                val packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                try {
                    assertEquals(0, audio.take(packet, 5_000_000_000L))
                    packet.get(java.lang.foreign.ValueLayout.JAVA_LONG, LibavAbi.Packet.PTS)
                } finally {
                    val p = a.allocate(ADDRESS)
                    p.set(ADDRESS, 0, packet)
                    Libav.avPacketFree(p)
                }
            }
            // Matroska times in milliseconds.
            assertTrue(firstPts > 500, "a fresh lane, not the one kept since the open: first pts $firstPts ms")
        }
    }

    @Test
    fun `one side reading alone is never held back by a lane full past the cap`() {
        Fixtures.assumeDecodeEnvironment()
        // A cap low enough that the sound lane, which nobody reads, passes it at
        // once. The picture side waits on its empty lane, and that alone must
        // keep the reading going to the end.
        SharedDemux.open(Forward(Files.readAllBytes(fixture())), softCapBytes = 32L * 1024).use { demux ->
            val video = demux.lane(demux.bestVideo)
            demux.lane(demux.bestAudio)
            demux.settle()
            var pictures = 0
            Arena.ofConfined().use { a ->
                val packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                try {
                    while (true) {
                        val ret = video.take(packet, 5_000_000_000L)
                        if (ret != 0) {
                            assertEquals(LibavAbi.AVERROR_EOF, ret, "the picture side waited 5 s for a packet")
                            break
                        }
                        pictures++
                        Libav.avPacketUnref(packet)
                    }
                } finally {
                    val p = a.allocate(ADDRESS)
                    p.set(ADDRESS, 0, packet)
                    Libav.avPacketFree(p)
                }
            }
            assertEquals(200, pictures, "every picture of the twenty seconds")
        }
    }

    @Test
    fun `a stream that ends early does not lift the bound for the rest`() {
        Fixtures.assumeDecodeEnvironment()
        // Two seconds of sound under twenty of picture. Once the sound has
        // ended its side waits on an empty lane for good, and that must not
        // read the rest of the picture in with nobody taking it.
        val video = Fixtures.generate(
            dir.resolve("short-sound.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10:duration=20",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
            "-map", "0:v", "-map", "1:a",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-g", "10", "-c:a", "flac",
        )
        SharedDemux.open(Forward(Files.readAllBytes(video))).use { demux ->
            val picture = demux.lane(demux.bestVideo)
            val sound = demux.lane(demux.bestAudio)
            demux.settle()
            Arena.ofConfined().use { a ->
                val packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                try {
                    // The sound side reads everything it gets, as a player does.
                    var idle = 0
                    while (idle < 3) {
                        if (sound.take(packet, 300_000_000L) == 0) {
                            idle = 0
                            Libav.avPacketUnref(packet)
                        } else {
                            idle++
                        }
                    }
                } finally {
                    val p = a.allocate(ADDRESS)
                    p.set(ADDRESS, 0, packet)
                    Libav.avPacketFree(p)
                }
            }
            waitQuiet()
            assertTrue(picture.depth < 60, "the picture lane read ${picture.depth} of 200 packets with nobody taking them")
        }
    }

    @Test
    fun `a lane is refused once the demux is closed`() {
        Fixtures.assumeDecodeEnvironment()
        val demux = SharedDemux.open(Forward(Files.readAllBytes(fixture())))
        val index = demux.bestAudio
        demux.close()
        assertFailsWith<LibavException> { demux.lane(index) }
    }

    @Test
    fun `what the stream raised reaches every lane`() {
        Fixtures.assumeDecodeEnvironment()
        val bytes = Files.readAllBytes(fixture())
        SharedDemux.open(Forward(bytes, failAfter = bytes.size / 2)).use { demux ->
            val video = demux.lane(demux.bestVideo)
            demux.settle()
            Arena.ofConfined().use { a ->
                val packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                try {
                    assertFailsWith<IOException> {
                        while (true) {
                            val ret = video.take(packet)
                            if (ret != 0) break
                            Libav.avPacketUnref(packet)
                        }
                    }
                } finally {
                    val p = a.allocate(ADDRESS)
                    p.set(ADDRESS, 0, packet)
                    Libav.avPacketFree(p)
                }
            }
        }
    }
}
