package dev.hivens.skinema.audio

/**
 * Where PCM goes: S16LE interleaved stereo throughout. [JavaSoundSink] in
 * production; tests inject a fake -- CI runners have no audio device, and
 * none of the pacing or clock logic may depend on one.
 */
interface PcmSink : AutoCloseable {

    /** Opens the device for [sampleRate] Hz S16LE stereo and starts it. */
    fun open(sampleRate: Int)

    /** Blocking write: returns once the device accepted all [length] bytes. */
    fun write(data: ByteArray, offset: Int, length: Int)

    /** Pauses the device; [framePosition] freezes until [start]. */
    fun stop()

    /** Resumes after [stop]. */
    fun start()

    /** Discards buffered-but-unplayed data (seek). */
    fun flush()

    /** Blocks until everything written has actually played (EOF). */
    fun drain()

    /** Sample frames played since [open]; freezes while stopped. */
    fun framePosition(): Long

    /** Linear 0..1 volume; best-effort (not every line exposes gain). */
    fun setVolume(volume: Float)
}
