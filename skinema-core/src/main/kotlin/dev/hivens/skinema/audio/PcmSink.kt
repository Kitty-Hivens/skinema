package dev.hivens.skinema.audio

/**
 * Where PCM goes. [JavaSoundSink] in production; tests inject a fake -- CI
 * runners have no audio device, and none of the pacing or clock logic may
 * depend on one.
 *
 * ## The shape is negotiated, not dictated
 *
 * [open] takes a [PcmFormat] and may refuse it by throwing. The player asks
 * for the shape the file actually has and walks down to something this sink
 * accepts, ending at [PcmFormat.floor], which every implementation must take.
 * That is the whole negotiation: there is no capability list to keep in step
 * with the code, and a sink says what it can do by doing it.
 *
 * Two things follow for an implementation. A refusal must leave nothing
 * playing, since the next call is another [open] on this same sink. And a
 * refusal must be a throw rather than a quiet substitution: a sink that
 * accepts 5.1 and plays the front two channels is indistinguishable from one
 * that works, and the player would never learn to fold the rest itself.
 *
 * ## Nobody resamples
 *
 * The rate in the format is the media's own. The player does not convert it
 * and neither should a sink: the audio server is better placed than either,
 * and a sink that resamples turns one conversion into two. What a sink may not
 * do is accept a rate it will not honour.
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
 *   the sink: closing the line is how a write that nothing will finish gets
 *   broken out of. Two things ask for that -- a device that stopped
 *   consuming, and a player being closed while a write sits in a sink the
 *   consumer is about to take back. That write may then return or throw;
 *   both are fine, and throwing is read as the rescue rather than as a
 *   fault. It follows that [close] must be idempotent: the rescue closes the
 *   sink, and the audio thread's own teardown closes it again on the way
 *   out.
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

    /**
     * Opens the device for [format] and starts it. Reopening replaces the
     * stream: the previous buffered tail is dropped and [framePosition]
     * restarts at zero, which is what a re-anchoring clock depends on.
     *
     * Throws when this sink cannot honour [format] exactly. The player answers
     * a refusal by asking again with a narrower one, so a throw here is an
     * ordinary part of opening a file rather than a failure of it. Only a
     * refusal of [PcmFormat.floor] ends playback.
     */
    fun open(format: PcmFormat)

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

    /**
     * Linear 0..1 volume; best-effort (not every line exposes gain).
     *
     * Never called with NaN or with a value outside the range -- the player
     * clamps and refuses at its own edge, so an implementation does not have
     * to. It also does not have to remember the value across an [open]: the
     * player re-applies it to every line it opens, before the first [write],
     * which is what keeps a quiet player quiet through a track switch or a
     * device coming back.
     */
    fun setVolume(volume: Float)
}
