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
 * one-slot mailbox a consumer polls with [acquireFrame], or waits on with
 * [awaitChange]; sound, when asked for, masters the clock, so the picture
 * follows the sound and never the reverse.
 *
 * Every call is safe from any thread. Open failures surface as
 * [State.Failed] rather than as a constructor throw, so a consumer answers
 * every failure the same way, with its own fallback (a static image).
 *
 * The engine differs by platform and the contract does not, except where a
 * member says so. On the desktop the player decodes through FFmpeg on threads
 * of its own and paces the frames itself. On Android it plays through Media3
 * on the platform codecs, and the frames are drawn on the GPU into images the
 * player owns.
 *
 * Image files play through it on both: a still, or an animated GIF, APNG or
 * WebP. Media3 plays none of them as video, so on Android the player paces
 * their frames itself, against a clock of its own. A still has one frame and
 * reads [State.Ended] unless it loops; on Android its clock then stands at
 * zero, where the desktop turns a 40 ms lap over and over.
 */
expect class VideoPlayer(
    path: Path,
    /** Whether the end of the file turns the lap; settable later through [loop]. */
    loop: Boolean = true,
    /**
     * Decode and play the file's sound. The sound then masters the clock.
     * Files without sound, and machines without a device, play silent on the
     * wall clock. Files without a picture play frameless: [acquireFrame] stays
     * null while [state] runs the usual lifecycle.
     */
    audio: Boolean = false,
    /**
     * Overrides the clock entirely; with audio on, prefer not to.
     *
     * Desktop only. On Android a player given one fails ([State.Failed]) by
     * name: the platform player takes no external media clock to pace video
     * against.
     */
    explicitClock: MediaClock? = null,
    /**
     * Where the sound goes: an implementation takes the player's PCM instead
     * of the platform output, which is how a consumer plays through its own
     * audio stack. Null takes the platform's (JavaSound on the desktop, the
     * platform audio track on Android). Read [PcmSink] before writing one:
     * it is called from several threads, its close must be idempotent, and its
     * frame position is the clock the whole player runs on. The shape is
     * negotiated down from the file's own until the sink takes one.
     *
     * Ignored entirely when [audio] is false.
     */
    sink: PcmSink? = null,
    /**
     * Decoded frames held ahead of the clock, 1..8, each costing a full RGBA
     * frame of memory. 1 suits a background; 3 to 5 lets a player ride out
     * decode stalls.
     *
     * Desktop only. On Android the platform player buffers by its own policy
     * and this has no effect.
     */
    readAheadFrames: Int = 1,
    /** The sound track to open (one of [audioTracks]); null takes the file's own pick. */
    audioTrack: Int? = null,
    /**
     * Where video is decoded. [HwAccel.OFF] decodes in software, [HwAccel.AUTO]
     * uses the hardware decoder when there is one and falls back to software
     * per file, and [HwAccel.REQUIRE] fails rather than fall back. The frames
     * are the same whichever decoded them. On Android, where every phone has a
     * hardware decoder for the common codecs, OFF is the expensive answer and
     * AUTO is usually the one wanted.
     */
    hardware: HwAccel = HwAccel.OFF,
    /**
     * What the timeline does while nobody is taking the picture; see
     * [WhenUnwatched]. Whether the picture is being taken is learnt from
     * [setPresenting], then [reportVisible], and failing both from a mailbox
     * that was being read and stops being read.
     */
    unwatched: WhenUnwatched = WhenUnwatched.FollowSound,
    /**
     * Open onto the first frame and stay on it: [state] settles
     * [State.Paused] with the first frame published, and [resume] starts the
     * file. A pause the caller owns, so [WhenUnwatched] never lifts it.
     */
    startPaused: Boolean = false,
    /**
     * Linear 0..1 volume from the first sample on, re-applied to every output
     * the player opens. Clamped; NaN leaves the default. Nothing at all
     * without [audio].
     */
    volume: Float = 1f,
    /**
     * How many channels to ask the output for: the file's own
     * ([ChannelPreference.Source]), or a fold to two before the output is asked
     * ([ChannelPreference.Stereo]). What the output took is [activeAudioFormat].
     */
    audioChannels: ChannelPreference = ChannelPreference.Source,
) : AutoCloseable {

    sealed interface State {
        data object Opening : State
        data object Playing : State
        data object Paused : State

        /** Non-looping playback ran out of frames; a seek revives it. */
        data object Ended : State

        /**
         * A seek is landing. The last frame stays on screen, and a consumer
         * may show a loading affordance. Resolves back to Playing, Paused or
         * Ended when the target lands.
         */
        data object Seeking : State

        /**
         * Open or decode failed; playback stopped for good. Fall back.
         *
         * A data class on every platform (equality and `toString` by
         * [cause]), although the shared declaration cannot say so.
         */
        class Failed(cause: Throwable) : State {
            val cause: Throwable
        }
        data object Closed : State
    }

    /**
     * One presented frame: RGBA8888, straight alpha, tightly packed. The
     * consumer owns it until its next [acquireFrame]; the player never writes
     * into it in between.
     */
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

    /** What the player is doing. Readable from any thread. */
    val state: State

    /**
     * One lap's duration as the container declares it; null while opening and
     * for a source that cannot know it.
     */
    var durationNanos: Long?
        private set

    /**
     * The file's sound tracks, by the index the selection takes. Empty while
     * opening, without [audio], and when the output failed to open.
     */
    val audioTracks: List<AudioTrack>

    /** Index of the sound track playing; null with no live sound. */
    val activeAudioTrack: Int?

    /**
     * The shape the sound actually leaves in, which is what the output agreed
     * to rather than what was asked for; null when nothing plays through one.
     */
    val activeAudioFormat: PcmFormat?

    /** Format-level tags (title, artist, ...); empty when none or while opening. */
    var tags: Map<String, String>
        private set

    /**
     * Container chapters; empty when none or while opening.
     *
     * On Android they are read by this library, since the platform reads
     * none: Matroska and WebM chapters, and MP4 and QuickTime chapter tracks
     * or Nero lists. Other containers have none there.
     */
    var chapters: List<Chapter>
        private set

    /** Encoded cover-art bytes (png or jpeg as stored), null when none. */
    var coverArt: ByteArray?
        private set

    /**
     * Clockwise degrees (0, 90, 180, 270) the frames must still be turned for
     * correct display. The Compose surface applies it; a consumer drawing
     * frames itself must do the same.
     *
     * On the desktop this is the file's orientation, since frames arrive as
     * stored. On Android it is always zero, since the platform applies the
     * orientation as it renders and frames arrive already turned.
     */
    var rotationDegrees: Int
        private set

    /**
     * True when video decodes on hardware, read off what actually decoded
     * rather than off the request. False for software decode, frameless
     * playback, and while opening.
     */
    var hardwareActive: Boolean
        private set

    /**
     * The file's subtitle tracks, side-loaded files appended under negative
     * ids. Empty while opening, for a frameless player, and when there are
     * none. Nothing subtitle-related runs until [selectSubtitleTrack].
     */
    var subtitleTracks: List<SubtitleTrack>
        private set

    /** The freshest published frame, or null when nothing newer arrived since the last call. */
    fun acquireFrame(): FrameSlot?

    /**
     * How many times something a picture depends on has changed: a frame or a
     * subtitle overlay published, a subtitle track selected or dropped,
     * [state] moving, a [setSource] refused. Only ever grows.
     */
    val changeCount: Long

    /**
     * Blocks until [changeCount] differs from [since], or for about
     * [timeoutNanos], and returns the count found. Returns at once when it
     * already differs; a timeout of zero or less never blocks. May wake early
     * without a change. Throws [InterruptedException] when the waiting thread
     * is interrupted, which is how a coroutine wrapping it is cancelled.
     */
    @Throws(InterruptedException::class)
    fun awaitChange(since: Long, timeoutNanos: Long): Long

    /**
     * The application's word on whether the picture is being taken: a window
     * minimised, an app sent to the background. What stopping costs the
     * timeline is [WhenUnwatched]'s to say. Saying it once takes the other two
     * signals out of play for good.
     */
    fun setPresenting(presenting: Boolean)

    /**
     * What whatever draws the picture can see; null withdraws the report. The
     * Compose surface reports here. Outranked by [setPresenting], and ignored
     * by a frameless player.
     */
    fun reportVisible(visible: Boolean?)

    /** Freezes playback; the last frame stays. */
    fun pause()

    /**
     * Continues from where [pause] froze, without a frame jump. Not the way
     * back from a stop the player made for a hidden picture: it plays, and the
     * picture stays off until it is being taken again.
     */
    fun resume()

    /**
     * Jumps to [ptsNanos], and revives an [State.Ended] player. Exact seeks are
     * frame-precise and cost up to a keyframe interval of decoding; with
     * [exact] false the seek lands on the keyframe at once. Skip buttons want
     * inexact, timeline scrubbing wants exact.
     */
    fun seek(ptsNanos: Long, exact: Boolean = true)

    /** Seeks relative to the intended playhead, so rapid presses accumulate to one destination. */
    fun seekBy(deltaNanos: Long, exact: Boolean = true)

    /** Advances exactly one frame and stays paused on it. */
    fun stepForward()

    /**
     * Steps back to the previous frame and stays paused on it. Finding which
     * frame precedes the shown one takes a decode from the keyframe toward it,
     * so this costs about what an exact seek costs.
     */
    fun stepBackward()

    /** Linear 0..1 volume; clamped, NaN ignored, nothing for silent playback. */
    fun setVolume(volume: Float)

    /** Playback speed; 1.0 until [setRate] changes it. */
    var rate: Float
        private set

    /** Playback speed with pitch preserved, clamped to 0.5..4; NaN ignored. Survives seeks and switches. */
    fun setRate(rate: Float)

    /**
     * Whether the end of the file turns the lap or ends playback. Settable
     * while playing, taking effect at the next end of the file; it does not
     * revive a player that has already ended.
     */
    var loop: Boolean

    /** The file playing now: the constructor's, until a [setSource] takes. */
    var source: Path
        private set

    /** Why the last [setSource] did not take, or null when it did. */
    var sourceFailure: Throwable?
        private set

    /**
     * Plays [path] on this player instead, keeping its threads, its sound
     * output, and the volume, rate and looping set on it. The new file is
     * opened and decoded to a first picture before the switch; one that cannot
     * be is refused through [sourceFailure] and the file playing carries on.
     * The position starts at zero, subtitles go off, and the metadata is the
     * new file's once the switch lands.
     */
    fun setSource(path: Path)

    /**
     * Switches the sound to another of [audioTracks], in place. A track that
     * cannot open is refused and the current one plays on.
     */
    fun selectAudioTrack(streamIndex: Int)

    /**
     * Appends [file]'s subtitle tracks to [subtitleTracks] under negative ids
     * and returns them; an unreadable file adds nothing. A frameless player
     * takes none.
     *
     * On Android the file is attached by preparing the item again at the
     * current position, so the sound pauses for as long as that takes, and
     * the format is told by extension (.srt, .ass, .ssa, .vtt, .ttml). An
     * image file playing takes none there: Media3 lays out the cues, and it is
     * not what plays an image.
     */
    fun addExternalSubtitles(file: Path): List<SubtitleTrack>

    /**
     * Turns subtitles on at one of [subtitleTracks]; null turns them off. A
     * track that fails to open degrades to no subtitles.
     *
     * On the desktop text renders through libass with full ASS typesetting.
     * On Android it is laid out by the platform's text engine at the size and
     * place the cue asks for, without libass's per-run styling, and closed
     * captions are not offered.
     */
    fun selectSubtitleTrack(id: Int?)

    /** Id of the subtitle track on screen; null when off or failed. */
    val activeSubtitleTrack: Int?

    /**
     * The freshest subtitle overlay, or null when nothing newer arrived:
     * [acquireFrame]'s contract for text. An overlay with no patches is a
     * clear. Gate drawing on [activeSubtitleTrack].
     */
    fun acquireSubtitles(): SubtitleOverlay?

    /**
     * The size subtitles rasterize at: the video's displayed rect, in pixels.
     * Idempotent, so posting it on every frame is fine; a size with no area is
     * ignored.
     */
    fun setSubtitleCanvasSize(width: Int, height: Int)

    /**
     * Current media position in nanoseconds; zero until playback starts. Not
     * clamped to [durationNanos], which a container may understate.
     */
    fun positionNanos(): Long

    /**
     * Tears the player down without waiting for it, for a caller that cannot
     * block. [state] settles [State.Closed] when the teardown finishes.
     */
    fun closeAsync()

    /** Tears the player down and waits, within a bound, for it to finish. */
    override fun close()
}
