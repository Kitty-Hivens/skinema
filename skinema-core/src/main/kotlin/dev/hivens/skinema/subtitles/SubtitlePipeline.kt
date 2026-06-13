package dev.hivens.skinema.subtitles

import dev.hivens.skinema.ass.Ass
import dev.hivens.skinema.ass.AssPatch
import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.core.TripleBuffer
import dev.hivens.skinema.core.nanosToPts
import dev.hivens.skinema.core.ptsToNanos
import dev.hivens.skinema.libav.Libav
import dev.hivens.skinema.libav.LibavAbi
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.SubtitleTrack
import dev.hivens.skinema.libav.dictValue
import dev.hivens.skinema.libav.streamAt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The subtitle half of a player: its own thread owns a demux + decode
 * session (confined arena) over its own AVFormatContext -- the audio
 * pipeline's shape -- and renders the selected track against the master
 * clock into a latest-wins overlay mailbox.
 *
 * One pipeline serves ONE track; switching tracks spawns a fresh
 * pipeline and the old one dies asynchronously. Audio switches in place
 * because a device line and the mastered clock must survive the switch;
 * subtitles own no device, so a clean replacement is both simpler and
 * equally seamless (the newcomer publishes within a tick).
 *
 * Two rules carry seek correctness (found adversarially before a line
 * was written): repositions gate their demux refill on ANY stream's
 * pts -- subtitle packets are sparse and a subtitle-pts gate would read
 * unbounded interleaved data with the command queue deaf -- and the
 * libass track flush policy is per-codec: native ASS packets embed
 * stable ReadOrders that libass dedups across replays, while converted
 * codecs (subrip, mov_text) synthesize them from a decoder counter that
 * resets on flush, so their track flushes on every reposition and the
 * preroll replay rebuilds the visible state.
 *
 * Any failure -- unopenable file, missing decoder, a corrupt stream --
 * publishes a clear and marks the pipeline [isDead]; playback never
 * notices.
 */
internal class SubtitlePipeline(
    private val path: Path,
    private val clock: MediaClock,
    val track: SubtitleTrack,
    private val storageSize: Pair<Int, Int>?,
) {

    /**
     * Seeks issued but not yet performed; tests handshake on it (the
     * audio pipeline's contract). Zeroed when the thread dies.
     */
    val pendingSeeks = AtomicInteger(0)

    /** The pipeline failed closed; the player treats it as deselected. */
    @Volatile
    var isDead = false
        private set

    /**
     * High-water mark of every demuxed packet's pts -- the horizon
     * discipline's observable: it must trail the clock by at most the
     * read-ahead horizon (plus one packet).
     */
    @Volatile
    internal var lastDemuxedPtsNanos = Long.MIN_VALUE

    private sealed interface Command {
        data class Seek(val ptsNanos: Long) : Command
        data class SetCanvasSize(val width: Int, val height: Int) : Command
        data object Close : Command
    }

    private val commands = LinkedBlockingQueue<Command>()
    private val buffer = TripleBuffer(SubtitleOverlay(), SubtitleOverlay(), SubtitleOverlay())
    private var generation = 0L

    // Thread-confined session state, opened by run().
    private lateinit var arena: Arena
    private var fmtCtx = MemorySegment.NULL
    private var codecCtx = MemorySegment.NULL
    private var assLibrary = MemorySegment.NULL
    private var assRenderer = MemorySegment.NULL
    private var assTrack = MemorySegment.NULL
    private var canvasWidth = 0
    private var canvasHeight = 0
    private var eofReached = false
    private var timeBaseNum = 1
    private var timeBaseDen = 1

    // The bitmap branch's display schedule: pixels convert ONCE at
    // decode time, windows close at their own end or at the next event,
    // whichever comes first.
    private class BitmapEvent(
        val startNanos: Long,
        var endNanos: Long,
        val patches: List<SubtitlePatch>,
    )

    private val bitmapEvents = ArrayDeque<BitmapEvent>()
    private var shownBitmapStart = Long.MIN_VALUE

    // Converted text codecs re-number ReadOrder from a flushable decoder
    // counter; only native ASS/SSA packets carry stable ones.
    private val convertedCodec = track.codecName != "ass" && track.codecName != "ssa"

    private val thread = Thread(::run, "skinema-subs").apply {
        isDaemon = true
        start()
    }

    /** Latest-wins overlay; null = nothing newer than what you hold. */
    fun acquire(): SubtitleOverlay? = buffer.acquire()

    fun seek(ptsNanos: Long) {
        pendingSeeks.incrementAndGet()
        commands.put(Command.Seek(ptsNanos))
    }

    /** The subtitle render size; the surface posts its displayed rect. */
    fun setCanvasSize(width: Int, height: Int) = commands.put(Command.SetCanvasSize(width, height))

    /** Mid-play teardown: no join -- a blocked read must not hitch a frame. */
    fun closeAsync() {
        commands.put(Command.Close)
    }

    /** Player teardown: joined, so the arena dies before the player does. */
    fun close() {
        commands.put(Command.Close)
        thread.join(5_000)
    }

    // -- Subtitle thread -------------------------------------------------------

    private fun run() {
        arena = Arena.ofConfined()
        try {
            open()
            pump()
        } catch (_: Throwable) {
        } finally {
            pendingSeeks.set(0)
            isDead = true
            // The mailbox is single-producer: the clear that hides a dead
            // or deselected track must come from this thread.
            runCatching { publishPatches(emptyList()) }
            runCatching { closeNatives() }
        }
    }

    private fun open() {
        val ctxOut = arena.allocate(ADDRESS)
        Libav.checkAv(
            Libav.avformatOpenInput(ctxOut, arena.allocateFrom(path.toString())),
            "avformat_open_input($path)",
        )
        fmtCtx = ctxOut.get(ADDRESS, 0).reinterpret(LibavAbi.FormatContext.SIZEOF)
        Libav.checkAv(Libav.avformatFindStreamInfo(fmtCtx), "avformat_find_stream_info(subs)")

        val streamCount = fmtCtx.get(JAVA_INT, LibavAbi.FormatContext.NB_STREAMS)
        if (track.streamIndex >= streamCount) throw LibavException("stream ${track.streamIndex} is out of range")
        val stream = streamAt(fmtCtx, track.streamIndex)
        val codecpar = stream.get(ADDRESS, LibavAbi.Stream.CODECPAR)
            .reinterpret(LibavAbi.CodecParameters.SIZEOF)
        if (codecpar.get(JAVA_INT, LibavAbi.CodecParameters.CODEC_TYPE) != LibavAbi.AVMEDIA_TYPE_SUBTITLE) {
            throw LibavException("stream ${track.streamIndex} is not a subtitle stream")
        }
        timeBaseNum = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE)
        timeBaseDen = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4)

        val decoder = Libav.avcodecFindDecoder(codecpar.get(JAVA_INT, LibavAbi.CodecParameters.CODEC_ID))
        if (decoder == MemorySegment.NULL) throw LibavException("no decoder for subtitle track ${track.id}")
        codecCtx = Libav.avcodecAllocContext3(decoder)
        if (codecCtx == MemorySegment.NULL) throw LibavException("avcodec_alloc_context3(subs) returned NULL")
        Libav.checkAv(Libav.avcodecParametersToContext(codecCtx, codecpar), "avcodec_parameters_to_context(subs)")
        Libav.checkAv(Libav.avcodecOpen2(codecCtx, decoder), "avcodec_open2(subs)")

        if (track.isText) {
            check(Ass.available) { "text subtitles need libass" }
            assLibrary = Ass.libraryInit()
            if (assLibrary == MemorySegment.NULL) throw LibavException("ass_library_init returned NULL")
            // Anime mkv ships its typesetting fonts as attachments; they
            // must be in the library before the renderer's font provider
            // initializes.
            Ass.setExtractFonts(assLibrary, true)
            addAttachedFonts()
            assRenderer = Ass.rendererInit(assLibrary)
            if (assRenderer == MemorySegment.NULL) throw LibavException("ass_renderer_init returned NULL")
            canvasWidth = storageSize?.first ?: DEFAULT_CANVAS_WIDTH
            canvasHeight = storageSize?.second ?: DEFAULT_CANVAS_HEIGHT
            Ass.setFrameSize(assRenderer, canvasWidth, canvasHeight)
            storageSize?.let { Ass.setStorageSize(assRenderer, it.first, it.second) }
            Ass.setFonts(assRenderer, arena.allocateFrom("sans-serif"))
            assTrack = Ass.newTrack(assLibrary)
            if (assTrack == MemorySegment.NULL) throw LibavException("ass_new_track returned NULL")
            // The ASS header (styles included) comes from the OPENED
            // decoder context: converted codecs (subrip, mov_text)
            // synthesize a default header there while their codecpar
            // extradata stays empty; native ASS copies its extradata in.
            val sized = codecCtx.reinterpret(LibavAbi.CodecContext.SIZEOF)
            val headerSize = sized.get(JAVA_INT, LibavAbi.CodecContext.SUBTITLE_HEADER_SIZE)
            if (headerSize > 0) {
                val header = sized.get(ADDRESS, LibavAbi.CodecContext.SUBTITLE_HEADER)
                if (header != MemorySegment.NULL) {
                    Ass.processCodecPrivate(assTrack, header, headerSize)
                }
            }
        } else {
            // Bitmap planes carry their own geometry; the overlay canvas
            // IS that plane and the consumer scales it onto the video.
            val planeWidth = codecpar.get(JAVA_INT, LibavAbi.CodecParameters.WIDTH)
            val planeHeight = codecpar.get(JAVA_INT, LibavAbi.CodecParameters.HEIGHT)
            canvasWidth = if (planeWidth > 0) planeWidth else storageSize?.first ?: DEFAULT_CANVAS_WIDTH
            canvasHeight = if (planeHeight > 0) planeHeight else storageSize?.second ?: DEFAULT_CANVAS_HEIGHT
        }
    }

    private fun addAttachedFonts() {
        val mimeKey = arena.allocateFrom("mimetype")
        val nameKey = arena.allocateFrom("filename")
        val count = fmtCtx.get(JAVA_INT, LibavAbi.FormatContext.NB_STREAMS)
        for (i in 0 until count) {
            val stream = streamAt(fmtCtx, i)
            val codecpar = stream.get(ADDRESS, LibavAbi.Stream.CODECPAR)
                .reinterpret(LibavAbi.CodecParameters.SIZEOF)
            if (codecpar.get(JAVA_INT, LibavAbi.CodecParameters.CODEC_TYPE) != LibavAbi.AVMEDIA_TYPE_ATTACHMENT) continue
            val metadata = stream.get(ADDRESS, LibavAbi.Stream.METADATA)
            val mime = dictValue(metadata, mimeKey)?.lowercase()
            val name = dictValue(metadata, nameKey)
            val fontLike = mime in FONT_MIMETYPES ||
                name?.let { n -> FONT_SUFFIXES.any { n.endsWith(it, ignoreCase = true) } } == true
            if (!fontLike) continue
            val size = codecpar.get(JAVA_INT, LibavAbi.CodecParameters.EXTRADATA_SIZE)
            if (size <= 0) continue
            val data = codecpar.get(ADDRESS, LibavAbi.CodecParameters.EXTRADATA)
            if (data == MemorySegment.NULL) continue
            Ass.addFont(assLibrary, arena.allocateFrom(name ?: "embedded"), data, size)
        }
    }

    private fun pump() {
        val packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
        if (packet == MemorySegment.NULL) throw LibavException("av_packet_alloc(subs) returned NULL")
        try {
            val subtitle = arena.allocate(LibavAbi.Subtitle.SIZEOF)
            val got = arena.allocate(JAVA_INT)
            val change = arena.allocate(JAVA_INT)
            var lastNow = Long.MIN_VALUE
            while (true) {
                val now = clock.mediaNanos()
                if (lastNow != Long.MIN_VALUE && now < lastNow - REGRESSION_NANOS) {
                    // A backward jump with no command is a loop wrap (the
                    // pacer's rule); treat it as a seek to now.
                    reposition(now)
                }
                val moving = now != lastNow
                lastNow = now

                refill(packet, subtitle, got, now)

                if (assTrack != MemorySegment.NULL) {
                    val head = Ass.renderFrame(assRenderer, assTrack, now / 1_000_000, change)
                    if (change.get(JAVA_INT, 0) != 0) {
                        publishPatches(Ass.parseImages(head))
                    }
                } else {
                    bitmapTick(now)
                }

                val cmd = commands.poll(if (moving) TICK_MOVING_MS else TICK_IDLE_MS, TimeUnit.MILLISECONDS)
                if (cmd != null && !handle(cmd)) return
            }
        } finally {
            val ptrPtr = arena.allocate(ADDRESS)
            ptrPtr.set(ADDRESS, 0, packet)
            Libav.avPacketFree(ptrPtr)
        }
    }

    /**
     * Keeps the demuxer [HORIZON_NANOS] ahead of the clock. The gate
     * closes on EVERY demuxed packet's pts: subtitle packets alone are
     * sparse enough that gating on them would read unbounded interleaved
     * video/audio after each seek, deaf to commands the whole time.
     */
    private fun refill(packet: MemorySegment, subtitle: MemorySegment, got: MemorySegment, nowNanos: Long) {
        if (eofReached) return
        while (lastDemuxedPtsNanos < nowNanos + HORIZON_NANOS) {
            if (Libav.avReadFrame(fmtCtx, packet) < 0) {
                eofReached = true
                return
            }
            val streamIndex = packet.get(JAVA_INT, LibavAbi.Packet.STREAM_INDEX)
            val pts = packet.get(JAVA_LONG, LibavAbi.Packet.PTS)
            if (pts != LibavAbi.AV_NOPTS_VALUE) {
                val (num, den) = timeBaseOf(streamIndex)
                val ptsNanos = ptsToNanos(pts, num, den)
                if (ptsNanos > lastDemuxedPtsNanos) lastDemuxedPtsNanos = ptsNanos
            }
            if (streamIndex == track.streamIndex) {
                decodePacket(packet, subtitle, got)
            }
            Libav.avPacketUnref(packet)
            // A post-seek preroll spans seconds of packets; a queued seek
            // or close must not wait out the whole replay.
            if (commands.peek() != null) return
        }
    }

    private val timeBases = HashMap<Int, Pair<Int, Int>>()

    private fun timeBaseOf(streamIndex: Int): Pair<Int, Int> = timeBases.getOrPut(streamIndex) {
        val stream = streamAt(fmtCtx, streamIndex)
        stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE) to stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4)
    }

    private fun decodePacket(packet: MemorySegment, subtitle: MemorySegment, got: MemorySegment) {
        if (Libav.avcodecDecodeSubtitle2(codecCtx, subtitle, got, packet) < 0) return // a bad packet is not fatal
        if (got.get(JAVA_INT, 0) == 0) return
        try {
            val pts = packet.get(JAVA_LONG, LibavAbi.Packet.PTS)
            val ptsNanos = if (pts == LibavAbi.AV_NOPTS_VALUE) 0L else ptsToNanos(pts, timeBaseNum, timeBaseDen)
            val startOffsetMs = subtitle.get(JAVA_INT, LibavAbi.Subtitle.START_DISPLAY_TIME).toLong()
            val timecodeMs = ptsNanos / 1_000_000 + startOffsetMs
            val durationMs = packetDurationMs(packet, subtitle)
            if (assTrack == MemorySegment.NULL) {
                ingestBitmapEvent(subtitle, timecodeMs * 1_000_000, durationMs)
                return
            }

            val numRects = subtitle.get(JAVA_INT, LibavAbi.Subtitle.NUM_RECTS)
            if (numRects == 0) return
            val rects = subtitle.get(ADDRESS, LibavAbi.Subtitle.RECTS)
                .reinterpret(numRects * ADDRESS.byteSize())
            for (i in 0 until numRects) {
                val rect = rects.getAtIndex(ADDRESS, i.toLong()).reinterpret(LibavAbi.SubtitleRect.SIZEOF)
                if (rect.get(JAVA_INT, LibavAbi.SubtitleRect.TYPE) != LibavAbi.SUBTITLE_ASS) continue
                val event = rect.get(ADDRESS, LibavAbi.SubtitleRect.ASS)
                if (event == MemorySegment.NULL) continue
                // The event is fed from its native string directly; only
                // its length is computed here (tiny, zero allocation).
                val bytes = event.reinterpret(Long.MAX_VALUE)
                var length = 0
                while (bytes.get(JAVA_BYTE, length.toLong()) != ZERO_BYTE) length++
                Ass.processChunk(assTrack, event, length, timecodeMs, durationMs)
            }
        } finally {
            Libav.avsubtitleFree(subtitle)
        }
    }

    /**
     * One decoded bitmap display set joins the schedule. The window
     * closes at the subtitle's own end time, the packet duration, or
     * stays open until the NEXT event (the pgs idiom: content sets
     * follow each other and num_rects == 0 is the explicit clear).
     */
    private fun ingestBitmapEvent(subtitle: MemorySegment, startNanos: Long, durationMs: Long) {
        // The next event ends an open-ended predecessor either way.
        bitmapEvents.lastOrNull()?.takeIf { it.endNanos == Long.MAX_VALUE }?.endNanos = startNanos

        val start = subtitle.get(JAVA_INT, LibavAbi.Subtitle.START_DISPLAY_TIME).toLong()
        val end = subtitle.get(JAVA_INT, LibavAbi.Subtitle.END_DISPLAY_TIME).toLong()
        val endNanos = when {
            end > start -> startNanos + (end - start) * 1_000_000
            durationMs in 1 until DEFAULT_DURATION_MS -> startNanos + durationMs * 1_000_000
            else -> Long.MAX_VALUE
        }

        val numRects = subtitle.get(JAVA_INT, LibavAbi.Subtitle.NUM_RECTS)
        if (numRects == 0) {
            bitmapEvents += BitmapEvent(startNanos, Long.MAX_VALUE, emptyList())
            return
        }
        val rects = subtitle.get(ADDRESS, LibavAbi.Subtitle.RECTS)
            .reinterpret(numRects * ADDRESS.byteSize())
        val patches = mutableListOf<SubtitlePatch>()
        for (i in 0 until numRects) {
            val rect = rects.getAtIndex(ADDRESS, i.toLong()).reinterpret(LibavAbi.SubtitleRect.SIZEOF)
            if (rect.get(JAVA_INT, LibavAbi.SubtitleRect.TYPE) != LibavAbi.SUBTITLE_BITMAP) continue
            val width = rect.get(JAVA_INT, LibavAbi.SubtitleRect.W)
            val height = rect.get(JAVA_INT, LibavAbi.SubtitleRect.H)
            val colors = rect.get(JAVA_INT, LibavAbi.SubtitleRect.NB_COLORS)
            val linesize = rect.get(JAVA_INT, LibavAbi.SubtitleRect.LINESIZE)
            if (width <= 0 || height <= 0 || colors <= 0 || linesize <= 0) continue
            val indicesPtr = rect.get(ADDRESS, LibavAbi.SubtitleRect.DATA)
            val palettePtr = rect.get(ADDRESS, LibavAbi.SubtitleRect.DATA + ADDRESS.byteSize())
            if (indicesPtr == MemorySegment.NULL || palettePtr == MemorySegment.NULL) continue

            val indexBytes = linesize.toLong() * (height - 1) + width
            val indices = ByteArray(indexBytes.toInt())
            MemorySegment.copy(indicesPtr.reinterpret(indexBytes), JAVA_BYTE, 0, indices, 0, indices.size)
            val palette = IntArray(colors)
            val paletteSeg = palettePtr.reinterpret(colors * 4L)
            for (c in 0 until colors) palette[c] = paletteSeg.getAtIndex(JAVA_INT, c.toLong())

            patches += SubtitlePatch().apply {
                x = rect.get(JAVA_INT, LibavAbi.SubtitleRect.X)
                y = rect.get(JAVA_INT, LibavAbi.SubtitleRect.Y)
                this.width = width
                this.height = height
                rgba = paletteToRgba(indices, linesize, width, height, palette)
            }
        }
        bitmapEvents += BitmapEvent(startNanos, endNanos, patches)
    }

    /** Publishes the schedule's state whenever the active window changes. */
    private fun bitmapTick(nowNanos: Long) {
        while (bitmapEvents.size > 1) {
            val first = bitmapEvents.first()
            if (first.endNanos == Long.MAX_VALUE || first.endNanos >= nowNanos - EVICT_NANOS) break
            bitmapEvents.removeFirst()
        }
        val active = bitmapEvents.lastOrNull { it.startNanos <= nowNanos && nowNanos < it.endNanos }
        val key = active?.startNanos ?: Long.MIN_VALUE
        if (key == shownBitmapStart) return
        shownBitmapStart = key
        publishBitmap(active?.patches ?: emptyList())
    }

    private fun publishBitmap(patches: List<SubtitlePatch>) {
        val slot = buffer.writing
        slot.patches = patches
        slot.canvasWidth = canvasWidth
        slot.canvasHeight = canvasHeight
        slot.generation = ++generation
        buffer.publish()
    }

    /** Packet duration, else the subtitle's own window, else 10s (circus). */
    private fun packetDurationMs(packet: MemorySegment, subtitle: MemorySegment): Long {
        val packetDuration = packet.get(JAVA_LONG, LibavAbi.Packet.DURATION)
        if (packetDuration > 0) return ptsToNanos(packetDuration, timeBaseNum, timeBaseDen) / 1_000_000
        val start = subtitle.get(JAVA_INT, LibavAbi.Subtitle.START_DISPLAY_TIME).toLong()
        val end = subtitle.get(JAVA_INT, LibavAbi.Subtitle.END_DISPLAY_TIME).toLong()
        if (end > start) return end - start
        return DEFAULT_DURATION_MS
    }

    private fun handle(cmd: Command): Boolean = when (cmd) {
        Command.Close -> false
        is Command.Seek -> {
            // Coalesce a burst: one reposition at the final target.
            var target = cmd.ptsNanos
            var consumed = 1
            while (true) {
                val next = commands.peek()
                if (next !is Command.Seek) break
                commands.poll()
                target = next.ptsNanos
                consumed++
            }
            reposition(target)
            pendingSeeks.addAndGet(-consumed)
            true
        }
        is Command.SetCanvasSize -> {
            // Text only: bitmap patches are fixed to their plane and the
            // consumer scales them; re-sizing their canvas would lie.
            if (assRenderer != MemorySegment.NULL && cmd.width > 0 && cmd.height > 0 &&
                (cmd.width != canvasWidth || cmd.height != canvasHeight)
            ) {
                canvasWidth = cmd.width
                canvasHeight = cmd.height
                // Glyphs rasterize at the displayed size; the storage
                // size keeps the PAR/positioning math on the video.
                Ass.setFrameSize(assRenderer, canvasWidth, canvasHeight)
            }
            true
        }
    }

    /**
     * Repositions the demuxer at [targetNanos] minus a preroll: matroska
     * cues align to video keyframes, and a cue that began before the
     * target but displays through it lives in that window. Converted
     * codecs flush their libass track and decoder on EVERY reposition
     * (forward included -- the preroll replays events already fed, and
     * a continued ReadOrder counter would stack them as duplicates);
     * native ASS never flushes, its embedded ReadOrders dedup.
     */
    private fun reposition(targetNanos: Long) {
        val preroll = (targetNanos - PREROLL_NANOS).coerceAtLeast(0)
        runCatching {
            Libav.checkAv(
                Libav.avSeekFrame(
                    fmtCtx, track.streamIndex,
                    nanosToPts(preroll, timeBaseNum, timeBaseDen),
                    LibavAbi.AVSEEK_FLAG_BACKWARD,
                ),
                "av_seek_frame(subs)",
            )
        }
        Libav.avcodecFlushBuffers(codecCtx)
        if (assTrack != MemorySegment.NULL && convertedCodec) {
            Ass.flushEvents(assTrack)
        }
        // Bitmap windows always rebuild from the preroll replay; the
        // sentinel forces the next tick to re-publish whatever state the
        // landing derives, a clear included.
        bitmapEvents.clear()
        shownBitmapStart = Long.MAX_VALUE
        eofReached = false
        lastDemuxedPtsNanos = Long.MIN_VALUE
    }

    private fun publishPatches(patches: List<AssPatch>) {
        val slot = buffer.writing
        val blended = blendAssPatches(patches, slot.scratch.rgba)
        if (blended == null) {
            slot.patches = emptyList()
        } else {
            slot.scratch.x = blended.x
            slot.scratch.y = blended.y
            slot.scratch.width = blended.width
            slot.scratch.height = blended.height
            slot.scratch.rgba = blended.rgba
            slot.patches = listOf(slot.scratch)
        }
        slot.canvasWidth = canvasWidth
        slot.canvasHeight = canvasHeight
        slot.generation = ++generation
        buffer.publish()
    }

    private fun closeNatives() {
        if (assTrack != MemorySegment.NULL) Ass.freeTrack(assTrack)
        if (assRenderer != MemorySegment.NULL) Ass.rendererDone(assRenderer)
        if (assLibrary != MemorySegment.NULL) Ass.libraryDone(assLibrary)
        val ptrPtr = arena.allocate(ADDRESS)
        if (codecCtx != MemorySegment.NULL) {
            ptrPtr.set(ADDRESS, 0, codecCtx)
            Libav.avcodecFreeContext(ptrPtr)
        }
        if (fmtCtx != MemorySegment.NULL) {
            ptrPtr.set(ADDRESS, 0, fmtCtx)
            Libav.avformatCloseInput(ptrPtr)
        }
        arena.close()
    }

    private companion object {
        /** How far ahead of the clock the demuxer reads. */
        const val HORIZON_NANOS = 30_000_000_000L

        /**
         * Repositions land this far before the target: a cue that began
         * earlier but displays through the target started in this window.
         */
        const val PREROLL_NANOS = 10_000_000_000L

        /** A backward clock jump past this without a seek is a loop wrap. */
        const val REGRESSION_NANOS = 1_000_000_000L

        /** Render-tick cadence: brisk while time moves, lazy when it stands. */
        const val TICK_MOVING_MS = 15L
        const val TICK_IDLE_MS = 100L

        /** Cue length when neither the packet nor the subtitle knows. */
        const val DEFAULT_DURATION_MS = 10_000L

        /** Closed bitmap windows this far behind the clock leave the schedule. */
        const val EVICT_NANOS = 60_000_000_000L

        /** Attachment mimetypes the wild uses for fonts. */
        val FONT_MIMETYPES = setOf(
            "font/ttf", "font/otf", "font/sfnt", "font/collection",
            "application/x-truetype-font", "application/x-font-ttf",
            "application/vnd.ms-opentype", "application/font-sfnt",
        )
        val FONT_SUFFIXES = listOf(".ttf", ".otf", ".ttc")

        /** Text render size when the video's geometry is unknown. */
        const val DEFAULT_CANVAS_WIDTH = 640
        const val DEFAULT_CANVAS_HEIGHT = 480

        const val ZERO_BYTE = 0.toByte()
    }
}
