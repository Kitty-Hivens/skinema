package dev.hivens.skinema.subtitles

import dev.hivens.skinema.Debug
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
import dev.hivens.skinema.libav.formatStartTimeNanos
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
    private val maxScheduledBitmapBytes: Long = MAX_SCHEDULED_BITMAP_BYTES,
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

    // Container start_time; subtracted so subtitle times share the video
    // and audio zero origin (see formatStartTimeNanos).
    private var startTimeNanos = 0L

    // The bitmap branch's display schedule: pixels convert ONCE at
    // decode time, windows close at their own end or at the next event,
    // whichever comes first.
    private class BitmapEvent(
        val startNanos: Long,
        var endNanos: Long,
        val patches: List<SubtitlePatch>,
    ) {
        /** Decoded pixels this window is holding; the schedule's weight. */
        val byteCount: Long = patches.sumOf { it.rgba.size.toLong() }
    }

    private val bitmapEvents = ArrayDeque<BitmapEvent>()
    private var shownBitmapStart = Long.MIN_VALUE

    /**
     * Decoded bitmap the display schedule is holding, in bytes -- the
     * retention discipline's observable, the way [lastDemuxedPtsNanos] is
     * the horizon's. Pixels convert once at decode time, so a schedule
     * that keeps its past is the largest allocation this pipeline makes.
     */
    @Volatile
    internal var scheduledBitmapBytes = 0L
        private set

    // Converted text codecs re-number ReadOrder from a flushable decoder
    // counter; only native ASS/SSA packets carry stable ones.
    private val convertedCodec = track.codecName != "ass" && track.codecName != "ssa"

    /**
     * Where the demuxer should read ahead of: the reposition target while
     * one is outstanding, the clock otherwise.
     *
     * The player announces a seek to this side BEFORE the clock reaches it
     * -- the landing decodes forward to the target first, which is seconds
     * on sparse keyframes. Gated on the clock alone, a backward seek then
     * read forward to the OLD position plus a horizon: measured at 85
     * seconds of packets for a jump from 60s back to 5s, all of it thrown
     * at a libass track that was flushed on the way in.
     */
    private var repositionTargetNanos = Long.MIN_VALUE
    private var repositionAtWall = 0L

    /**
     * Repositions performed. A test observable, like [lastDemuxedPtsNanos]:
     * a redundant one costs a demuxer seek and a preroll replay and shows up
     * nowhere else.
     */
    @Volatile
    internal var repositions = 0
        private set

    private val timeBases = HashMap<Int, Pair<Int, Int>>()

    // Declared above the thread, and that is load-bearing: Kotlin runs
    // initializers in declaration order, so a field declared after the
    // `start()` below is still null when the thread it started reads it.
    // The audio pipeline was bitten by exactly this and moved its own thread
    // last; these three are read from the subs thread's very first refill.
    private val thread = Thread(::run, "skinema-subs").apply {
        isDaemon = true
        start()
    }

    /** Latest-wins overlay; null = nothing newer than what you hold. */
    fun acquire(): SubtitleOverlay? = buffer.acquire()

    fun seek(ptsNanos: Long) {
        // Nobody is left to read it. The player keeps this reference after
        // the thread fails closed -- a track that would not open, a corrupt
        // stream -- and goes on announcing every seek to it, so a scrubbed
        // timeline grew a command per press, forever, and [pendingSeeks]
        // with it. A dead pipeline is deselected as far as the player's own
        // [dev.hivens.skinema.player.VideoPlayer.activeSubtitleTrack] is
        // concerned; it should be silent here too.
        if (isDead) return
        pendingSeeks.incrementAndGet()
        commands.put(Command.Seek(ptsNanos))
    }

    /** The subtitle render size; the surface posts its displayed rect. */
    fun setCanvasSize(width: Int, height: Int) {
        if (isDead) return
        commands.put(Command.SetCanvasSize(width, height))
    }

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
            // Clear first, before anything slow. A track switch replaces the
            // pipeline and the consumer keeps drawing the overlay it holds
            // until a newer one arrives -- so until this one had something of
            // its own to show, the PREVIOUS track's cue stayed on screen. If
            // the new track has nothing at the playhead it has nothing to
            // show for minutes, and measured against a track whose next cue
            // was fifteen seconds out, the old line simply never went away.
            // The open below is the slow part (demux probe, libass, fonts),
            // which is why this comes before it and not after.
            runCatching { publishPatches(emptyList()) }
            open()
            pump()
        } catch (t: Throwable) {
            Debug.trace("subtitle pipeline failed", t)
        } finally {
            // Dead first, then zeroed: a seek racing this way in sees the
            // flag and adds nothing, where the other order let it raise a
            // counter that had already been settled.
            isDead = true
            pendingSeeks.set(0)
            // The mailbox is single-producer: the clear that hides a dead
            // or deselected track must come from this thread.
            runCatching { publishPatches(emptyList()) }.onFailure { Debug.trace("subtitle clear on teardown", it) }
            runCatching { closeNatives() }.onFailure { Debug.trace("subtitle native close", it) }
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
        startTimeNanos = formatStartTimeNanos(fmtCtx)

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
            val mime = dictValue(metadata, mimeKey)
            val name = dictValue(metadata, nameKey)
            if (!isFontAttachment(mime, name)) continue
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
                // A backward jump with no command is a loop wrap: the player
                // announces every seek to this side, and nothing else moves
                // the clock back. The magnitude is the difference from the
                // pacer's rule, which judges by direction -- the pacer has a
                // queue flush to reset against and this side does not.
                //
                // Suppressed while an announced reposition is still
                // outstanding, because the announcement arrives BEFORE the
                // clock does: the player queues it as the seek is issued,
                // while the clock only moves once the audio thread reaches its
                // own copy of the command, between blocking writes. So the
                // landing looked like an unannounced backward jump and threw
                // away everything the announced one had just built -- a second
                // demuxer seek, a second preroll replay, and for a converted
                // codec a second track flush, which clears the screen for a
                // tick at the exact moment the seek completes.
                if (lastNow != Long.MIN_VALUE &&
                    now < lastNow - REGRESSION_NANOS &&
                    repositionTargetNanos == Long.MIN_VALUE
                ) {
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
        val gate = demuxNowNanos(nowNanos)
        while (lastDemuxedPtsNanos < gate + HORIZON_NANOS) {
            // The horizon bounds the read-ahead in time, and time is not what
            // it costs. Bitmap pixels convert once, at ingest, so a horizon's
            // worth of them is however much the stream chose to put there:
            // dialogue PGS is a few megabytes, and an animated signs track at
            // full-plane 1080p is eight megabytes per presentation set, several
            // a second, for the thirty seconds ahead plus the ten of preroll a
            // seek replays before a single window is evicted. That is gigabytes
            // reached in one burst after an ordinary scrub, and the resulting
            // OutOfMemoryError is caught by the thread's own handler, so the
            // track simply dies. Read-ahead is the right thing to give up
            // here: the clock evicts as it advances and the refill resumes.
            if (scheduledBitmapBytes > maxScheduledBitmapBytes) return
            if (Libav.avReadFrame(fmtCtx, packet) < 0) {
                eofReached = true
                return
            }
            val streamIndex = packet.get(JAVA_INT, LibavAbi.Packet.STREAM_INDEX)
            val pts = packet.get(JAVA_LONG, LibavAbi.Packet.PTS)
            if (pts != LibavAbi.AV_NOPTS_VALUE) {
                val (num, den) = timeBaseOf(streamIndex)
                // Normalize to the shared zero origin: this gate compares
                // against nowNanos (the master clock), which is normalized.
                val ptsNanos = (ptsToNanos(pts, num, den) - startTimeNanos).coerceAtLeast(0L)
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

    private fun demuxNowNanos(clockNanos: Long): Long {
        val target = repositionTargetNanos
        if (target == Long.MIN_VALUE) return clockNanos
        // The landing anchors within a frame of the target (a sample, with
        // sound), so this is arrival.
        if (clockNanos >= target - REGRESSION_NANOS && clockNanos <= target + REGRESSION_NANOS) {
            repositionTargetNanos = Long.MIN_VALUE
            return clockNanos
        }
        // Not every seek reaches its target: one past the last frame ends
        // the file instead, and the clock is then placed on the duration.
        // Bounded by the wall rather than by where the clock went, because
        // every measure of "the clock is not heading here" misfires on a
        // clock that is simply still running -- an owned one keeps ticking
        // through the whole landing. Waiting costs only read-ahead, and
        // giving up costs no more than gating on the clock always did.
        if (System.nanoTime() - repositionAtWall > LANDING_PATIENCE_NANOS) {
            repositionTargetNanos = Long.MIN_VALUE
            return clockNanos
        }
        return target
    }

    private fun timeBaseOf(streamIndex: Int): Pair<Int, Int> = timeBases.getOrPut(streamIndex) {
        val stream = streamAt(fmtCtx, streamIndex)
        stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE) to stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4)
    }

    private fun decodePacket(packet: MemorySegment, subtitle: MemorySegment, got: MemorySegment) {
        if (Libav.avcodecDecodeSubtitle2(codecCtx, subtitle, got, packet) < 0) return // a bad packet is not fatal
        try {
            // FFmpeg's contract is to free the AVSubtitle after any
            // non-negative decode, even when no event was produced -- some
            // decoders allocate into the struct regardless of got_sub_ptr.
            if (got.get(JAVA_INT, 0) == 0) return
            val pts = packet.get(JAVA_LONG, LibavAbi.Packet.PTS)
            val ptsNanos = if (pts == LibavAbi.AV_NOPTS_VALUE) {
                0L
            } else {
                (ptsToNanos(pts, timeBaseNum, timeBaseDen) - startTimeNanos).coerceAtLeast(0L)
            }
            val startOffsetMs = subtitle.get(JAVA_INT, LibavAbi.Subtitle.START_DISPLAY_TIME).toLong()
            val timecodeMs = ptsNanos / 1_000_000 + startOffsetMs
            val durationMs = packetDurationMs(packet, subtitle)
            if (assTrack == MemorySegment.NULL) {
                ingestBitmapEvent(subtitle, timecodeMs * 1_000_000, durationMs)
                return
            }
            // Text needs a number here whatever happens; a picture can stay up
            // until the next event, and does.
            val textDurationMs = durationMs ?: DEFAULT_DURATION_MS

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
                Ass.processChunk(assTrack, event, length, timecodeMs, textDurationMs)
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
    private fun ingestBitmapEvent(subtitle: MemorySegment, startNanos: Long, durationMs: Long?) {
        bitmapEvents.lastOrNull()?.let { it.endNanos = windowTruncatedAt(it.endNanos, startNanos) }

        val start = subtitle.get(JAVA_INT, LibavAbi.Subtitle.START_DISPLAY_TIME).toLong()
        val end = subtitle.get(JAVA_INT, LibavAbi.Subtitle.END_DISPLAY_TIME).toLong()
        val endNanos = bitmapWindowEnd(startNanos, start, end, durationMs)

        val numRects = subtitle.get(JAVA_INT, LibavAbi.Subtitle.NUM_RECTS)
        if (numRects == 0) {
            add(BitmapEvent(startNanos, Long.MAX_VALUE, emptyList()))
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
            if (colors <= 0) continue
            val indexBytes = indexPlaneBytes(width, height, linesize) ?: continue
            val indicesPtr = rect.get(ADDRESS, LibavAbi.SubtitleRect.DATA)
            val palettePtr = rect.get(ADDRESS, LibavAbi.SubtitleRect.DATA + ADDRESS.byteSize())
            if (indicesPtr == MemorySegment.NULL || palettePtr == MemorySegment.NULL) continue

            val indices = ByteArray(indexBytes)
            MemorySegment.copy(
                indicesPtr.reinterpret(indexBytes.toLong()), JAVA_BYTE, 0, indices, 0, indices.size,
            )
            // Every decoder allocates this plane at AVPALETTE_SIZE -- 256
            // entries -- so a count past that is a rect describing memory the
            // decoder did not allocate, and reading it is off the end. The
            // index plane next to it is bounded the same way.
            if (colors > MAX_PALETTE_ENTRIES) continue
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
        add(BitmapEvent(startNanos, endNanos, patches))
    }

    private fun add(event: BitmapEvent) {
        bitmapEvents += event
        scheduledBitmapBytes += event.byteCount
    }

    /** Publishes the schedule's state whenever the active window changes. */
    private fun bitmapTick(nowNanos: Long) {
        while (bitmapEvents.size > 1) {
            val first = bitmapEvents.first()
            if (first.endNanos == Long.MAX_VALUE || first.endNanos >= nowNanos - EVICT_NANOS) break
            scheduledBitmapBytes -= bitmapEvents.removeFirst().byteCount
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

    /** How long this cue is meant to be up; see [declaredDurationMs]. */
    private fun packetDurationMs(packet: MemorySegment, subtitle: MemorySegment): Long? = declaredDurationMs(
        // A duration delta, not a timestamp -- start_time does not apply.
        packetDuration = packet.get(JAVA_LONG, LibavAbi.Packet.DURATION),
        timeBaseNum = timeBaseNum,
        timeBaseDen = timeBaseDen,
        displayStartMs = subtitle.get(JAVA_INT, LibavAbi.Subtitle.START_DISPLAY_TIME).toLong(),
        displayEndMs = subtitle.get(JAVA_INT, LibavAbi.Subtitle.END_DISPLAY_TIME).toLong(),
    )

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
        repositions++
        val preroll = (targetNanos - PREROLL_NANOS).coerceAtLeast(0)
        val moved = runCatching {
            Libav.checkAv(
                Libav.avSeekFrame(
                    fmtCtx, track.streamIndex,
                    // Re-apply the container start_time after the preroll math.
                    nanosToPts(preroll + startTimeNanos, timeBaseNum, timeBaseDen),
                    LibavAbi.AVSEEK_FLAG_BACKWARD,
                ),
                "av_seek_frame(subs)",
            )
        }.onFailure { Debug.trace("subtitle demuxer reposition", it) }.isSuccess
        Libav.avcodecFlushBuffers(codecCtx)
        if (assTrack != MemorySegment.NULL && convertedCodec) {
            Ass.flushEvents(assTrack)
        }
        // Bitmap windows always rebuild from the preroll replay; the
        // sentinel forces the next tick to re-publish whatever state the
        // landing derives, a clear included.
        bitmapEvents.clear()
        scheduledBitmapBytes = 0L
        shownBitmapStart = Long.MAX_VALUE
        eofReached = false
        // Only a demuxer that actually moved has nothing behind it. The
        // refusal used to be discarded and the rest applied regardless, which
        // told the refill gate to re-observe the target plus a horizon from a
        // stream still standing where it was: on a backward seek that is a
        // read to EOF -- every packet of every stream -- with the subtitles
        // blank for the length of it and no way back for the rest of the file.
        // A stream that will not seek keeps demuxing forward from where it
        // is, against the clock rather than a target it never reached.
        if (moved) {
            lastDemuxedPtsNanos = Long.MIN_VALUE
            repositionTargetNanos = targetNanos
        } else {
            repositionTargetNanos = Long.MIN_VALUE
        }
        repositionAtWall = System.nanoTime()
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
        // The arena goes last and unconditionally. Its close was the last
        // statement of a block the caller wraps in runCatching, so a throw
        // from any free above it -- a libass handle a partial open left in an
        // odd state -- leaked the whole confined arena, the codec context and
        // the format context with it, once per track switch.
        arena.use { arena ->
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
        }
    }

    internal companion object {
        /** How far ahead of the clock the demuxer reads. */
        const val HORIZON_NANOS = 30_000_000_000L

        /**
         * Repositions land this far before the target: a cue that began
         * earlier but displays through the target started in this window.
         */
        const val PREROLL_NANOS = 10_000_000_000L

        /** A backward clock jump past this without a seek is a loop wrap. */
        const val REGRESSION_NANOS = 1_000_000_000L

        /**
         * How long the demux gate trusts a reposition target the clock has
         * not reached. An exact seek's landing is a keyframe jump plus a
         * decode-forward run, which is seconds on sparse-keyframe content;
         * past that the seek did not go where it said and the clock is the
         * better guide.
         */
        const val LANDING_PATIENCE_NANOS = 10_000_000_000L

        /** Render-tick cadence: brisk while time moves, lazy when it stands. */
        const val TICK_MOVING_MS = 15L
        const val TICK_IDLE_MS = 100L

        /** Cue length when neither the packet nor the subtitle knows. */
        const val DEFAULT_DURATION_MS = 10_000L

        /**
         * Closed bitmap windows this far behind the clock leave the schedule.
         *
         * Twice the largest backward move that does NOT rebuild the schedule,
         * because that is the whole reason to keep a closed window at all: a
         * jump past [REGRESSION_NANOS] repositions and re-feeds, and anything
         * smaller has to find its window still here. It used to be a minute,
         * which is a minute of decoded RGBA held for nothing -- the pixels
         * convert once at ingest, so a schedule peaked at 46 windows on
         * ordinary dialogue density, 40 MiB of them at 1080p rect sizes.
         */
        const val EVICT_NANOS = 2 * REGRESSION_NANOS

        /**
         * Whether an attachment is a font to hand libass. Containers are
         * inconsistent about which half says so -- some carry a mimetype and
         * no useful filename, some the reverse -- so either answer counts.
         * A cover image or a chapter thumbnail rides in the same attachment
         * stream and must not.
         */
        fun isFontAttachment(mime: String?, name: String?): Boolean =
            mime?.lowercase() in FONT_MIMETYPES ||
                name?.let { n -> FONT_SUFFIXES.any { n.endsWith(it, ignoreCase = true) } } == true

        /**
         * Bytes in a bitmap rect's index plane, or null when the geometry is
         * not one a subtitle can have.
         *
         * The rect comes from a decoder, so this is defence in depth rather
         * than validation -- but the arithmetic is done in Long and used as
         * an Int, and a rect claiming an implausible size would otherwise
         * pick the allocation size out of a truncated number. The ceiling is
         * far above anything real: a 4096x4096 indexed plane is 16 MiB, and
         * PGS at 1080p is nearer two.
         *
         * The geometry is bounded as well as the plane, because the plane is
         * not what gets held: it converts to RGBA at four bytes a pixel, and
         * the two are only related through a linesize the rect also supplies.
         * A rect claiming 8192x8192 with a linesize of one occupies 16 KiB
         * and asked for 256 MiB of output; one claiming 65536x8192 asked for
         * exactly 2^31 bytes, which is a negative array size and takes the
         * track down. A linesize below the width is not a stride at all.
         */
        internal fun indexPlaneBytes(width: Int, height: Int, linesize: Int): Int? {
            if (width <= 0 || height <= 0 || linesize < width) return null
            if (width.toLong() * height > MAX_RECT_PIXELS) return null
            val bytes = linesize.toLong() * (height - 1) + width
            if (bytes !in 1..MAX_RECT_BYTES) return null
            return bytes.toInt()
        }

        /**
         * When a bitmap cue stops being shown: its own declared window, else
         * the packet's duration, else open-ended until the next event.
         *
         * The middle arm used to read `durationMs in 1 until DEFAULT`, to
         * exclude the ten-second sentinel that meant "nobody knows" -- and it
         * excluded every genuine window of ten seconds or more with it. A
         * title card declaring fifteen went open-ended and was cleared by the
         * next event, or never. Absence is spelled null now, so a real
         * fifteen seconds is fifteen seconds.
         */
        internal fun bitmapWindowEnd(
            startNanos: Long,
            displayStartMs: Long,
            displayEndMs: Long,
            durationMs: Long?,
        ): Long = when {
            displayEndMs > displayStartMs -> startNanos + (displayEndMs - displayStartMs) * 1_000_000
            durationMs != null && durationMs > 0 -> startNanos + durationMs * 1_000_000
            else -> Long.MAX_VALUE
        }

        /**
         * Where a window ends once the next one begins -- the second half of
         * the rule [bitmapWindowEnd] states, and the half that was missing.
         *
         * Only an OPEN-ENDED predecessor used to be cut here, so a window
         * with a declared end outliving its successor stayed in the schedule
         * behind it. The schedule shows the last window covering the moment,
         * which hides that while the successor is open and stops hiding it
         * the instant the successor closes: a ten-second cue replaced after
         * two seconds by a one-second one came back on screen at three and
         * stayed to ten. dvdsub declares an end on every cue, so it is the
         * ordinary case there, and PGS reaches it whenever the packet's
         * duration outruns the gap to the next presentation.
         */
        internal fun windowTruncatedAt(previousEnd: Long, nextStart: Long): Long =
            if (previousEnd > nextStart) nextStart else previousEnd

        /**
         * How long a cue is meant to be up, or null when nothing says.
         *
         * Null rather than the default, because the two were the same value
         * and a caller could not tell them apart: a cue genuinely declaring
         * ten seconds or more read as "nobody knows" and became open-ended,
         * so a title card held for fifteen seconds was cleared by the next
         * event or not at all. The default belongs at the point that needs
         * one.
         *
         * A packet duration that floors to zero milliseconds is not a length
         * either. Fine grids make that ordinary -- mov_text in mp4 and
         * anything in MPEG-TS run at 90 kHz, where a duration under 90 units
         * divides away -- and a zero handed on as a known length reaches the
         * text path, which has no arm for it, and renders a cue that is never
         * on screen.
         */
        internal fun declaredDurationMs(
            packetDuration: Long,
            timeBaseNum: Int,
            timeBaseDen: Int,
            displayStartMs: Long,
            displayEndMs: Long,
        ): Long? {
            if (packetDuration > 0) {
                val ms = ptsToNanos(packetDuration, timeBaseNum, timeBaseDen) / 1_000_000
                if (ms > 0) return ms
            }
            if (displayEndMs > displayStartMs) return displayEndMs - displayStartMs
            return null
        }

        /** AVPALETTE_SIZE in entries: what every subtitle decoder allocates. */
        const val MAX_PALETTE_ENTRIES = 256

        /**
         * Ceiling on the decoded pixels the bitmap schedule holds ahead of
         * the clock. Eight full-plane 1080p presentation sets, and hundreds
         * of ordinary dialogue ones -- the read-ahead horizon is reached
         * first by everything except a track built to defeat it.
         */
        const val MAX_SCHEDULED_BITMAP_BYTES = 64L * 1024 * 1024

        /** Ceiling on a single bitmap rect's index plane; see [indexPlaneBytes]. */
        const val MAX_RECT_BYTES = 64L * 1024 * 1024

        /**
         * Ceiling on a single bitmap rect's pixel count -- what the RGBA it
         * converts to is sized from. Sixteen megapixels is four 4K frames and
         * far past anything a subtitle covers.
         */
        const val MAX_RECT_PIXELS = 16L * 1024 * 1024

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
