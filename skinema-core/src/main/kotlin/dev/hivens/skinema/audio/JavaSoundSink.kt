package dev.hivens.skinema.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.max

/**
 * [PcmSink] over a javax.sound.sampled [SourceDataLine]. One line per
 * player; the OS audio server mixes simultaneous players (ROADMAP.md
 * section 3), so there is deliberately no mixer here. [open] throws on
 * machines without an audio device -- the pipeline degrades to silent
 * playback on a wall clock.
 */
class JavaSoundSink : PcmSink {

    // Volatile: a track switch reopens the line on the audio thread while
    // the pacer reads framePosition through it on every pace iteration.
    @Volatile
    private var line: SourceDataLine? = null
    private var volume = 1f

    override fun open(sampleRate: Int) {
        // A reopen (track switch) drops the old line first; without this
        // the previous line keeps the device and its buffered tail.
        line?.let {
            it.stop()
            it.flush()
            it.close()
        }
        val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
        line = AudioSystem.getSourceDataLine(format).apply {
            // A deliberately small buffer. The default can run to half a
            // second, and everything queued in it is past the point of no
            // return: it keeps sounding after stop() on some backends
            // (the not-abrupt pause), plays at the old gain after a volume
            // change, holds the blocking writes that gate the audio
            // thread's commands, and -- because the device reports no
            // progress until the buffer first drains -- sets how long the
            // clock (and the video with it) stalls after a seek flush.
            //
            // 200 ms is the floor that does NOT underrun under load: 100 ms
            // measured cleaner on an idle machine but glitched intermittently
            // with the build daemon running, and an underrun freezes the
            // clock (and video) exactly like the stall it was meant to cut.
            // A deterministic 200 ms hold beats a load-dependent freeze.
            open(format, (sampleRate / 5) * BYTES_PER_FRAME)
            start()
        }
        applyVolume()
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        line?.write(data, offset, length)
    }

    override fun stop() {
        line?.stop()
    }

    override fun start() {
        line?.start()
    }

    override fun flush() {
        line?.flush()
    }

    override fun framePosition(): Long = line?.longFramePosition ?: 0L

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    private fun applyVolume() {
        val l = line ?: return
        if (!l.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val gain = l.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        // Perceptual mapping: linear volume to decibels, floored so that
        // volume 0 is effectively silence rather than -infinity.
        val db = (20f * log10(max(volume, 1e-4f))).coerceIn(gain.minimum, gain.maximum)
        gain.value = db
    }

    override fun close() {
        line?.let {
            it.stop()
            it.flush()
            it.close()
        }
        line = null
    }

    private companion object {
        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        const val BYTES_PER_FRAME = 4
    }
}
