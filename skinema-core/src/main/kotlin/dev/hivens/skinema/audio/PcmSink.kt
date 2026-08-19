package dev.hivens.skinema.audio

/**
 * Where PCM goes: S16LE interleaved stereo throughout. [JavaSoundSink] in
 * production; tests inject a fake -- CI runners have no audio device, and
 * none of the pacing or clock logic may depend on one.
 *
 * It is also the seam for a consumer's own audio: pass an implementation as
 * the player's `sink` and the sound leaves through it instead of the
 * platform line. What the player needs back is [framePosition] -- frames the
 * device has actually PLAYED, not frames accepted -- because that number is
 * the clock the whole player runs on.
 *
 * ## Which thread calls what
 *
 * Not one thread, and an implementation has to be built for that:
 *
 * - [open], [write], [stop], [start] and [flush] come from the audio thread
 *   alone, in order, never concurrently with each other.
 * - [close] comes from the audio thread OR from its watchdog, and the
 *   watchdog's call is deliberately made while a [write] is blocked inside
 *   the sink: closing the line is how a device that stopped consuming is
 *   broken out of. That write may then return or throw; both are fine, and
 *   throwing is read as the rescue rather than as a fault.
 * - [setVolume] comes from whatever thread the consumer calls it on, at any
 *   time, including during a write.
 * - [framePosition] comes from everywhere -- the pacer, the decode thread,
 *   the subtitle thread, and the consumer's own render loop through
 *   `positionNanos()` -- many of them concurrently, and during a write.
 *
 * Two rules follow. [framePosition] and [setVolume] must be safe to call
 * while a write is in flight, and neither may wait on a lock that write
 * holds: every clock reader in the player goes through [framePosition], so
 * one that parks there parks the picture with it.
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

    /** Sample frames played since [open]; freezes while stopped. */
    fun framePosition(): Long

    /** Linear 0..1 volume; best-effort (not every line exposes gain). */
    fun setVolume(volume: Float)
}
