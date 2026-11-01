package dev.hivens.skinema.android

import dev.hivens.skinema.Debug
import dev.hivens.skinema.libav.Chapter
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * A file's chapters, read here because the platform extractor reads none.
 *
 * Matroska and WebM: the Chapters element, found among the segment's first
 * children or through the seek head, the default edition (the first when
 * none says so), its top-level atoms, hidden and disabled ones left out.
 * MP4 and QuickTime: the chapter track a video track names in its `tref`,
 * which is what Apple's tools write, and failing that the Nero `chpl` list,
 * which is what mp4v2 writes; ffmpeg writes both, and libavformat lets the
 * track win too. A chapter with no end ends where the next one starts, and
 * the last at the container's duration, which is how libavformat fills them.
 *
 * Never throws: chapters are an optional part of an open, and a file they
 * cannot be read from has none.
 */
internal object Chapters {

    fun read(input: Input): List<Chapter> = try {
        Bytes(input).use { bytes ->
            val head = bytes.read(0, 12)
            when {
                head.size >= 4 && head.u32(0) == EBML_HEADER -> matroska(bytes)
                head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_FIRST_BOXES -> mp4(bytes)
                else -> emptyList()
            }
        }
    } catch (e: Exception) {
        Debug.trace("reading chapters of ${input.label}", e)
        emptyList()
    }

    // -- MP4 / QuickTime ---------------------------------------------------------

    private class Box(val type: String, val start: Long, val end: Long)

    private fun Bytes.boxes(from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            val header = read(at, 16)
            if (header.size < 8) break
            var size = header.u32(0).toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            when (size) {
                1L -> {
                    if (header.size < 16) break
                    size = ByteBuffer.wrap(header, 8, 8).long
                    headerSize = 16
                }
                0L -> size = to - at
            }
            if (size < headerSize || at + size > to) break
            out += Box(type, at + headerSize, at + size)
            at += size
        }
        return out
    }

    private fun Bytes.children(box: Box) = boxes(box.start, box.end)

    private fun Bytes.body(box: Box): ByteArray = read(box.start, (box.end - box.start).coerceAtMost(MAX_BOX.toLong()).toInt())

    private fun List<Box>.first(type: String) = firstOrNull { it.type == type }

    private fun mp4(bytes: Bytes): List<Chapter> {
        val moov = bytes.boxes(0, bytes.size).first("moov") ?: return emptyList()
        val inMoov = bytes.children(moov)
        val duration = inMoov.first("mvhd")?.let { movieDuration(bytes.body(it)) }
        val traks = inMoov.filter { it.type == "trak" }
        val referenced = traks.flatMap { trak ->
            val tref = bytes.children(trak).first("tref") ?: return@flatMap emptyList()
            bytes.children(tref).filter { it.type == "chap" }.flatMap { chap ->
                val ids = bytes.body(chap)
                (0 until ids.size / 4).map { ids.u32(it * 4) }
            }
        }.toSet()
        traks.firstOrNull { trackId(bytes, it) in referenced }
            ?.let { chapterTrack(bytes, it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val chpl = inMoov.first("udta")?.let { bytes.children(it).first("chpl") } ?: return emptyList()
        return nero(bytes.body(chpl), duration)
    }

    private fun movieDuration(mvhd: ByteArray): Long? {
        val b = ByteBuffer.wrap(mvhd)
        val version = b.get().toInt()
        b.position(if (version == 1) 20 else 12)
        val timescale = b.int.toLong() and 0xFFFFFFFFL
        val units = if (version == 1) b.long else b.int.toLong() and 0xFFFFFFFFL
        return if (timescale > 0) units.scaled(timescale) else null
    }

    private fun trackId(bytes: Bytes, trak: Box): Int? {
        val tkhd = bytes.children(trak).first("tkhd")?.let { bytes.body(it) } ?: return null
        return tkhd.u32(if (tkhd[0].toInt() == 1) 20 else 12)
    }

    /**
     * The QuickTime chapter track: a text track whose samples are the titles,
     * each a 16-bit length and the text, timed by the track's own table.
     */
    private fun chapterTrack(bytes: Bytes, trak: Box): List<Chapter> {
        val mdia = bytes.children(trak).first("mdia") ?: return emptyList()
        val inMdia = bytes.children(mdia)
        val mdhd = inMdia.first("mdhd")?.let { bytes.body(it) } ?: return emptyList()
        val timescale = (mdhd.u32(if (mdhd[0].toInt() == 1) 20 else 12).toLong() and 0xFFFFFFFFL).takeIf { it > 0 } ?: return emptyList()
        val minf = inMdia.first("minf") ?: return emptyList()
        val stbl = bytes.children(minf).first("stbl") ?: return emptyList()
        val tables = bytes.children(stbl)
        fun table(type: String) = tables.first(type)?.let { bytes.body(it) }

        val stts = table("stts") ?: return emptyList()
        val stsz = table("stsz") ?: return emptyList()
        val stsc = table("stsc") ?: return emptyList()
        val offsets = table("stco")?.let { t -> LongArray(t.u32(4).coerceIn(0, MAX_SAMPLES)) { t.u32(8 + it * 4).toLong() and 0xFFFFFFFFL } }
            ?: table("co64")?.let { t -> LongArray(t.u32(4).coerceIn(0, MAX_SAMPLES)) { ByteBuffer.wrap(t, 8 + it * 8, 8).long } }
            ?: return emptyList()

        val fixedSize = stsz.u32(4)
        val count = stsz.u32(8).coerceIn(0, MAX_SAMPLES)
        val sizes = IntArray(count) { if (fixedSize != 0) fixedSize else stsz.u32(12 + it * 4) }

        // Where each sample sits: each stsc entry covers the chunks up to the
        // next entry's first, every one holding its samples per chunk. Walked
        // so the work is bounded by the chunks and samples that exist, never
        // by what a size field claims: an entry starts no earlier than the
        // one before it ended, so no chunk is walked twice, whatever order a
        // file puts its entries in.
        val sampleOffsets = LongArray(count)
        val entries = stsc.u32(4).coerceIn(0, MAX_SAMPLES)
        fun firstChunk(e: Int) = (stsc.u32(8 + e * 12).toLong() and 0xFFFFFFFFL) - 1
        var sample = 0
        var walked = 0L
        for (e in 0 until entries) {
            if (sample >= count) break
            val perChunk = stsc.u32(8 + e * 12 + 4).toLong() and 0xFFFFFFFFL
            val until = if (e + 1 < entries) firstChunk(e + 1).coerceAtMost(offsets.size.toLong()) else offsets.size.toLong()
            var chunk = maxOf(firstChunk(e), walked)
            walked = maxOf(walked, until)
            while (chunk < until && sample < count) {
                var at = offsets[chunk.toInt()]
                var placed = 0L
                while (placed < perChunk && sample < count) {
                    sampleOffsets[sample] = at
                    at += sizes[sample]
                    sample++
                    placed++
                }
                chunk++
            }
        }

        val out = ArrayList<Chapter>()
        var start = 0L
        var index = 0
        // Titles are read until their total reaches a bound, and the chapters
        // after that keep their times with no title: a file of a hundred
        // thousand chapters each titled with the same 64 KiB would otherwise
        // hold gigabytes of strings.
        var titleBytes = 0L
        for (e in 0 until stts.u32(4).coerceIn(0, MAX_SAMPLES)) {
            val runs = stts.u32(8 + e * 8)
            val delta = stts.u32(12 + e * 8).toLong() and 0xFFFFFFFFL
            repeat(runs.coerceAtMost(count - index)) {
                val size = sizes[index].coerceIn(0, MAX_TITLE)
                val title = if (titleBytes + size <= MAX_TITLES_TOTAL) {
                    titleBytes += size
                    sampleTitle(bytes.read(sampleOffsets[index], size))
                } else {
                    null
                }
                out += Chapter(start.scaled(timescale), (start + delta).scaled(timescale), title)
                start += delta
                index++
            }
        }
        return out
    }

    /** A text sample: a big-endian 16-bit length, then UTF-8, or UTF-16 behind its byte order mark. */
    private fun sampleTitle(sample: ByteArray): String? {
        if (sample.size < 2) return null
        val length = ((sample[0].toInt() and 0xFF) shl 8 or (sample[1].toInt() and 0xFF)).coerceAtMost(sample.size - 2)
        if (length <= 0) return null
        val text = sample.copyOfRange(2, 2 + length)
        val decoded = when {
            text.size >= 2 && text[0] == 0xFE.toByte() && text[1] == 0xFF.toByte() -> String(text, 2, text.size - 2, Charsets.UTF_16BE)
            text.size >= 2 && text[0] == 0xFF.toByte() && text[1] == 0xFE.toByte() -> String(text, 2, text.size - 2, Charsets.UTF_16LE)
            else -> String(text, Charsets.UTF_8)
        }
        return decoded.takeIf { it.isNotEmpty() }
    }

    /** Nero's list: starts in hundreds of nanoseconds and titles, the ends left to be filled. */
    private fun nero(chpl: ByteArray, duration: Long?): List<Chapter> {
        val b = ByteBuffer.wrap(chpl)
        val version = b.get().toInt()
        b.position(if (version > 0) 8 else 4)
        val count = b.get().toInt() and 0xFF
        val starts = ArrayList<Pair<Long, String?>>()
        repeat(count) {
            if (b.remaining() < 9) return@repeat
            val start = b.long * 100L
            val length = (b.get().toInt() and 0xFF).coerceAtMost(b.remaining())
            val title = ByteArray(length).also(b::get).let { String(it, Charsets.UTF_8) }.takeIf { it.isNotEmpty() }
            starts += start to title
        }
        return withEnds(starts.map { Triple(it.first, null, it.second) }, duration)
    }

    // -- Matroska ------------------------------------------------------------------

    private class Element(val id: Long, val start: Long, val end: Long)

    /** An element header at [at]: the id with its marker kept, the size without, as EBML writes them. */
    private fun Bytes.element(at: Long, limit: Long): Element? {
        val header = read(at, 12)
        val id = vint(header, 0, keepMarker = true) ?: return null
        val size = vint(header, id.second, keepMarker = false) ?: return null
        val dataStart = at + size.second
        val unknown = size.first == (1L shl (7 * (size.second - id.second))) - 1
        val end = if (unknown) limit else (dataStart + size.first).coerceAtMost(limit)
        return Element(id.first, dataStart, end)
    }

    /** A variable-length integer at [at] in [b]: its value and the offset just past it. */
    private fun vint(b: ByteArray, at: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (at >= b.size) return null
        val first = b[at].toInt() and 0xFF
        if (first == 0) return null
        var length = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            length++
        }
        if (at + length > b.size) return null
        var value = (if (keepMarker) first else first and (mask - 1)).toLong()
        for (i in 1 until length) value = (value shl 8) or (b[at + i].toLong() and 0xFF)
        return value to at + length
    }

    private fun matroska(bytes: Bytes): List<Chapter> {
        val header = bytes.element(0, bytes.size) ?: return emptyList()
        val segment = bytes.element(header.end, bytes.size)?.takeIf { it.id == SEGMENT } ?: return emptyList()
        var info: Element? = null
        var chapters: Element? = null
        var seekHead: Element? = null
        var at = segment.start
        while (at < segment.end && (info == null || chapters == null)) {
            val e = bytes.element(at, segment.end) ?: break
            when (e.id) {
                INFO -> info = e
                CHAPTERS -> chapters = e
                SEEK_HEAD -> if (seekHead == null) seekHead = e
                CLUSTER -> break
            }
            if (e.end <= at) break
            at = e.end
        }
        // Written after the clusters, as a muxer adding chapters later does:
        // the seek head says where.
        if ((chapters == null || info == null) && seekHead != null) {
            val head = bytes.read(seekHead.start, (seekHead.end - seekHead.start).coerceAtMost(MAX_BOX.toLong()).toInt())
            for (seek in children(head, 0, head.size).filter { it.id == SEEK }) {
                val fields = children(head, seek.start.toInt(), seek.end.toInt())
                val id = fields.firstOrNull { it.id == SEEK_ID }?.let { uint(head, it) } ?: continue
                val position = fields.firstOrNull { it.id == SEEK_POSITION }?.let { uint(head, it) } ?: continue
                val target = segment.start + position
                when (id) {
                    CHAPTERS -> if (chapters == null) chapters = bytes.element(target, segment.end)?.takeIf { it.id == CHAPTERS }
                    INFO -> if (info == null) info = bytes.element(target, segment.end)?.takeIf { it.id == INFO }
                }
            }
        }
        val found = chapters ?: return emptyList()
        val duration = info?.let { matroskaDuration(bytes.read(it.start, (it.end - it.start).coerceAtMost(MAX_BOX.toLong()).toInt())) }
        val body = bytes.read(found.start, (found.end - found.start).coerceAtMost(MAX_BOX.toLong()).toInt())
        val editions = children(body, 0, body.size).filter { it.id == EDITION }
        val edition = editions.firstOrNull { e ->
            children(body, e.start.toInt(), e.end.toInt()).any { it.id == EDITION_DEFAULT && uint(body, it) == 1L }
        } ?: editions.firstOrNull() ?: return emptyList()
        val atoms = children(body, edition.start.toInt(), edition.end.toInt()).filter { it.id == ATOM }.mapNotNull { atom ->
            val fields = children(body, atom.start.toInt(), atom.end.toInt())
            fun field(id: Long) = fields.firstOrNull { it.id == id }?.let { uint(body, it) }
            if (field(HIDDEN) == 1L || field(ENABLED) == 0L) return@mapNotNull null
            val start = field(TIME_START) ?: return@mapNotNull null
            val title = fields.firstOrNull { it.id == DISPLAY }?.let { display ->
                children(body, display.start.toInt(), display.end.toInt()).firstOrNull { it.id == CHAP_STRING }
                    ?.let { String(body, it.start.toInt(), (it.end - it.start).toInt(), Charsets.UTF_8).trimEnd('\u0000') }
            }?.takeIf { it.isNotEmpty() }
            Triple(start, field(TIME_END), title)
        }
        return withEnds(atoms, duration)
    }

    private fun matroskaDuration(info: ByteArray): Long? {
        val fields = children(info, 0, info.size)
        val scale = fields.firstOrNull { it.id == TIMESTAMP_SCALE }?.let { uint(info, it) } ?: 1_000_000L
        val duration = fields.firstOrNull { it.id == DURATION } ?: return null
        val units = when ((duration.end - duration.start).toInt()) {
            4 -> ByteBuffer.wrap(info, duration.start.toInt(), 4).float.toDouble()
            8 -> ByteBuffer.wrap(info, duration.start.toInt(), 8).double
            else -> return null
        }
        return (units * scale).toLong()
    }

    /** The elements directly inside [from] until [to] of an in-memory element body. */
    private fun children(b: ByteArray, from: Int, to: Int): List<Element> {
        val out = ArrayList<Element>()
        var at = from
        while (at < to) {
            val id = vint(b, at, keepMarker = true) ?: break
            val size = vint(b, id.second, keepMarker = false) ?: break
            val end = (size.second + size.first).coerceAtMost(to.toLong())
            out += Element(id.first, size.second.toLong(), end)
            if (end <= at) break
            at = end.toInt()
        }
        return out
    }

    private fun uint(b: ByteArray, e: Element): Long {
        var v = 0L
        for (i in e.start.toInt() until e.end.toInt().coerceAtMost(e.start.toInt() + 8)) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    // -- Shared --------------------------------------------------------------------

    /**
     * Fills the ends a container left out, as libavformat does: the next
     * chapter's start, in time order, and for the last the duration, never
     * before its own start.
     */
    private fun withEnds(chapters: List<Triple<Long, Long?, String?>>, duration: Long?): List<Chapter> {
        val starts = chapters.map { it.first }.sorted()
        return chapters.map { (start, end, title) ->
            val filled = end ?: starts.firstOrNull { it > start } ?: maxOf(start, duration ?: start)
            Chapter(start, filled, title)
        }
    }

    private fun Long.scaled(timescale: Long): Long =
        (this / timescale) * 1_000_000_000L + (this % timescale) * 1_000_000_000L / timescale

    private fun ByteArray.u32(at: Int): Int =
        if (at + 4 > size) 0 else ByteBuffer.wrap(this, at, 4).int

    /** Random access over an input, a file or a consumer's seekable source. */
    private class Bytes(input: Input) : Closeable {
        private val file = (input as? Input.File)?.let { RandomAccessFile(it.path.toFile(), "r") }
        private val cursor = (input as? Input.Source)?.cursor

        val size: Long = file?.length() ?: cursor?.source?.size()?.takeIf { it >= 0 } ?: Long.MAX_VALUE

        /** Up to [n] bytes at [at]; fewer at the end of the input. */
        fun read(at: Long, n: Int): ByteArray {
            val buffer = ByteArray(n.coerceAtLeast(0))
            var got = 0
            while (got < buffer.size) {
                val read = if (file != null) {
                    file.seek(at + got)
                    file.read(buffer, got, buffer.size - got)
                } else {
                    cursor!!.readAt(at + got, buffer, got, buffer.size - got)
                }
                if (read <= 0) break
                got += read
            }
            return if (got == buffer.size) buffer else buffer.copyOf(got)
        }

        override fun close() {
            file?.close()
        }
    }

    private const val EBML_HEADER = 0x1A45DFA3
    private val MP4_FIRST_BOXES = setOf("ftyp", "moov", "wide", "free", "skip", "mdat", "pnot")

    // Bounds against a corrupt size: chapter structures are kilobytes.
    private const val MAX_BOX = 16 * 1024 * 1024
    private const val MAX_SAMPLES = 100_000
    private const val MAX_TITLE = 64 * 1024
    private const val MAX_TITLES_TOTAL = 1024L * 1024

    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val INFO = 0x1549A966L
    private const val TIMESTAMP_SCALE = 0x2AD7B1L
    private const val DURATION = 0x4489L
    private const val CHAPTERS = 0x1043A770L
    private const val CLUSTER = 0x1F43B675L
    private const val EDITION = 0x45B9L
    private const val EDITION_DEFAULT = 0x45DBL
    private const val ATOM = 0xB6L
    private const val TIME_START = 0x91L
    private const val TIME_END = 0x92L
    private const val HIDDEN = 0x98L
    private const val ENABLED = 0x4598L
    private const val DISPLAY = 0x80L
    private const val CHAP_STRING = 0x85L
}
