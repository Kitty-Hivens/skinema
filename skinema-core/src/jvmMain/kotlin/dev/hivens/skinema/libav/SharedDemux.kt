package dev.hivens.skinema.libav

import dev.hivens.skinema.Debug
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import dev.hivens.skinema.core.ptsToNanos
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One demuxer over a stream that reads forward only, shared by every side
 * that plays it.
 *
 * A file or a seekable stream is opened by each side on its own, and each
 * reads at its own pace. A forward-only stream can be read once, so here one
 * thread reads it and hands each packet to the [Lane] of the stream it
 * belongs to: the picture, the sound and the subtitles each take theirs from
 * a lane instead of from a demuxer of their own. Packets of a stream nobody
 * holds a lane for are dropped.
 *
 * How far the thread reads ahead is bounded the way ffplay bounds it: past
 * [SOFT_CAP_BYTES] queued it waits, unless a side is waiting on an empty lane,
 * since a full sound lane and a starving picture would otherwise wait on each
 * other for good. [HARD_CAP_BYTES] is the guard against a side that holds a
 * lane and never reads it: past it the fullest lane nobody waits on is cut
 * back to its latest keyframe.
 *
 * Everything the sides need to open a decoder is read at the open, before the
 * thread starts, and kept: the demuxer may update its own stream and metadata
 * structures as it reads, and nothing outside its thread touches them after.
 */
internal class SharedDemux private constructor(
    private val arena: Arena,
    private val fmtCtx: MemorySegment,
    private val avio: AvioSource,
    private val source: MediaSource,
    private val softCapBytes: Long,
) : AutoCloseable {

    /** One stream as the open found it. [codecpar] belongs to the demux and lives until its close. */
    class StreamInfo(
        val index: Int,
        val mediaType: Int,
        val timeBaseNum: Int,
        val timeBaseDen: Int,
        val codecpar: MemorySegment,
        val durationNanos: Long?,
        val attachedPicture: Boolean,
        val alphaTagged: Boolean,
        val rotationDegrees: Int,
    )

    val startTimeNanos: Long = formatStartTimeNanos(fmtCtx)
    val tags: Map<String, String> = containerTags(fmtCtx, arena)
    val chapters: List<Chapter> = containerChapters(fmtCtx, arena, startTimeNanos)
    val coverArt: ByteArray? = attachedCoverArt(fmtCtx)
    val subtitleTracks: List<SubtitleTrack> = enumerateSubtitleTracks(fmtCtx, arena)
    val audioTracks: List<AudioTrack> = AudioDecoder.enumerateTracks(fmtCtx, arena)
    val streams: List<StreamInfo> = readStreams()

    /** The stream av_find_best_stream picks for the picture, or -1. */
    val bestVideo: Int = Libav.avFindBestStream(fmtCtx, LibavAbi.AVMEDIA_TYPE_VIDEO, MemorySegment.NULL)

    /** The same for the sound, or -1. */
    val bestAudio: Int = Libav.avFindBestStream(fmtCtx, LibavAbi.AVMEDIA_TYPE_AUDIO, MemorySegment.NULL)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // Guarded by [lock].
    private val lanes = HashMap<Int, Lane>()
    private var queuedBytes = 0L
    private var ended = false
    private var failure: Throwable? = null

    @Volatile
    private var closed = false

    private lateinit var thread: Thread

    private fun readStreams(): List<StreamInfo> {
        val count = fmtCtx.get(JAVA_INT, LibavAbi.FormatContext.NB_STREAMS)
        return (0 until count).map { i ->
            val stream = streamAt(fmtCtx, i)
            val par = stream.get(ADDRESS, LibavAbi.Stream.CODECPAR).reinterpret(LibavAbi.CodecParameters.SIZEOF)
            val copy = Libav.avcodecParametersAlloc()
            if (copy == MemorySegment.NULL) throw LibavException("avcodec_parameters_alloc returned NULL")
            Libav.checkAv(Libav.avcodecParametersCopy(copy, par), "avcodec_parameters_copy")
            val num = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE)
            val den = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4)
            StreamInfo(
                index = i,
                mediaType = par.get(JAVA_INT, LibavAbi.CodecParameters.CODEC_TYPE),
                timeBaseNum = num,
                timeBaseDen = den,
                codecpar = copy.reinterpret(LibavAbi.CodecParameters.SIZEOF),
                durationNanos = containerDurationNanos(fmtCtx, stream, num, den),
                attachedPicture = stream.get(JAVA_INT, LibavAbi.Stream.DISPOSITION) and LibavAbi.AV_DISPOSITION_ATTACHED_PIC != 0,
                alphaTagged = VideoDecoder.alphaTagged(arena, stream),
                rotationDegrees = displayRotationDegrees(par),
            )
        }
    }

    /**
     * The packets of one stream, in demux order. Held by one side, which takes
     * them with [take] on its own thread and lets go with [release].
     */
    inner class Lane internal constructor(val stream: StreamInfo, private val sparse: Boolean) {
        private val queue = ArrayDeque<MemorySegment>()

        // Guarded by [lock].
        internal var claimed = false
        internal var waiting = false
        private var released = false
        private var bytes = 0L

        /**
         * While parked, the lane keeps only what follows the latest keyframe:
         * the side holding it is not reading (a picture nobody watches), and
         * when it reads again it starts from a keyframe rather than from
         * everything that came since it stopped.
         */
        var parked = false
            set(value) = lock.withLock { field = value }

        /** A copy of the stream's parameters this lane's decoder owns; freed with the lane. */
        val codecpar: MemorySegment = Libav.avcodecParametersAlloc().also {
            if (it == MemorySegment.NULL) throw LibavException("avcodec_parameters_alloc returned NULL")
            Libav.checkAv(Libav.avcodecParametersCopy(it, stream.codecpar), "avcodec_parameters_copy")
        }.reinterpret(LibavAbi.CodecParameters.SIZEOF)

        /**
         * Moves the next packet into [into]: 0, AVERROR_EOF at the end of the
         * stream (or once released or closed), or AVERROR(EAGAIN) when
         * [timeoutNanos] passed with nothing to take. Throws what the source
         * raised while being read.
         */
        fun take(into: MemorySegment, timeoutNanos: Long = Long.MAX_VALUE): Int {
            val deadline = if (timeoutNanos == Long.MAX_VALUE) Long.MAX_VALUE else System.nanoTime() + timeoutNanos
            lock.withLock {
                while (queue.isEmpty()) {
                    if (released || closed) return LibavAbi.AVERROR_EOF
                    failure?.let { throw it }
                    if (ended) return LibavAbi.AVERROR_EOF
                    val left = deadline - System.nanoTime()
                    if (left <= 0) {
                        waiting = false
                        return LibavAbi.AVERROR_EAGAIN
                    }
                    if (!waiting) {
                        waiting = true
                        changed.signalAll()
                    }
                    changed.awaitNanos(minOf(left, WAIT_SLICE_NANOS))
                }
                waiting = false
                val next = queue.removeFirst()
                val size = packetSize(next)
                bytes -= size
                queuedBytes -= size
                Libav.avPacketMoveRef(into, next)
                freePacket(next)
                changed.signalAll()
                return 0
            }
        }

        /** Drops everything queued before the latest keyframe, so the next packet starts a picture. */
        fun skipToLatestKeyframe() = lock.withLock { trimToLatestKeyframe() }

        internal fun trimToLatestKeyframe(): Boolean {
            val key = queue.indexOfLast { isKey(it) }
            if (key <= 0) return false
            repeat(key) { dropFirst() }
            changed.signalAll()
            return true
        }

        internal fun add(packet: MemorySegment) {
            if (parked && isKey(packet)) while (queue.isNotEmpty()) dropFirst()
            val size = packetSize(packet)
            queue.addLast(packet)
            bytes += size
            queuedBytes += size
        }

        internal val heavy: Long get() = bytes
        internal val depth: Int get() = queue.size
        internal val isParked: Boolean get() = parked

        /** Whether this lane holds enough to stop reading for: a second of its stream, or enough packets where none are timed. */
        internal val holdsEnough: Boolean
            get() {
                if (queue.size < 2) return false
                val first = timeOf(queue.first())
                val last = timeOf(queue.last())
                if (first == LibavAbi.AV_NOPTS_VALUE || last == LibavAbi.AV_NOPTS_VALUE) return queue.size >= ENOUGH_PACKETS
                return ptsToNanos(last - first, stream.timeBaseNum, stream.timeBaseDen) >= ENOUGH_NANOS
            }
        internal val isSparse: Boolean get() = sparse
        internal val starving: Boolean get() = claimed && waiting && queue.isEmpty()
        internal val isEmpty: Boolean get() = queue.isEmpty()

        internal fun dropFirst() {
            val p = queue.removeFirst()
            val size = packetSize(p)
            bytes -= size
            queuedBytes -= size
            freePacket(p)
        }

        internal fun dropAll() {
            while (queue.isNotEmpty()) dropFirst()
        }

        /** Lets go of the lane: what it still holds is dropped and its stream is no longer kept. */
        fun release() {
            lock.withLock {
                if (released) return
                released = true
                dropAll()
                if (lanes[stream.index] === this) lanes.remove(stream.index)
                changed.signalAll()
            }
            Arena.ofConfined().use { a ->
                val p = a.allocate(ADDRESS)
                p.set(ADDRESS, 0, codecpar)
                Libav.avcodecParametersFree(p)
            }
        }
    }

    /**
     * A lane on [streamIndex] for one side. A lane the open made ready for the
     * best picture or sound stream is handed over as it is, with whatever it
     * gathered since the open, so the first keyframe is not lost to a side
     * that arrived a moment after the thread started.
     */
    fun lane(streamIndex: Int): Lane = lock.withLock {
        val info = streams.getOrNull(streamIndex) ?: throw LibavException("stream $streamIndex is not in the stream")
        val ready = lanes[streamIndex]
        if (ready != null && !ready.claimed) {
            ready.claimed = true
            return ready
        }
        if (ready != null) throw LibavException("stream $streamIndex is already being read")
        val fresh = Lane(info, sparse = info.mediaType == LibavAbi.AVMEDIA_TYPE_SUBTITLE)
        fresh.claimed = true
        lanes[streamIndex] = fresh
        fresh
    }

    /**
     * Drops the lanes the open made ready and nobody took: a player without
     * sound takes no sound lane, and one without a picture no picture lane.
     */
    fun settle() = lock.withLock {
        lanes.values.filter { !it.claimed }.forEach { it.release() }
    }

    private fun start() {
        for (index in listOf(bestVideo, bestAudio)) {
            val info = streams.getOrNull(index) ?: continue
            if (info.attachedPicture) continue
            lanes[index] = Lane(info, sparse = false)
        }
        thread = Thread(::run, "skinema-demux").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Whether to stop reading for now. Never while a side waits on an empty
     * lane. Otherwise past the byte cap, or once every lane holds enough, as
     * ffplay judges it: [ENOUGH_NANOS] of its own stream's time, or
     * [ENOUGH_PACKETS] where the packets carry no times. A source that answers
     * faster than it plays (memory, a fast disk) would otherwise be read to
     * its end at the open. Lanes made ready at the open count before a side
     * takes them, which is exactly that window. A parked lane and a sparse one
     * (subtitles, a packet every few seconds) are left out, since neither
     * ever fills.
     */
    private fun mustWait(): Boolean {
        if (lanes.values.any { it.starving }) return false
        if (queuedBytes >= softCapBytes) return true
        val active = lanes.values.filter { !it.isParked && !it.isSparse }
        return active.isNotEmpty() && active.all { it.holdsEnough }
    }

    private fun run() {
        val packet = Libav.avPacketAlloc()
        try {
            if (packet == MemorySegment.NULL) throw LibavException("av_packet_alloc(demux) returned NULL")
            val sized = packet.reinterpret(LibavAbi.Packet.SIZEOF)
            while (!closed) {
                lock.withLock {
                    while (!closed && mustWait()) changed.await(WAIT_SLICE_NANOS, TimeUnit.NANOSECONDS)
                }
                if (closed) break
                val ret = Libav.avReadFrame(fmtCtx, sized)
                if (ret < 0) {
                    val raised = runCatching { avio.throwIfFailed() }.exceptionOrNull()
                    lock.withLock {
                        if (raised != null && !closed) failure = raised
                        ended = true
                        changed.signalAll()
                    }
                    break
                }
                val index = sized.get(JAVA_INT, LibavAbi.Packet.STREAM_INDEX)
                lock.withLock {
                    val lane = lanes[index]
                    if (lane == null || !decodablePacket(sized, index)) {
                        Libav.avPacketUnref(sized)
                    } else {
                        val held = Libav.avPacketAlloc()
                        if (held == MemorySegment.NULL) throw LibavException("av_packet_alloc(lane) returned NULL")
                        Libav.avPacketMoveRef(held, sized)
                        lane.add(held.reinterpret(LibavAbi.Packet.SIZEOF))
                        if (queuedBytes > HARD_CAP_BYTES) cutBack()
                        changed.signalAll()
                    }
                }
            }
        } catch (t: Throwable) {
            lock.withLock {
                if (!closed) failure = t
                ended = true
                changed.signalAll()
            }
        } finally {
            if (packet != MemorySegment.NULL) freePacket(packet)
        }
    }

    /** Past the hard cap: the fullest lane nobody is waiting on goes back to its latest keyframe, or empty. */
    private fun cutBack() {
        val victim = lanes.values.filter { !it.starving && !it.isEmpty }.maxByOrNull { it.heavy } ?: return
        Debug.trace("stream read-ahead past ${HARD_CAP_BYTES shr 20} MiB, cutting stream ${victim.stream.index} back", Throwable())
        if (!victim.trimToLatestKeyframe()) victim.dropAll()
    }

    /**
     * Stops the thread and frees the demuxer. The source is closed first, which
     * is what breaks a read blocked inside it; a thread that still does not
     * leave within [CLOSE_JOIN_MILLIS] keeps the native state it is using, and
     * that is leaked rather than freed under it.
     */
    override fun close() {
        if (closed) return
        lock.withLock {
            closed = true
            changed.signalAll()
        }
        runCatching { source.close() }
        if (::thread.isInitialized) thread.join(CLOSE_JOIN_MILLIS)
        if (::thread.isInitialized && thread.isAlive) return
        lock.withLock {
            // A lane a side still holds keeps its parameters until that side
            // lets go, since its decoder may still read them (a fallback to
            // software reopens from them). What is queued goes now.
            lanes.values.toList().forEach { if (it.claimed) it.dropAll() else it.release() }
            lanes.clear()
        }
        Arena.ofConfined().use { a ->
            val ptrPtr = a.allocate(ADDRESS)
            streams.forEach {
                ptrPtr.set(ADDRESS, 0, it.codecpar)
                Libav.avcodecParametersFree(ptrPtr)
            }
            ptrPtr.set(ADDRESS, 0, fmtCtx)
            Libav.avformatCloseInput(ptrPtr)
            avio.free(ptrPtr)
        }
        arena.close()
    }

    private fun packetSize(p: MemorySegment): Long = p.get(JAVA_INT, LibavAbi.Packet.SIZE).toLong()

    private fun timeOf(p: MemorySegment): Long {
        val pts = p.get(JAVA_LONG, LibavAbi.Packet.PTS)
        return if (pts != LibavAbi.AV_NOPTS_VALUE) pts else p.get(JAVA_LONG, LibavAbi.Packet.DTS)
    }

    private fun isKey(p: MemorySegment): Boolean = p.get(JAVA_INT, LibavAbi.Packet.FLAGS) and LibavAbi.Packet.FLAG_KEY != 0

    private fun freePacket(p: MemorySegment) {
        Arena.ofConfined().use { a ->
            val ptrPtr = a.allocate(ADDRESS)
            ptrPtr.set(ADDRESS, 0, p)
            Libav.avPacketFree(ptrPtr)
        }
    }

    companion object {
        const val SOFT_CAP_BYTES = 16L shl 20
        const val HARD_CAP_BYTES = 64L shl 20
        const val ENOUGH_PACKETS = 25
        const val ENOUGH_NANOS = 1_000_000_000L
        private const val WAIT_SLICE_NANOS = 50_000_000L
        private const val CLOSE_JOIN_MILLIS = 2_000L

        /**
         * Opens [source] on the calling thread (the header and the stream info
         * are read here) and starts the thread that reads the rest.
         */
        fun open(source: MediaSource, softCapBytes: Long = SOFT_CAP_BYTES): SharedDemux {
            val arena = Arena.ofShared()
            val once = CloseOnce(source)
            val opened = openInput(arena, once)
            val avio = checkNotNull(opened.avioSource)
            val demux = try {
                Libav.checkAv(Libav.avformatFindStreamInfo(opened.fmtCtx), "avformat_find_stream_info(stream)")
                SharedDemux(arena, opened.fmtCtx, avio, once, softCapBytes)
            } catch (t: Throwable) {
                arena.allocate(ADDRESS).let { ptrPtr ->
                    ptrPtr.set(ADDRESS, 0, opened.fmtCtx)
                    Libav.avformatCloseInput(ptrPtr)
                    avio.free(ptrPtr)
                }
                arena.close()
                throw t
            }
            demux.start()
            return demux
        }
    }

    /** The consumer's source, closed once however many of the teardown's paths reach it. */
    private class CloseOnce(private val inner: MediaSource) : MediaSource {
        @Volatile
        private var done = false

        override fun read(dst: ByteArray, offset: Int, length: Int): Int = inner.read(dst, offset, length)

        override fun seek(position: Long): Long = inner.seek(position)

        override fun size(): Long = inner.size()

        override fun close() {
            if (done) return
            done = true
            inner.close()
        }
    }
}
