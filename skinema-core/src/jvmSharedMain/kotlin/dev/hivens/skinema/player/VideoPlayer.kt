package dev.hivens.skinema.player

import dev.hivens.skinema.audio.ChannelPreference
import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.libav.AudioTrack
import dev.hivens.skinema.libav.Chapter
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.SubtitleTrack
import dev.hivens.skinema.subtitles.SubtitleOverlay
import java.nio.file.Path

/**
 * Plays one media file: frames are published at their own time into a
 * mailbox a consumer polls with [acquireFrame], or waits on with
 * [awaitChange]; sound, when asked for, masters the clock. Open failures
 * surface as [State.Failed] rather than as a constructor throw, so a
 * consumer answers every failure the same way, with its own fallback.
 *
 * Each platform plays through its own engine, and its contract, parameter
 * by parameter, is documented on that platform's implementation.
 */
expect class VideoPlayer(
    path: Path,
    loop: Boolean = true,
    audio: Boolean = false,
    explicitClock: MediaClock? = null,
    sink: PcmSink? = null,
    readAheadFrames: Int = 1,
    audioTrack: Int? = null,
    hardware: HwAccel = HwAccel.OFF,
    unwatched: WhenUnwatched = WhenUnwatched.FollowSound,
    startPaused: Boolean = false,
    volume: Float = 1f,
    audioChannels: ChannelPreference = ChannelPreference.Source,
) : AutoCloseable {

    sealed interface State {
        data object Opening : State
        data object Playing : State
        data object Paused : State

        /** Non-looping playback ran out of frames; a seek revives it. */
        data object Ended : State

        /** A seek is landing; the last frame stays on screen. */
        data object Seeking : State

        /** Open or decode failed; playback stopped for good. Fall back. */
        class Failed(cause: Throwable) : State {
            val cause: Throwable
        }
        data object Closed : State
    }

    /** One presented frame, the consumer's until its next [acquireFrame]. */
    class FrameSlot {
        var width: Int
            internal set
        var height: Int
            internal set
        var ptsNanos: Long
            internal set
        var rgba: ByteArray
            internal set
    }

    /** What the player is doing; readable from any thread. */
    val state: State

    /** One lap's duration, or null while opening and for sources that cannot know. */
    var durationNanos: Long?
        private set

    /** The file's audio tracks; empty without sound or a device. */
    val audioTracks: List<AudioTrack>

    /** Stream index of the track playing; null with no live audio. */
    val activeAudioTrack: Int?

    /** The shape the sound is actually leaving in, or null when nothing plays through a device. */
    val activeAudioFormat: PcmFormat?

    /** Format-level tags. */
    var tags: Map<String, String>
        private set

    /** Container chapters. */
    var chapters: List<Chapter>
        private set

    /** Encoded cover-art bytes as stored in the file, or null. */
    var coverArt: ByteArray?
        private set

    /** Clockwise degrees (0, 90, 180, 270) the frames must be turned for display. */
    var rotationDegrees: Int
        private set

    /** True when video decodes on the GPU, read off the frames rather than the request. */
    var hardwareActive: Boolean
        private set

    /** The file's subtitle tracks, external files appended. */
    var subtitleTracks: List<SubtitleTrack>
        private set

    /** The freshest published frame, or null when nothing newer arrived since the last call. */
    fun acquireFrame(): FrameSlot?

    /** How many times something a picture depends on has changed. Only ever grows. */
    val changeCount: Long

    /** Blocks until [changeCount] differs from [since], or for about [timeoutNanos]; returns the count found. */
    @Throws(InterruptedException::class)
    fun awaitChange(since: Long, timeoutNanos: Long): Long

    /** The application's word on whether the picture is being taken. Outranks [reportVisible]. */
    fun setPresenting(presenting: Boolean)

    /** What whatever draws the picture can see; null withdraws the report. */
    fun reportVisible(visible: Boolean?)

    /** Freezes playback; the last frame stays. */
    fun pause()

    /** Continues from where [pause] froze, without a frame jump. */
    fun resume()

    /** Jumps to [ptsNanos]: frame-precise when [exact], onto the keyframe at once otherwise. */
    fun seek(ptsNanos: Long, exact: Boolean = true)

    /** Seeks relative to the intended playhead, so rapid presses accumulate. */
    fun seekBy(deltaNanos: Long, exact: Boolean = true)

    /** Advances one frame and stays paused on it. */
    fun stepForward()

    /** Steps back one frame and stays paused on it. */
    fun stepBackward()

    /** Linear 0..1 volume; clamped, NaN ignored. */
    fun setVolume(volume: Float)

    /** Playback speed; 1.0 until [setRate] changes it. */
    var rate: Float
        private set

    /** Playback speed with pitch preserved, clamped to 0.5..4; NaN ignored. */
    fun setRate(rate: Float)

    /** Whether the end of the file turns the lap or ends playback; settable while playing. */
    var loop: Boolean

    /** The file playing now. */
    var source: Path
        private set

    /** Why the last [setSource] did not take, or null when it did. */
    var sourceFailure: Throwable?
        private set

    /** Plays [path] on this player instead, keeping its threads, its sound line and its settings. */
    fun setSource(path: Path)

    /** Switches the sound to another of [audioTracks], in place. */
    fun selectAudioTrack(streamIndex: Int)

    /** Appends [file]'s subtitle tracks to [subtitleTracks] under negative ids; returns them. */
    fun addExternalSubtitles(file: Path): List<SubtitleTrack>

    /** Turns subtitles on at one of [subtitleTracks]; null turns them off. */
    fun selectSubtitleTrack(id: Int?)

    /** Id of the subtitle track on screen; null when off or failed. */
    val activeSubtitleTrack: Int?

    /** The freshest subtitle overlay, or null when nothing newer arrived. */
    fun acquireSubtitles(): SubtitleOverlay?

    /** The size subtitles rasterize at: the video's displayed rect in pixels. Idempotent. */
    fun setSubtitleCanvasSize(width: Int, height: Int)

    /** Current media position in nanoseconds; zero until playback starts. */
    fun positionNanos(): Long

    /** Tears the player down without waiting for it. */
    fun closeAsync()

    override fun close()
}
