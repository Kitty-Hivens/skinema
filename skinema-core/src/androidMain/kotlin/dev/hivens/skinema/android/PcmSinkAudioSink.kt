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

/**
 * A consumer's [PcmSink] as the output ExoPlayer plays into.
 *
 * The two contracts differ where it matters. ExoPlayer hands buffers over
 * from its playback thread and expects [handleBuffer] to return at once,
 * answering false when there is no room; a [PcmSink] blocks in its write
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
 * played, mapped back to media time.
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

    private class Chunk(val bytes: ByteArray, val frames: Long)

    private val controls = LinkedBlockingQueue<Control>()
    private val chunks = LinkedBlockingQueue<Chunk>()
    private val writer = Thread(::runWriter, "skinema-pcm-sink").apply {
        isDaemon = true
        start()
    }

    private var listener: AudioSink.Listener? = null
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var attributes = AudioAttributes.DEFAULT

    // What ExoPlayer hands over, and what the sink took.
    private var input: PcmFormat? = null
    private var inputPacked24 = false
    private var output: PcmFormat? = null
    private var sonic: SonicAudioProcessor? = null

    // Media time bookkeeping, on the playback thread.
    private var startMediaUs = C.TIME_UNSET
    private var queuedFrames = 0L
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

    @Volatile
    private var drainedBytes = 0L

    // -- Formats ---------------------------------------------------------------

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return AudioSink.SINK_FORMAT_UNSUPPORTED
        return when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT,
            -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            else -> AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun configure(config: AudioSink.AudioSinkConfig) = configureFor(config.format)

    private fun configureFor(format: Format) {
        val (encoding, bits) = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> PcmEncoding.U8 to 8
            C.ENCODING_PCM_FLOAT -> PcmEncoding.F32LE to 32
            C.ENCODING_PCM_24BIT -> PcmEncoding.S32LE to 24
            C.ENCODING_PCM_32BIT -> PcmEncoding.S32LE to 32
            else -> PcmEncoding.S16LE to 16
        }
        val source = PcmFormat(format.sampleRate, format.channelCount, layoutFor(format.channelCount), encoding, bits)
        if (source == input && output != null) return
        input = source
        inputPacked24 = format.pcmEncoding == C.ENCODING_PCM_24BIT
        val chosen = negotiate(source)
        output = chosen
        onFormat(chosen)
        configureSonic()
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
            val refused = answer.get(5, TimeUnit.SECONDS)
            if (refused == null) return rung
            last = refused
        }
        throw AudioSink.ConfigurationException(last ?: IllegalStateException("the sink refused every format"), Format.Builder().build())
    }

    private fun configureSonic() {
        val out = output ?: return
        sonic = if (playbackParameters.speed != 1f) {
            SonicAudioProcessor().also {
                it.setSpeed(playbackParameters.speed)
                it.setPitch(1f)
                it.configure(AudioProcessor.AudioFormat(out.sampleRate, out.channels, C.ENCODING_PCM_16BIT))
                it.flush(AudioProcessor.StreamMetadata.DEFAULT)
            }
        } else {
            null
        }
    }

    // -- Data --------------------------------------------------------------------

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        failure?.let { throw AudioSink.WriteException(0, Format.Builder().build(), false) }
        val source = input ?: return false
        val out = output ?: return false
        if (queuedBytes - drainedBytes > maxQueuedBytes(out)) return false
        if (startMediaUs == C.TIME_UNSET) startMediaUs = presentationTimeUs
        val raw = ByteArray(buffer.remaining())
        buffer.get(raw)
        val widened = if (inputPacked24) widen24(raw) else raw
        val inFrames = widened.size / source.bytesPerFrame
        queuedFrames += inFrames
        enqueue(convert(widened, source, out))
        return true
    }

    /** Source shape to the sink's, through Sonic when the speed is not one. */
    private fun convert(bytes: ByteArray, from: PcmFormat, to: PcmFormat): ByteArray {
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
        val stretched = stretcher.output
        val out = ByteArray(stretched.remaining())
        stretched.get(out)
        if (s16 == to) return out
        val dst = ByteArray(out.size / s16.bytesPerFrame * to.bytesPerFrame)
        PcmConvert.convert(out, out.size, s16, to, dst)
        return dst
    }

    private fun widen24(raw: ByteArray): ByteArray {
        val samples = raw.size / 3
        val out = ByteArray(samples * 4)
        for (i in 0 until samples) {
            out[i * 4 + 1] = raw[i * 3]
            out[i * 4 + 2] = raw[i * 3 + 1]
            out[i * 4 + 3] = raw[i * 3 + 2]
        }
        return out
    }

    private fun enqueue(bytes: ByteArray) {
        val out = output ?: return
        if (bytes.isEmpty()) return
        queuedBytes += bytes.size
        chunks.put(Chunk(bytes, (bytes.size / out.bytesPerFrame).toLong()))
    }

    private fun maxQueuedBytes(out: PcmFormat): Long = out.sampleRate.toLong() * out.bytesPerFrame * QUEUE_MILLIS / 1000

    // -- Clock ---------------------------------------------------------------------

    /**
     * Media time of the frame the device is playing now: where this stretch
     * started, plus the frames played since, at the media's rate and scaled
     * by the speed they were stretched at.
     */
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val start = startMediaUs
        val out = output ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (start == C.TIME_UNSET || settledGeneration != generation) return AudioSink.CURRENT_POSITION_NOT_SET
        val played = (runCatching { sink.framePosition() }.getOrDefault(baseDeviceFrames) - baseDeviceFrames).coerceIn(0, writtenFrames)
        val mediaFrames = played * playbackParameters.speed
        return start + (mediaFrames * 1_000_000L / out.sampleRate).toLong()
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

    override fun handleDiscontinuity() {}

    override fun playToEndOfStream() {
        if (endOfStream) return
        endOfStream = true
        sonic?.let {
            it.queueEndOfStream()
            val tail = it.output
            val bytes = ByteArray(tail.remaining())
            tail.get(bytes)
            val out = output ?: return
            val s16 = out.copy(encoding = PcmEncoding.S16LE, significantBits = 16)
            enqueue(if (s16 == out) bytes else ByteArray(bytes.size / 2 * out.encoding.bytesPerSample).also { dst -> PcmConvert.convert(bytes, bytes.size, s16, out, dst) })
        }
    }

    override fun isEnded(): Boolean = endOfStream && !hasPendingData()

    override fun hasPendingData(): Boolean {
        if (queuedBytes > drainedBytes) return true
        val played = runCatching { sink.framePosition() }.getOrDefault(0L) - baseDeviceFrames
        return played < writtenFrames
    }

    override fun flush() {
        generation++
        chunks.clear()
        controls.put(Control.Flush(generation))
        sonic?.flush(AudioProcessor.StreamMetadata.DEFAULT)
        resetBookkeeping()
    }

    private fun resetBookkeeping() {
        startMediaUs = C.TIME_UNSET
        queuedFrames = 0L
        queuedBytes = 0L
        drainedBytes = 0L
        endOfStream = false
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = PlaybackParameters(playbackParameters.speed, 1f)
        configureSonic()
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setVolume(volume: Float) = sink.setVolume(volume.coerceIn(0f, 1f))

    override fun reset() {
        flush()
        input = null
        output = null
    }

    override fun release() {
        chunks.clear()
        controls.put(Control.Close)
        runCatching { sink.close() }
        writer.join(1_000)
    }

    // -- The writer thread ----------------------------------------------------------

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
                if (!playing) continue
                val chunk = chunks.poll() ?: continue
                sink.write(chunk.bytes, 0, chunk.bytes.size)
                writtenFrames += chunk.frames
                drainedBytes += chunk.bytes.size
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Debug.trace("pcm sink writer", e)
                failure = e
                listener?.onAudioSinkError(e)
            }
        }
    }

    /** One control command on the writer thread; false once the writer is to stop. */
    private fun handle(control: Control): Boolean {
        when (control) {
            is Control.Open -> control.answer.complete(
                runCatching {
                    sink.open(control.format)
                    sink.stop()
                    writtenFrames = 0L
                    baseDeviceFrames = sink.framePosition()
                    settledGeneration = generation
                }.exceptionOrNull(),
            )
            Control.Start -> sink.start()
            Control.Stop -> sink.stop()
            is Control.Flush -> {
                sink.flush()
                writtenFrames = 0L
                baseDeviceFrames = sink.framePosition()
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
    }
}
