package dev.hivens.skinema.libav

import dev.hivens.skinema.Debug
import dev.hivens.skinema.core.ptsToNanos
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One demuxer over a stream that reads forward only, shared by every side
 * that plays it.
 *
 * A file or a seekable stream is opened by each side on its own, and each
 * reads at its own pace. A forward-only stream can be read once, so here one
 * thread reads it and hands each packet to the [Lane]s of the stream it
 * belongs to: the picture, the sound and the subtitles each take theirs from
 * a lane instead of from a demuxer of their own. A stream may have more than
 * one lane for a moment (a subtitle track turned off and on again, the old
 * reader still winding down), and each gets every packet. Packets of a
 * stream nobody holds a lane for are dropped.
 *
 * How far the thread reads ahead is bounded the way ffplay bounds it: about
 * a second of each stream a side reads, or [SOFT_CAP_BYTES] queued, unless a
 * side is waiting on an empty lane, since a full sound lane and a starving
 * picture would otherwise wait on each other for good. A lane whose stream
 * has gone quiet (ended before the others, or a feed that stopped) counts
 * for neither, judged in stream time so a fast source is caught at once. [HARD_CAP_BYTES] is the guard against a side that holds a lane
 * and never reads it: past it the fullest lane nobody waits on is cut back
 * to its latest keyframe.
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
) : AutoCloseable, Interruptible {

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
    private val lanes = HashMap<Int, MutableList<Lane>>()
    private var queuedBytes = 0L
    private var ended = false
    private var failure: Throwable? = null
    private var settled = false
    private val startedNanos = System.nanoTime()

    // The furthest any stream has got, in its own time from its own origin.
    private var furthestNanos = Long.MIN_VALUE

    // Set by [interrupt]: reads end at once, the thread leaves, nothing more is
    // handed out. The native state stays until [close] frees it.
    @Volatile
    private var interrupted = false

    private val closing = AtomicBoolean(false)

    private lateinit var thread: Thread

    private fun readStreams(): List<StreamInfo> {
        val count = fmtCtx.get(JAVA_INT, LibavAbi.FormatContext.NB_STREAMS)
        val made = ArrayList<StreamInfo>(count)
        try {
            for (i in 0 until count) {
                val stream = streamAt(fmtCtx, i)
                val par = stream.get(ADDRESS, LibavAbi.Stream.CODECPAR).reinterpret(LibavAbi.CodecParameters.SIZEOF)
                val copy = copyParameters(par)
                val num = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE)
                val den = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4)
                made += StreamInfo(
                    index = i,
                    mediaType = par.get(JAVA_INT, LibavAbi.CodecParameters.CODEC_TYPE),
                    timeBaseNum = num,
                    timeBaseDen = den,
                    codecpar = copy,
                    durationNanos = containerDurationNanos(fmtCtx, stream, num, den),
                    attachedPicture = stream.get(JAVA_INT, LibavAbi.Stream.DISPOSITION) and LibavAbi.AV_DISPOSITION_ATTACHED_PIC != 0,
                    alphaTagged = VideoDecoder.alphaTagged(arena, stream),
                    rotationDegrees = displayRotationDegrees(par),
                )
            }
        } catch (t: Throwable) {
            made.forEach { freeParameters(it.codecpar) }
            throw t
        }
        return made
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

        // Where this stream last got to, in its own time. A lane made after the
        // others have run starts level with them.
        private var lastNanos = furthestNanos

        /**
         * While parked, the lane keeps only what follows the latest keyframe:
         * the side holding it is not reading (a picture nobody watches), and
         * when it reads again it starts from a keyframe rather than from
         * everything that came since it stopped.
         */
        var parked = false
            set(value) = lock.withLock { field = value }

        /** A copy of the stream's parameters this lane's decoder owns. Freed with the lane. */
        val codecpar: MemorySegment = copyParameters(stream.codecpar)

        /**
         * Moves the next packet into [into]: 0, AVERROR_EOF at the end of the
         * stream (or once released, interrupted or closed), or AVERROR(EAGAIN)
         * when [timeoutNanos] passed with nothing to take. Throws what the
         * source raised while being read.
         */
        fun take(into: MemorySegment, timeoutNanos: Long = Long.MAX_VALUE): Int {
            val deadline = if (timeoutNanos == Long.MAX_VALUE) Long.MAX_VALUE else System.nanoTime() + timeoutNanos
            lock.withLock {
                while (queue.isEmpty()) {
                    if (released || interrupted) return LibavAbi.AVERROR_EOF
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

        /**
         * Drops everything queued before the latest keyframe. True when the
         * next packet starts a picture, which is when a decoder flushed for a
         * fresh start has something to start from. False with no keyframe
         * queued: the packets that are there carry on from what the decoder
         * already has, and flushing it would leave them nothing to refer to.
         */
        fun skipToLatestKeyframe(): Boolean = lock.withLock {
            trimToLatestKeyframe()
            queue.firstOrNull()?.let { isKey(it) } == true
        }

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
            val t = timeOf(packet)
            if (t != LibavAbi.AV_NOPTS_VALUE) {
                val at = ptsToNanos(t, stream.timeBaseNum, stream.timeBaseDen)
                lastNanos = at
                if (at > furthestNanos) furthestNanos = at
            }
        }

        internal val heavy: Long get() = bytes
        internal val depth: Int get() = queue.size
        internal val isParked: Boolean get() = parked
        internal val isPicture: Boolean get() = stream.mediaType == LibavAbi.AVMEDIA_TYPE_VIDEO

        /** Whether this lane holds enough to stop reading for: a second of its stream, or enough packets where none are timed. */
        internal val holdsEnough: Boolean
            get() {
                if (queue.size < 2) return false
                val first = timeOf(queue.first())
                val last = timeOf(queue.last())
                if (first == LibavAbi.AV_NOPTS_VALUE || last == LibavAbi.AV_NOPTS_VALUE) return queue.size >= ENOUGH_PACKETS
                return ptsToNanos(last - first, stream.timeBaseNum, stream.timeBaseDen) >= ENOUGH_NANOS
            }

        /**
         * Whether this stream has gone quiet: the others are [QUIET_NANOS] of
         * their own time past its last packet. A stream that ended before the
         * others is the ordinary case, and its side waits at the end of an
         * empty lane until the whole input ends. Counted as starving, it would
         * turn the bound off for the rest of the input. Stream time rather than
         * the wall's, since a fast source reads seconds of stream in a moment
         * and would be read to the hard cap before any wall-clock wait ran out.
         */
        internal val isQuiet: Boolean
            get() = furthestNanos != Long.MIN_VALUE && lastNanos != Long.MIN_VALUE && furthestNanos - lastNanos > QUIET_NANOS
        internal val isSparse: Boolean get() = sparse
        internal val starving: Boolean get() = claimed && waiting && queue.isEmpty() && !isQuiet
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

        /** Lets go of the lane: what it still holds is dropped and its stream is no longer kept for it. */
        fun release() {
            lock.withLock {
                if (released) return
                released = true
                dropAll()
                lanes[stream.index]?.let { list ->
                    list.remove(this)
                    if (list.isEmpty()) lanes.remove(stream.index)
                }
                changed.signalAll()
            }
            freeParameters(codecpar)
        }
    }

    private fun allLanes(): List<Lane> = lanes.values.flatten()

    /**
     * A lane on [streamIndex] for one side. A lane the open made ready for the
     * best picture or sound stream is handed over as it is, with whatever it
     * gathered since the open, so the first keyframe is not lost to a side
     * that arrived a moment after the thread started. Refused once the demux
     * is interrupted or closed: what a lane is copied from may be gone.
     */
    fun lane(streamIndex: Int): Lane = lock.withLock {
        if (interrupted || closing.get()) throw LibavException("the stream was closed")
        val info = streams.getOrNull(streamIndex) ?: throw LibavException("stream $streamIndex is not in the stream")
        val list = lanes.getOrPut(streamIndex) { mutableListOf() }
        list.firstOrNull { !it.claimed }?.let {
            it.claimed = true
            return it
        }
        val fresh = Lane(info, sparse = info.mediaType == LibavAbi.AVMEDIA_TYPE_SUBTITLE)
        fresh.claimed = true
        list += fresh
        fresh
    }

    /**
     * Drops the lanes the open made ready and nobody took: a player without
     * sound takes no sound lane, and one without a picture no picture lane.
     * The thread does it on its own a few seconds after the open, for an
     * owner that never says.
     */
    fun settle() = lock.withLock {
        settled = true
        allLanes().filter { !it.claimed }.forEach { it.release() }
    }

    private fun start() {
        for (index in listOf(bestVideo, bestAudio)) {
            val info = streams.getOrNull(index) ?: continue
            if (info.attachedPicture) continue
            lanes.getOrPut(index) { mutableListOf() } += Lane(info, sparse = false)
        }
        thread = Thread(::run, "skinema-demux").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Whether to stop reading for now. Never while a side waits on an empty
     * lane. Otherwise past the byte cap, or once every lane a side reads holds
     * enough, as ffplay judges it: [ENOUGH_NANOS] of its own stream's time, or
     * [ENOUGH_PACKETS] where the packets carry no times. A source that answers
     * faster than it plays (memory, a fast disk) would otherwise be read to
     * its end at the open. Lanes made ready at the open count before a side
     * takes them, which is exactly that window. A parked lane, a sparse one
     * (subtitles, a packet every few seconds) and a quiet one are left out,
     * since none of them fills. With nothing left to count, the parked lanes
     * bound it (a picture hidden while nothing else is read), and with no
     * lane at all once settled there is nobody to read for.
     */
    private fun mustWait(): Boolean {
        val all = allLanes()
        if (all.any { it.starving }) return false
        if (queuedBytes >= softCapBytes) return true
        val active = all.filter { !it.isParked && !it.isSparse && !it.isQuiet }
        if (active.isNotEmpty()) return active.all { it.holdsEnough }
        val parked = all.filter { it.isParked }
        if (parked.isNotEmpty()) return parked.all { it.holdsEnough }
        return settled
    }

    private fun run() {
        val packet = Libav.avPacketAlloc()
        try {
            if (packet == MemorySegment.NULL) throw LibavException("av_packet_alloc(demux) returned NULL")
            val sized = packet.reinterpret(LibavAbi.Packet.SIZEOF)
            while (!interrupted) {
                lock.withLock {
                    if (!settled && System.nanoTime() - startedNanos > SETTLE_AFTER_NANOS) settle()
                    while (!interrupted && mustWait()) changed.await(WAIT_SLICE_NANOS, TimeUnit.NANOSECONDS)
                }
                if (interrupted) break
                val ret = Libav.avReadFrame(fmtCtx, sized)
                if (ret < 0) {
                    val raised = runCatching { avio.throwIfFailed() }.exceptionOrNull()
                    lock.withLock {
                        if (raised != null && !interrupted) failure = raised
                        ended = true
                        changed.signalAll()
                    }
                    break
                }
                val index = sized.get(JAVA_INT, LibavAbi.Packet.STREAM_INDEX)
                lock.withLock {
                    val list = lanes[index]
                    if (list.isNullOrEmpty() || !decodablePacket(sized, index)) {
                        Libav.avPacketUnref(sized)
                    } else {
                        val held = Libav.avPacketAlloc()
                        if (held == MemorySegment.NULL) throw LibavException("av_packet_alloc(lane) returned NULL")
                        Libav.avPacketMoveRef(held, sized)
                        val first = held.reinterpret(LibavAbi.Packet.SIZEOF)
                        // Every lane after the first gets its own reference to
                        // the same data.
                        for (lane in list.drop(1)) {
                            val copy = Libav.avPacketClone(first)
                            if (copy == MemorySegment.NULL) throw LibavException("av_packet_clone(lane) returned NULL")
                            lane.add(copy.reinterpret(LibavAbi.Packet.SIZEOF))
                        }
                        list.first().add(first)
                        if (queuedBytes > HARD_CAP_BYTES) cutBack()
                        changed.signalAll()
                    }
                }
            }
        } catch (t: Throwable) {
            lock.withLock {
                if (!interrupted) failure = t
                ended = true
                changed.signalAll()
            }
        } finally {
            if (packet != MemorySegment.NULL) freePacket(packet)
        }
    }

    /**
     * Past the hard cap: the fullest lane nobody is waiting on goes back to its
     * latest keyframe. A picture lane with no later keyframe is left alone,
     * since dropping it would hand its decoder packets that refer to pictures
     * it never saw. The next lane goes instead, and a sound or subtitle lane is
     * emptied.
     */
    private fun cutBack() {
        val candidates = allLanes().filter { !it.starving && !it.isEmpty }.sortedByDescending { it.heavy }
        for (victim in candidates) {
            Debug.trace("stream read-ahead past ${HARD_CAP_BYTES shr 20} MiB, cutting stream ${victim.stream.index} back", Throwable())
            if (victim.trimToLatestKeyframe()) return
            if (victim.isPicture) continue
            victim.dropAll()
            return
        }
    }

    /**
     * Breaks every read at once and waits for nothing: the source is closed,
     * which ends a read blocked inside it, every lane answers the end, and
     * the thread leaves at its next turn. What a close frees stays until
     * [close] runs on the owner's thread.
     */
    override fun interrupt() {
        lock.withLock {
            interrupted = true
            changed.signalAll()
        }
        runCatching { source.close() }
    }

    /**
     * Stops the thread and frees the demuxer, waiting at most [joinMillis] for
     * the thread to leave. The source is closed first, which is what breaks a
     * read blocked inside it. A thread that still does not leave keeps the
     * native state it is using, and that is leaked rather than freed under it.
     */
    fun close(joinMillis: Long) {
        if (!closing.compareAndSet(false, true)) return
        interrupt()
        if (::thread.isInitialized) {
            if (joinMillis > 0) thread.join(joinMillis)
            if (thread.isAlive) return
        }
        lock.withLock {
            // A lane a side still holds keeps its parameters until that side
            // lets go, since its decoder may still read them (a fallback to
            // software reopens from them). What is queued goes now.
            allLanes().forEach { if (it.claimed) it.dropAll() else it.release() }
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

    override fun close() = close(CLOSE_JOIN_MILLIS)

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
        const val QUIET_NANOS = 2_000_000_000L
        const val SETTLE_AFTER_NANOS = 5_000_000_000L
        private const val WAIT_SLICE_NANOS = 50_000_000L
        private const val CLOSE_JOIN_MILLIS = 2_000L

        private fun copyParameters(from: MemorySegment): MemorySegment {
            val copy = Libav.avcodecParametersAlloc()
            if (copy == MemorySegment.NULL) throw LibavException("avcodec_parameters_alloc returned NULL")
            val ret = Libav.avcodecParametersCopy(copy, from)
            if (ret < 0) {
                freeParameters(copy)
                Libav.checkAv(ret, "avcodec_parameters_copy")
            }
            return copy.reinterpret(LibavAbi.CodecParameters.SIZEOF)
        }

        private fun freeParameters(par: MemorySegment) {
            Arena.ofConfined().use { a ->
                val p = a.allocate(ADDRESS)
                p.set(ADDRESS, 0, par)
                Libav.avcodecParametersFree(p)
            }
        }

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
            try {
                demux.start()
            } catch (t: Throwable) {
                demux.close(0)
                throw t
            }
            return demux
        }
    }
}
