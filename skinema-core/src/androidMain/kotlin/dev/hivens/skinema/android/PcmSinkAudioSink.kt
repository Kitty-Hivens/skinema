@file:OptIn(UnstableApi::class)

package dev.hivens.skinema.android

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import dev.hivens.skinema.Debug
import dev.hivens.skinema.audio.ChannelPreference
import dev.hivens.skinema.audio.PcmEncoding
import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.audio.formatLadder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs

/**
 * A consumer's [PcmSink] as the output ExoPlayer plays into.
 *
 * The two contracts differ where it matters. ExoPlayer hands buffers over
 * from its playback thread and expects [handleBuffer] to return at once,
 * answering false when there is no room. A [PcmSink] blocks in its write
 * until the device takes the bytes, and wants its open, write, stop, start
 * and flush from one thread, in order. So every call into the sink goes
 * through a writer thread of its own, fed by a short queue, and a full queue
 * is the "no room" ExoPlayer waits on.
 *
 * The shape is negotiated as on the desktop: the file's own first, walking
 * down [formatLadder] until the sink takes one, conversion in Kotlin where the
 * rung differs. Playback speed is applied here with Sonic, pitch preserved,
 * since the sink plays at the media's rate. The position ExoPlayer paces
 * video against is the sink's [PcmSink.framePosition], frames the device has
 * played, mapped back to media time through what each stretch of them was
 * stretched at.
 *
 * [onFormat] is told the shape the sink accepted, which is what the player
 * reports as its active audio format.
 */
internal class PcmSinkAudioSink(
    private val sink: PcmSink,
    private val channelPreference: ChannelPreference,
    private val onFormat: (PcmFormat) -> Unit,
) : AudioSink {

    // Two queues, because a pause must reach the device while sound is still
    // waiting to be written. With one queue the writer held the next chunk,
    // waiting for playback to resume, and the stop sat behind it unprocessed:
    // the device played out its buffer instead of stopping.
    private sealed interface Control {
        class Open(val format: PcmFormat, val answer: CompletableFuture<Throwable?>) : Control
        data object Start : Control
        data object Stop : Control
        class Flush(val generation: Long) : Control
        data object Close : Control
    }

    /**
     * Sound for the device: [frames] of it, played at [speed] media frames per
     * device frame, and [jumpFrames] of media time skipped before it, which is
     * how a gap in the timestamps reaches the clock at the frame it belongs to.
     */
    private class Chunk(val bytes: ByteArray, val frames: Long, val speed: Float, val jumpFrames: Long, val generation: Long)

    private val controls = LinkedBlockingQueue<Control>()
    private val chunks = LinkedBlockingQueue<Chunk>()
    private val writer = Thread(::runWriter, "skinema-pcm-sink").apply {
        isDaemon = true
        start()
    }

    // When the write now inside the sink began, zero when none is. A sink is
    // allowed to block in its write until the device takes the bytes, and not
    // forever: one that stops taking them holds every stop and flush queued
    // behind it. The PcmSink contract makes closing the sink, from another
    // thread, the way out of such a write, and the watchdog is that thread.
    @Volatile
    private var writeStartedAt = 0L

    @Volatile
    private var released = false

    private val watchdog = Thread(::runWatchdog, "skinema-pcm-sink-watchdog").apply {
        isDaemon = true
        start()
    }

    private var listener: AudioSink.Listener? = null
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var attributes = AudioAttributes.DEFAULT

    // Re-applied to every line the sink opens, which the PcmSink contract
    // leaves to the player: a sink may start each one at full gain.
    @Volatile
    private var volume = 1f

    // What ExoPlayer hands over, and what the sink took. The shape ExoPlayer
    // names is normalised to a little-endian one PcmFormat has a member for
    // before anything else sees it.
    private var input: PcmFormat? = null
    private var inputShape = InputShape.PLAIN
    private var output: PcmFormat? = null
    private var sonic: SonicAudioProcessor? = null

    // The order Media3 asks the channels to leave in: output channel i is
    // input channel mapping[i]. Set for the platform Vorbis and Opus
    // decoders, which hand multichannel sound out in Vorbis order. Null when
    // the order stands.
    private var channelMapping: IntArray? = null

    // A format that arrived while sound of the previous one was still queued:
    // applied once that has played, so no chunk reaches a line of the wrong
    // shape, as the platform sink drains to its old configuration first.
    private var pendingFormat: Format? = null
    private var pendingMapping: IntArray? = null

    private enum class InputShape { PLAIN, PACKED_24, SWAP_16, SWAP_24, SWAP_32, DOUBLE, DOUBLE_SWAPPED }

    // Media time bookkeeping, on the playback thread.
    private var startMediaUs = C.TIME_UNSET
    private var queuedMediaFrames = 0L
    private var pendingJumpFrames = 0L
    private var needsSync = false
    private var endOfStream = false

    // Written by the writer thread, read by the playback thread.
    @Volatile
    private var writtenFrames = 0L

    @Volatile
    private var baseDeviceFrames = 0L

    @Volatile
    private var generation = 0L

    @Volatile
    private var settledGeneration = 0L

    @Volatile
    private var playing = false

    @Volatile
    private var failure: Exception? = null

    private var queuedBytes = 0L

    // Bytes the writer has handed the sink in this generation. Counted under
    // the same lock a flush resets it under, and only for chunks of the
    // generation that is current, so a write that finishes after a flush is
    // not counted into the next one.
    private var drainedBytes = 0L
    private val accountLock = Any()

    private fun drained(): Long = synchronized(accountLock) { drainedBytes }

    // Device frames -> media frames: where each stretch of written sound
    // starts on the device, where it starts in the media, and at what speed
    // it was stretched. Appended by the writer as it writes, read and pruned
    // by the clock.
    private class Segment(val deviceStart: Long, val mediaStart: Long, val speed: Float)

    private val segments = ArrayDeque<Segment>()
    private val segmentLock = Any()

    // -- Formats ---------------------------------------------------------------

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return AudioSink.SINK_FORMAT_UNSUPPORTED
        return when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT,
            -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            // Raw big-endian or double PCM, which QuickTime files carry:
            // turned into one of the above here, as the platform sink does.
            C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN,
            C.ENCODING_PCM_FLOAT_BIG_ENDIAN, C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN,
            -> AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
            else -> AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val mapping = config.outputChannelMapping?.takeIf { !it.isEmpty }?.toArray()
        if (output != null && queuedBytes > drained()) {
            // The old shape's tail still plays first, Sonic's included.
            drainSonicTail()
            pendingFormat = config.format
            pendingMapping = mapping
            return
        }
        configureFor(config.format, mapping)
    }

    private fun drainSonicTail() {
        val old = sonic ?: return
        old.queueEndOfStream()
        output?.let { out -> enqueue(fromS16(drain(old), out)) }
        sonic = null
    }

    private fun configureFor(format: Format, mapping: IntArray?) {
        pendingFormat = null
        pendingMapping = null
        val (shape, encoding, bits) = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> Triple(InputShape.PLAIN, PcmEncoding.U8, 8)
            C.ENCODING_PCM_FLOAT -> Triple(InputShape.PLAIN, PcmEncoding.F32LE, 32)
            C.ENCODING_PCM_24BIT -> Triple(InputShape.PACKED_24, PcmEncoding.S32LE, 24)
            C.ENCODING_PCM_32BIT -> Triple(InputShape.PLAIN, PcmEncoding.S32LE, 32)
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> Triple(InputShape.SWAP_16, PcmEncoding.S16LE, 16)
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> Triple(InputShape.SWAP_24, PcmEncoding.S32LE, 24)
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> Triple(InputShape.SWAP_32, PcmEncoding.S32LE, 32)
            C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> Triple(InputShape.SWAP_32, PcmEncoding.F32LE, 32)
            C.ENCODING_PCM_DOUBLE -> Triple(InputShape.DOUBLE, PcmEncoding.F32LE, 32)
            C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> Triple(InputShape.DOUBLE_SWAPPED, PcmEncoding.F32LE, 32)
            else -> Triple(InputShape.PLAIN, PcmEncoding.S16LE, 16)
        }
        val channels = mapping?.size ?: format.channelCount
        val source = PcmFormat(format.sampleRate, channels, layoutFor(channels), encoding, bits)
        if (source == input && shape == inputShape && output != null && mapping.contentEquals(channelMapping)) return
        input = source
        inputShape = shape
        inputChannels = format.channelCount
        channelMapping = mapping
        val chosen = negotiate(source)
        output = chosen
        onFormat(chosen)
        sonic = newSonic()
        resetBookkeeping()
    }

    /**
     * The first rung of the ladder the sink opens, tried on the caller's
     * thread through the writer so the sink still sees one thread. A refusal
     * is the sink's way of saying no, and the next rung is asked.
     */
    private fun negotiate(source: PcmFormat): PcmFormat {
        var last: Throwable? = null
        for (rung in formatLadder(source, channelPreference)) {
            val answer = CompletableFuture<Throwable?>()
            controls.put(Control.Open(rung, answer))
            // A sink that does not answer an open is a configuration failure,
            // which ExoPlayer reports as one; a TimeoutException escaping here
            // would end the process from its playback thread.
            val refused = try {
                answer.get(5, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                throw AudioSink.ConfigurationException(IllegalStateException("the sink did not answer an open within 5 s", e), Format.Builder().build())
            }
            if (refused == null) return rung
            last = refused
        }
        throw AudioSink.ConfigurationException(last ?: IllegalStateException("the sink refused every format"), Format.Builder().build())
    }

    private fun newSonic(): SonicAudioProcessor? {
        val out = output ?: return null
        if (playbackParameters.speed == 1f) return null
        return SonicAudioProcessor().also {
            it.setSpeed(playbackParameters.speed)
            it.setPitch(1f)
            it.configure(AudioProcessor.AudioFormat(out.sampleRate, out.channels, C.ENCODING_PCM_16BIT))
            it.flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
    }

    // -- Data --------------------------------------------------------------------

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        failure?.let { throw AudioSink.WriteException(0, Format.Builder().build(), false) }
        pendingFormat?.let { next ->
            if (queuedBytes > drained()) return false
            configureFor(next, pendingMapping)
        }
        val source = input ?: return false
        val out = output ?: return false
        if (queuedBytes - drained() > maxQueuedBytes(out)) return false
        if (startMediaUs == C.TIME_UNSET) {
            startMediaUs = presentationTimeUs
        } else {
            // A buffer whose time is not where the sound so far ends: a
            // discontinuity ExoPlayer announced, or one it did not (the next
            // lap of a loop whose sound is shorter than its picture, a gap in
            // the stream). The clock takes the jump at the frame it happens
            // on, as the platform sink does.
            val expectedUs = startMediaUs + framesToUs(queuedMediaFrames, source.sampleRate)
            val offUs = presentationTimeUs - expectedUs
            if (needsSync || abs(offUs) > DISCONTINUITY_US) {
                needsSync = false
                if (offUs != 0L) {
                    val jump = offUs * source.sampleRate / 1_000_000L
                    pendingJumpFrames += jump
                    queuedMediaFrames += jump
                    listener?.onPositionDiscontinuity()
                }
            }
        }
        val raw = ByteArray(buffer.remaining())
        buffer.get(raw)
        val normalised = remap(normalise(raw), source)
        val inFrames = normalised.size / source.bytesPerFrame
        queuedMediaFrames += inFrames
        enqueue(stretch(normalised, source, out))
        return true
    }

    private fun framesToUs(frames: Long, rate: Int): Long = frames * 1_000_000L / rate

    // How many channels ExoPlayer hands over, before [channelMapping] picks.
    private var inputChannels = 0

    /** Each frame's channels in the order [channelMapping] names, in the normalised shape. */
    private fun remap(bytes: ByteArray, source: PcmFormat): ByteArray {
        val mapping = channelMapping ?: return bytes
        val sample = source.encoding.bytesPerSample
        val inFrame = inputChannels * sample
        val frames = bytes.size / inFrame
        val out = ByteArray(frames * mapping.size * sample)
        var d = 0
        for (f in 0 until frames) {
            val base = f * inFrame
            for (channel in mapping) {
                System.arraycopy(bytes, base + channel * sample, out, d, sample)
                d += sample
            }
        }
        return out
    }

    /** ExoPlayer's bytes in the little-endian shape [input] names. */
    private fun normalise(raw: ByteArray): ByteArray = when (inputShape) {
        InputShape.PLAIN -> raw
        InputShape.PACKED_24 -> widen24(raw, bigEndian = false)
        InputShape.SWAP_24 -> widen24(raw, bigEndian = true)
        InputShape.SWAP_16 -> raw.also { swapInPlace(it, 2) }
        InputShape.SWAP_32 -> raw.also { swapInPlace(it, 4) }
        InputShape.DOUBLE -> narrowDoubles(raw, ByteOrder.LITTLE_ENDIAN)
        InputShape.DOUBLE_SWAPPED -> narrowDoubles(raw, ByteOrder.BIG_ENDIAN)
    }

    /** Source shape to the sink's, through Sonic when the speed is not one. */
    private fun stretch(bytes: ByteArray, from: PcmFormat, to: PcmFormat): ByteArray {
        val stretcher = sonic
        if (stretcher == null) {
            if (from == to) return bytes
            val dst = ByteArray(bytes.size / from.bytesPerFrame * to.bytesPerFrame)
            PcmConvert.convert(bytes, bytes.size, from, to, dst)
            return dst
        }
        // Sonic works in 16-bit at the sink's channel count.
        val s16 = to.copy(encoding = PcmEncoding.S16LE, significantBits = 16)
        val mid = ByteArray(bytes.size / from.bytesPerFrame * s16.bytesPerFrame)
        PcmConvert.convert(bytes, bytes.size, from, s16, mid)
        stretcher.queueInput(ByteBuffer.wrap(mid).order(ByteOrder.LITTLE_ENDIAN))
        return fromS16(drain(stretcher), to)
    }

    private fun drain(stretcher: SonicAudioProcessor): ByteArray {
        val stretched = stretcher.output
        val out = ByteArray(stretched.remaining())
        stretched.get(out)
        return out
    }

    private fun fromS16(bytes: ByteArray, to: PcmFormat): ByteArray {
        val s16 = to.copy(encoding = PcmEncoding.S16LE, significantBits = 16)
        if (s16 == to) return bytes
        val dst = ByteArray(bytes.size / s16.bytesPerFrame * to.bytesPerFrame)
        PcmConvert.convert(bytes, bytes.size, s16, to, dst)
        return dst
    }

    private fun widen24(raw: ByteArray, bigEndian: Boolean): ByteArray {
        val samples = raw.size / 3
        val out = ByteArray(samples * 4)
        for (i in 0 until samples) {
            val s = i * 3
            out[i * 4 + 1] = raw[if (bigEndian) s + 2 else s]
            out[i * 4 + 2] = raw[s + 1]
            out[i * 4 + 3] = raw[if (bigEndian) s else s + 2]
        }
        return out
    }

    private fun swapInPlace(bytes: ByteArray, width: Int) {
        var i = 0
        while (i + width <= bytes.size) {
            for (k in 0 until width / 2) {
                val t = bytes[i + k]
                bytes[i + k] = bytes[i + width - 1 - k]
                bytes[i + width - 1 - k] = t
            }
            i += width
        }
    }

    private fun narrowDoubles(raw: ByteArray, order: ByteOrder): ByteArray {
        val src = ByteBuffer.wrap(raw).order(order)
        val dst = ByteBuffer.allocate(raw.size / 2).order(ByteOrder.LITTLE_ENDIAN)
        while (src.remaining() >= 8) dst.putFloat(src.double.toFloat())
        return dst.array()
    }

    private fun enqueue(bytes: ByteArray) {
        val out = output ?: return
        if (bytes.isEmpty()) return
        queuedBytes += bytes.size
        val jump = pendingJumpFrames
        pendingJumpFrames = 0L
        chunks.put(Chunk(bytes, (bytes.size / out.bytesPerFrame).toLong(), playbackParameters.speed, jump, generation))
    }

    private fun maxQueuedBytes(out: PcmFormat): Long = out.sampleRate.toLong() * out.bytesPerFrame * QUEUE_MILLIS / 1000

    // -- Clock ---------------------------------------------------------------------

    /**
     * Media time of the frame the device is playing now: where this stretch
     * started, plus the media frames behind the device frames played since,
     * each stretch counted at the speed it was stretched at.
     */
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val start = startMediaUs
        val out = output ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (start == C.TIME_UNSET || settledGeneration != generation) return AudioSink.CURRENT_POSITION_NOT_SET
        val played = (runCatching { sink.framePosition() }.getOrDefault(baseDeviceFrames) - baseDeviceFrames).coerceIn(0, writtenFrames)
        return start + framesToUs(mediaFramesAt(played), out.sampleRate)
    }

    /** Media frames behind [played] device frames; segments the device has passed are dropped. */
    private fun mediaFramesAt(played: Long): Long = synchronized(segmentLock) {
        while (segments.size > 1 && segments[1].deviceStart <= played) segments.removeFirst()
        val s = segments.firstOrNull() ?: return 0L
        s.mediaStart + ((played - s.deviceStart).coerceAtLeast(0) * s.speed.toDouble()).toLong()
    }

    // -- Transport -------------------------------------------------------------------

    override fun play() {
        playing = true
        controls.put(Control.Start)
    }

    override fun pause() {
        playing = false
        controls.put(Control.Stop)
    }

    override fun handleDiscontinuity() {
        needsSync = true
    }

    override fun playToEndOfStream() {
        if (endOfStream) return
        endOfStream = true
        sonic?.let {
            it.queueEndOfStream()
            val out = output ?: return
            enqueue(fromS16(drain(it), out))
        }
    }

    override fun isEnded(): Boolean = endOfStream && !hasPendingData()

    override fun hasPendingData(): Boolean {
        if (queuedBytes > drained()) return true
        val played = runCatching { sink.framePosition() }.getOrDefault(0L) - baseDeviceFrames
        return played < writtenFrames
    }

    override fun flush() {
        synchronized(accountLock) {
            generation++
            drainedBytes = 0L
        }
        chunks.clear()
        controls.put(Control.Flush(generation))
        sonic?.flush(AudioProcessor.StreamMetadata.DEFAULT)
        resetBookkeeping()
    }

    private fun resetBookkeeping() {
        startMediaUs = C.TIME_UNSET
        queuedMediaFrames = 0L
        pendingJumpFrames = 0L
        needsSync = false
        queuedBytes = 0L
        synchronized(accountLock) { drainedBytes = 0L }
        endOfStream = false
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        val next = PlaybackParameters(playbackParameters.speed, 1f)
        if (next.speed == this.playbackParameters.speed) return
        // What the old stretcher still holds is sound already counted into
        // the media, so it is played out at the speed it was taken at.
        sonic?.let { old ->
            old.queueEndOfStream()
            output?.let { out -> enqueue(fromS16(drain(old), out)) }
        }
        this.playbackParameters = next
        sonic = newSonic()
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        this.volume = v
        sink.setVolume(v)
    }

    override fun reset() {
        flush()
        input = null
        output = null
        pendingFormat = null
        pendingMapping = null
    }

    override fun release() {
        released = true
        chunks.clear()
        controls.put(Control.Close)
        runCatching { sink.close() }
        writer.join(1_000)
        watchdog.interrupt()
    }

    /** Closes the sink under a write that has not returned for [WRITE_STALL_NANOS], which fails playback by name. */
    private fun runWatchdog() {
        while (!released) {
            try {
                Thread.sleep(WATCHDOG_TICK_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
            val since = writeStartedAt
            if (since == 0L || System.nanoTime() - since < WRITE_STALL_NANOS) continue
            val stalled = IllegalStateException("the sink took no sound for ${WRITE_STALL_NANOS / 1_000_000_000} s")
            if (failure == null) failure = stalled
            runCatching { sink.close() }
            listener?.onAudioSinkError(stalled)
            writeStartedAt = 0L
        }
    }

    // -- The writer thread ----------------------------------------------------------

    // Whether the sink has a line open, on the writer thread alone. A start or
    // stop that arrives before the first open has nothing to act on: the open
    // itself leaves the line running or stopped as playback stands then.
    private var opened = false

    private fun runWriter() {
        while (true) {
            try {
                // Control first, always: a stop or a flush is never held behind
                // sound waiting to be written.
                val control = if (playing && chunks.isNotEmpty()) controls.poll() else controls.poll(5, TimeUnit.MILLISECONDS)
                if (control != null) {
                    if (!handle(control)) return
                    continue
                }
                if (!playing || !opened) continue
                val chunk = chunks.poll() ?: continue
                if (failure != null) continue
                note(chunk)
                writeStartedAt = System.nanoTime()
                try {
                    sink.write(chunk.bytes, 0, chunk.bytes.size)
                } finally {
                    writeStartedAt = 0L
                }
                writtenFrames += chunk.frames
                synchronized(accountLock) {
                    if (chunk.generation == generation) drainedBytes += chunk.bytes.size
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Debug.trace("pcm sink writer", e)
                failure = e
                listener?.onAudioSinkError(e)
            }
        }
    }

    /** Opens a new segment where [chunk] starts, when its speed or a jump says the mapping changes there. */
    private fun note(chunk: Chunk) {
        synchronized(segmentLock) {
            val at = writtenFrames
            val last = segments.lastOrNull()
            if (last != null && last.speed == chunk.speed && chunk.jumpFrames == 0L) return
            val media = if (last == null) 0L else last.mediaStart + ((at - last.deviceStart) * last.speed.toDouble()).toLong()
            segments.addLast(Segment(at, media + chunk.jumpFrames, chunk.speed))
        }
    }

    private fun restartSegments() = synchronized(segmentLock) { segments.clear() }

    /** One control command on the writer thread; false once the writer is to stop. */
    private fun handle(control: Control): Boolean {
        when (control) {
            is Control.Open -> control.answer.complete(
                runCatching {
                    opened = false
                    sink.open(control.format)
                    opened = true
                    sink.setVolume(volume)
                    // The open starts the line. It stays running only when
                    // playback already is, which a renderer enabled mid-play
                    // asks for before its format is known.
                    if (!playing) sink.stop()
                    writtenFrames = 0L
                    restartSegments()
                    baseDeviceFrames = sink.framePosition()
                    settledGeneration = generation
                }.exceptionOrNull(),
            )
            Control.Start -> if (opened) sink.start()
            Control.Stop -> if (opened) sink.stop()
            is Control.Flush -> {
                if (opened) sink.flush()
                writtenFrames = 0L
                restartSegments()
                baseDeviceFrames = if (opened) sink.framePosition() else 0L
                settledGeneration = control.generation
            }
            Control.Close -> return false
        }
        return true
    }

    // -- What a custom sink does not have -----------------------------------------------

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled(): Boolean = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        attributes = audioAttributes
    }
    override fun getAudioAttributes(): AudioAttributes = attributes
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
    override fun getAudioTrackBufferSizeUs(): Long = QUEUE_MILLIS * 1_000L
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}

    private companion object {
        /** How much sound waits between ExoPlayer and the sink. */
        const val QUEUE_MILLIS = 100L

        /** How long one write may block before the sink is taken for stuck: the queue many times over. */
        const val WRITE_STALL_NANOS = 3_000_000_000L
        const val WATCHDOG_TICK_MILLIS = 250L

        /** How far a buffer's time may stray from where the sound so far ends before the clock takes it as a jump: the platform sink's figure. */
        const val DISCONTINUITY_US = 200_000L
    }
}
