package dev.hivens.skinema.player

import dev.hivens.skinema.ass.Ass
import dev.hivens.skinema.audio.AudioPipeline
import dev.hivens.skinema.audio.JavaSoundSink
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.core.PlaybackClock
import dev.hivens.skinema.core.TripleBuffer
import dev.hivens.skinema.libav.AudioTrack
import dev.hivens.skinema.libav.Chapter
import dev.hivens.skinema.libav.FrameSource
import dev.hivens.skinema.libav.FrameSources
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.NoVideoStreamException
import dev.hivens.skinema.libav.SubtitleTrack
import dev.hivens.skinema.libav.VideoDecoder
import dev.hivens.skinema.libav.probeSubtitleFile
import dev.hivens.skinema.subtitles.SubtitleOverlay
import dev.hivens.skinema.subtitles.SubtitlePipeline
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays one video file: a dedicated decode thread keeps a small queue
 * stocked with converted frames, and a pacer thread publishes each one
 * into a tear-free [TripleBuffer] at its pts. Presentation living on
 * its own thread is what makes the queue worth having -- a decode stall
 * cannot stall the screen while inventory lasts.
 *
 * Core stays dependency-free by design (ROADMAP.md section 3): no
 * coroutines, no UI types. The consumer polls [acquireFrame] on its own
 * cadence (a Compose frame clock, a render loop) -- null means "nothing
 * newer than what you already hold" -- and reads [state] for lifecycle.
 *
 * Everything libav happens on the decode thread, open and close
 * included: the decoder's arena is confined to it (the pacer touches
 * only heap arrays, the clock, and the mailbox). Open failures
 * therefore surface as [State.Failed] rather than a constructor throw --
 * the fail-closed path (ROADMAP.md section 2) a consumer answers with a
 * static fallback.
 */
class VideoPlayer internal constructor(
    private val path: Path,
    private val loop: Boolean,
    audio: Boolean,
    private val explicitClock: MediaClock?,
    sink: PcmSink?,
    readAheadFrames: Int,
    audioTrack: Int?,
    private val unwatched: WhenUnwatched,
    private val startPaused: Boolean,
    volume: Float,
    // The test seam: a Path is the only public way in, so deterministic
    // sources (scripted hiccups) enter here. No defaults on this
    // constructor -- the test source set sees internal members, and a
    // second defaulted overload would make every call ambiguous.
    private val frameSourceFactory: (Path) -> FrameSource,
) : AutoCloseable {

    constructor(
        path: Path,
        loop: Boolean = true,
        /**
         * Decode and play the file's audio stream. The audio sink then
         * masters the player's clock (ROADMAP.md section 3); files without
         * an audio stream -- and machines without an audio device -- degrade
         * to silent wall-clock playback. Audio-only files play frameless:
         * [acquireFrame] stays null while [state] runs the usual lifecycle.
         */
        audio: Boolean = false,
        /** Overrides the clock entirely; with audio on, prefer not to. */
        explicitClock: MediaClock? = null,
        /**
         * Where the sound goes. An implementation of [PcmSink] takes the
         * player's S16LE stereo PCM instead of the platform line, which is how
         * a consumer plays through its own audio stack -- no change in this
         * library is needed for that, the seam is here. Null (the default)
         * takes [JavaSoundSink].
         *
         * Read [PcmSink] before writing one: it is called from several threads,
         * its [PcmSink.close] must be idempotent, and [PcmSink.framePosition]
         * is the clock the whole player runs on.
         *
         * Ignored entirely when [audio] is false -- nothing decodes sound, so
         * nothing ever opens it. That is not an error, and the sink is left
         * untouched rather than opened and closed.
         */
        sink: PcmSink? = null,
        /**
         * How many decoded frames the player holds ahead of the clock,
         * 1..8. At 1 (the default) decode runs a single frame ahead --
         * the right footprint for backgrounds; 3-5 lets a player scenario
         * ride out decode stalls up to that many frame periods. Each step
         * of depth costs one full RGBA frame of memory (8.3 MB at 1080p)
         * on top of the mailbox's three slots.
         */
        readAheadFrames: Int = 1,
        /**
         * Stream index of the audio track to open (one of [audioTracks]);
         * null takes the demuxer's best pick. Switch later with
         * [selectAudioTrack].
         */
        audioTrack: Int? = null,
        /**
         * Hardware-decode policy. [HwAccel.OFF] (default) is pure software
         * decode -- the historical behaviour and the only CI-tested path.
         * [HwAccel.AUTO] uses the platform's GPU decoder when present and
         * falls back to software per file otherwise; [HwAccel.REQUIRE]
         * fails the open ([State.Failed]) when hardware decode cannot be
         * set up. The RGBA frame contract is identical on every path.
         */
        hardware: HwAccel = HwAccel.OFF,
        /**
         * What the timeline does while nobody is taking frames -- see
         * [WhenUnwatched]. A player nobody reads is not free: it decodes,
         * converts and paces pictures into a mailbox nothing empties, which
         * for a launcher minimised to the tray is a core spent on a window
         * that is not on screen.
         *
         * It applies both ways round. A consumer that knows says so with
         * [setPresenting]; one that says nothing is noticed anyway, once the
         * mailbox it HAD been reading goes unread.
         */
        unwatched: WhenUnwatched = WhenUnwatched.Freeze,
        /**
         * Open onto the first frame and stay on it: [state] settles
         * [State.Paused] rather than [State.Playing], and [resume] is what
         * starts the file.
         *
         * The picture is up before that -- the first frame publishes the way a
         * seek landing does, so the surface shows it while the player waits.
         * A poster frame with no picture would be a black rectangle, which is
         * not what asking for one means.
         *
         * The pause belongs to the caller from the start, so it is the kind
         * [WhenUnwatched] never lifts: only [resume] ends it.
         */
        startPaused: Boolean = false,
        /**
         * Linear 0..1 volume from the first sample onward, rather than from
         * whenever a [setVolume] call gets through -- a player that has to
         * start quiet cannot afford the gap, since the sink opens and takes
         * its first chunk on the audio thread's own schedule.
         *
         * Applied to every line this player opens, so a track switch or a
         * device-loss recovery comes back at the volume asked for instead of
         * the device's default. Out-of-range values are clamped; NaN is not a
         * volume and leaves the default standing, as [setVolume] does with
         * one. Nothing at all without `audio = true`.
         */
        volume: Float = 1f,
    ) : this(
        path, loop, audio, explicitClock, sink, readAheadFrames, audioTrack, unwatched,
        startPaused, volume,
        { FrameSources.open(it, hardware) },
    )

    sealed interface State {
        data object Opening : State
        data object Playing : State
        data object Paused : State

        /** Non-looping playback ran out of frames; a seek revives it. */
        data object Ended : State

        /**
         * A seek is landing: the demuxer jumped to a keyframe and is
         * decoding forward to the target. The last frame stays on screen;
         * a consumer may show a loading affordance. Resolves back to
         * Playing / Paused / Ended when the target frame lands.
         */
        data object Seeking : State

        /** Open or decode failed; playback stopped for good. Fall back. */
        data class Failed(val cause: Throwable) : State
        data object Closed : State
    }

    /**
     * One presented frame. The consumer owns the returned slot until its
     * next [acquireFrame] call; the player never writes into it during
     * that window.
     */
    class FrameSlot internal constructor(size: Int = 0) {
        var width = 0
            internal set
        var height = 0
            internal set
        var ptsNanos = 0L
            internal set
        var rgba = ByteArray(size)
            internal set
    }

    private sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        data class Seek(val ptsNanos: Long, val exact: Boolean) : Command
        data class SeekBy(val deltaNanos: Long, val exact: Boolean) : Command
        data class SetRate(val rate: Float) : Command
        data class SelectSubtitles(val id: Int?) : Command
        data object StepForward : Command
        data object StepBackward : Command
        data class SetPresenting(val presenting: Boolean) : Command
        data object Close : Command

        /**
         * The pacer freed a queue cell. Without this token the fill side
         * discovers room only when its command poll times out, putting
         * dead time on every frame of full-queue (steady-state) playback
         * -- which caps production below the frame rate of 60 fps
         * content. Handled as a no-op; its arrival is the point.
         *
         * One token per publish into an unbounded queue reads like a leak and
         * is not. A publish is only possible for a frame the fill side put
         * there, and the fill side drains every command at the top of each
         * pass, so what can be outstanding is bounded by the inventory depth
         * rather than by how long the player runs. The two paths that do not
         * come back to that top drain them where they stand: the seek landing
         * peeks past them explicitly, so a token cannot hide a queued seek
         * behind it, and the lap wait handles them as it polls.
         */
        data object RoomFreed : Command
    }

    @Volatile
    private var stateField: State = State.Opening

    /**
     * What the player is doing. Written from the decode thread and from the
     * pacer, read from anywhere.
     */
    val state: State get() = stateField

    /**
     * The one way state is written, and the reason it is a function.
     *
     * [State.Failed] is terminal by contract -- it is the fail-closed promise
     * the whole library rests on -- but it was not terminal in fact. The pacer
     * publishes it from its own thread, and the decode thread then wrote over
     * it unconditionally: a pacer dying while a seek was landing had its
     * failure replaced by Seeking, then by Playing when the landing finished,
     * and finally by Closed when the pacer's own Close command arrived. The
     * consumer watched Playing -> Seeking -> Playing -> Closed and saw a
     * player that had shut itself down tidily, with the cause gone -- so the
     * fallback the failure exists to trigger never ran.
     *
     * Guarding the three unconditional sites would have been whack-a-mole; a
     * fourth would reintroduce it. The write itself refuses instead.
     *
     * No test, and the reason is worth keeping so it is not rediscovered. The
     * interleaving above could not be built. Two attempts, both traced: aiming
     * a seek at a keyframe leaves no decode-forward run to catch the pacer in,
     * and parking the run at a non-keyframe target stops the pacer dying at all
     * -- a landing clears the queue, and with nothing to pace the pacer never
     * reaches the clock read that would throw. At readAheadFrames = 1 the two
     * requirements exclude each other. So this is a contract made true by
     * construction rather than a defect reproduced and fixed; if a consumer
     * ever reports the Playing -> Seeking -> Playing -> Closed sequence, the
     * inventory depth is where to look first.
     */
    private fun publishState(next: State) {
        if (stateField is State.Failed && next !is State.Failed) return
        stateField = next
    }

    /**
     * Total duration of one lap, as the container reports it; null while
     * [State.Opening] and for sources that cannot know (animated webp).
     * With [positionNanos] this is the timeline.
     */
    @Volatile
    var durationNanos: Long? = null
        private set

    /**
     * The file's audio tracks. Empty while [State.Opening], without
     * `audio = true`, and when the audio device failed to open -- a dead
     * pipeline must not advertise a working selector.
     */
    @Volatile
    var audioTracks: List<AudioTrack> = emptyList()
        private set

    /** Stream index of the track playing; null when no live audio. */
    val activeAudioTrack: Int?
        get() = if (audioTracks.isEmpty()) null else audioPipeline?.activeAudioTrack

    /** Format-level tags (title, artist, ...); empty when none or Opening. */
    @Volatile
    var tags: Map<String, String> = emptyMap()
        private set

    /** Container chapters -- timeline markers; empty when none or Opening. */
    @Volatile
    var chapters: List<Chapter> = emptyList()
        private set

    /**
     * Encoded cover-art bytes (png/jpeg as stored in the file), null when
     * none. The consumer decodes them with its own image stack; frameless
     * playback typically shows this where frames would be.
     */
    @Volatile
    var coverArt: ByteArray? = null
        private set

    /**
     * Clockwise degrees (0/90/180/270) the frames must be rotated for
     * correct display -- phone footage carries its orientation as
     * metadata. VideoSurface applies it; a consumer drawing frames
     * itself must do the same.
     */
    @Volatile
    var rotationDegrees: Int = 0
        private set

    /**
     * True when video is decoding on the GPU. False for software decode,
     * frameless playback, and while [State.Opening]. Hardware decode is
     * opt-in (the `hardware` constructor parameter) and silently falls back
     * to software when no device or codec support is present, so this is
     * the only signal that it actually engaged.
     *
     * Read off the frames, not off the request: a device can open and the
     * hwaccel still fail to initialise for the stream, and until a frame has
     * come back there is nothing to read. So this can go true at the open and
     * false again once decoding starts.
     */
    @Volatile
    var hardwareActive: Boolean = false
        private set

    /**
     * The container's subtitle streams (external files appended by
     * [addExternalSubtitles] later). Empty while [State.Opening], for
     * frameless playback, and when the file carries none. Selection is
     * off by default; nothing subtitle-related runs until
     * [selectSubtitleTrack].
     */
    @Volatile
    var subtitleTracks: List<SubtitleTrack> = emptyList()
        private set

    // Guards subtitleTracks writes: the decode thread publishes the
    // embedded set once, consumer threads append externals afterward.
    private val subtitleTracksLock = Any()
    private val externalSubtitleIds = AtomicInteger(0)

    @Volatile
    private var frameless = false

    // Whether any frame has carried closed captions, which is the only way to
    // find out that a file has them. Owned by the decode thread; the track it
    // publishes is read from everywhere, under the tracks lock.
    private var captionsSeen = false

    /**
     * The synthetic track closed captions are selected through.
     *
     * Synthetic because there is no stream behind it: the id is reserved
     * rather than allocated, so it cannot collide with a container's stream
     * index or with the negative ids external files take. Text, because
     * cc_dec emits ASS and renders through libass like every other text
     * track.
     */
    private val captionTrack = SubtitleTrack(
        id = SubtitleTrack.CLOSED_CAPTION_ID,
        streamIndex = -1,
        language = null,
        title = "Closed captions",
        codecName = "eia_608",
        isText = true,
        isDefault = false,
        isForced = false,
    )

    @Volatile
    private var buffer: TripleBuffer<FrameSlot>? = null
    private val queue = FrameQueue(readAheadFrames.coerceIn(1, 8))
    private val commands = LinkedBlockingQueue<Command>()

    // The intended playhead while a seek burst accumulates. SeekBy adds to
    // this rather than to the live clock, so presses faster than a landing
    // takes still sum to the final destination -- the clock stands at the
    // old anchor mid-landing, and reading it (or worse, resetting to -1 on
    // each landing) made bursts resolve to the wrong place. Outside a
    // burst relative seeks base on [lastPublishedPts] (the frame on
    // screen); frameless players, which never publish, keep their last
    // target here. Owned by the decode thread.
    private var intendedPositionNanos = 0L

    // True between issuing a seek and its landing; a SeekBy mid-burst
    // accumulates on [intendedPositionNanos] instead of the on-screen pts.
    private var seekInFlight = false
    private val stateBeforeSeek = java.util.concurrent.atomic.AtomicReference<State?>(null)
    private val audioPipeline: AudioPipeline? =
        if (audio) {
            AudioPipeline(
                path,
                sink ?: JavaSoundSink(),
                audioTrack,
                // Clamped here because this is the public edge; the pipeline
                // stores what it is handed. NaN leaves the default rather than
                // reaching a gain control, for the reason [setVolume] gives --
                // and there it means "keep what you had", which at the open is
                // the full volume below.
                initialVolume = if (volume.isNaN()) 1f else volume.coerceIn(0f, 1f),
                // Told at construction rather than paused by command. This
                // side starts before the decode thread and would otherwise
                // have played the start of the file before the pause could be
                // queued -- the decode thread is inside the video open at that
                // moment, released by the very clock this pipeline published.
                startPaused = startPaused,
            )
        } else {
            null
        }

    // Owned by the decode thread (selection runs there, where the clock
    // exists by construction); volatile because consumers poll
    // acquireSubtitles/activeSubtitleTrack from their render loops.
    @Volatile
    private var subtitlePipeline: SubtitlePipeline? = null
    // Written once on the decode thread, read from arbitrary consumer
    // threads through positionNanos(). Volatile for the publication: a
    // caller-supplied MediaClock gets no safe-publication guarantee
    // otherwise, and positionNanos() could go on reporting zero after
    // playback had started.
    @Volatile
    private lateinit var clock: MediaClock

    // Whether an audio clock ever took over: settled once, when the audio
    // side reports what the file and the machine can actually do.
    private var audioMastered = false

    // What killed the pacer, if anything did. See [runPacer].
    @Volatile
    private var pacerFailure: Throwable? = null

    // Which side supplies media time -- the device or the wall -- not who
    // may move it. Both sides re-anchor, each at a point where nothing is in
    // flight: the audio thread at its own landing, the decode thread at a
    // seek landing, a lap and the end of playback.
    //
    // Asked every time rather than settled once, because the audio side can
    // leave mid-file -- a device that dies, a track switch onto a rate the
    // machine refuses. A player that went on deferring to a thread that was
    // no longer there stopped re-anchoring on its own seeks: the landing
    // published against the old anchor, the picture stood on it, and the
    // position ran away on the wall clock while state reported Playing.
    private val ownsClock: Boolean
        get() = !audioMastered || audioPipeline?.alive != true

    // The pts on screen. Written by the pacer's publish; the decode
    // thread reads it for the resume re-anchor, the loop-wrap park, and
    // relative-seek bases.
    @Volatile
    private var lastPublishedPts = 0L

    /**
     * Whether the lap now running has produced a single frame.
     *
     * A looping player whose source yields nothing wraps, reads EOF at once,
     * wraps again -- and the wrap is not cheap: a source whose demuxer cannot
     * seek is reopened from disk on every turn. Measured on a source with no
     * frames at all: a full core, indefinitely, with the state reporting
     * Playing. A lap that produced nothing cannot be looped, so it ends
     * instead, which is what a file with no pictures deserves either way.
     */
    private var lapProducedFrames = false

    /**
     * Set while the pacer is between taking a frame off the queue and
     * writing the playhead it just published.
     *
     * Volatile: raised and lowered on the pacer, read by the decode thread's
     * step waits. Those wait for the screen to catch up and give up early
     * when there is nothing left to publish -- and an empty queue alone does
     * not mean that, because the frame in flight has already left it. A step
     * that gave up there read the previous playhead and computed the target
     * its predecessor had just used, so one press of a burst moved nothing.
     */
    @Volatile
    private var publishing = false

    // Wall time of the last publish; feeds the late-frame starvation
    // guard on both threads.
    @Volatile
    private var lastPublishWallNanos = 0L

    // The gap between the last two published pts -- a measured frame period,
    // not a guess, and the only thing that says how long the LAST frame of a
    // lap is meant to stay up. Zero until two frames have been published.
    @Volatile
    private var lastPublishGapNanos = 0L

    // Decode ran out of stream but the pacer may still hold the tail;
    // the EOF actions (loop wrap, park, Ended) wait for that drain.
    private var eofPending = false

    // The pts run a backstep's discovery pass decoded (keyframe toward
    // the shown frame, ascending). Which pts precedes which is a static
    // property of the file, so the memo never invalidates -- a repeated
    // backstep finds its target here and skips straight to the landing
    // run, halving the gesture. Owned by the decode thread.
    private var stepBackRun: LongArray? = null

    private val thread = Thread(::run, "skinema-decode").apply {
        isDaemon = true
        start()
    }

    /**
     * The freshest published frame, or null when nothing new arrived since
     * the previous call (keep showing what you have).
     */
    fun acquireFrame(): FrameSlot? {
        // The read IS the signal, so it is taken before the frame: a consumer
        // that polls and gets null is still watching, and one that stopped
        // polling is what this notices.
        lastAcquireNanos = System.nanoTime()
        unreadPublishes = 0
        if (!presenting && !presentingSaid) submit(Command.SetPresenting(true))
        return buffer?.acquire()
    }

    /**
     * Says whether the picture is being taken -- a window minimised, a tab
     * switched away from, a wallpaper behind a maximised app.
     *
     * A player nobody reads is not free: it decodes, converts and paces
     * pictures into a mailbox nothing empties. What stopping costs the
     * timeline is [WhenUnwatched]'s to say.
     *
     * Saying nothing is allowed. A mailbox that WAS being read and stops
     * being read is noticed on its own, and the next [acquireFrame] undoes
     * it -- so a consumer that never thinks about this still stops burning a
     * core behind a hidden window, and one that wants the transition exact
     * says so here.
     *
     * Saying it once takes the automatic notice out of play for good. The two
     * would otherwise argue: a player told to stop presenting, whose consumer
     * goes on polling the mailbox for a position readout, would be revived by
     * the polling against what it was told.
     */
    fun setPresenting(presenting: Boolean) {
        presentingSaid = true
        submit(Command.SetPresenting(presenting))
    }

    /** Freezes playback; the surface keeps showing the last frame. */
    fun pause() = submit(Command.Pause)

    /**
     * Continues from where [pause] froze, without a frame jump.
     *
     * Not the way back from a pause the player imposed on itself because
     * nobody was taking the picture ([WhenUnwatched.Freeze]) -- the next
     * [acquireFrame] lifts that one. Calling this instead takes the automatic
     * lift out of play and publishes [State.Playing], while nothing is decoded
     * until frames are being taken again: what stopped is the pictures, and
     * nothing here starts them for a mailbox no one is emptying.
     */
    fun resume() = submit(Command.Resume)

    /**
     * Jumps to [ptsNanos]; revives an [State.Ended] player.
     *
     * Exact seeks are frame-precise: the decoder runs forward from the
     * keyframe at-or-before the target, which on sparse-keyframe content
     * costs up to a keyframe interval of bare decode -- the keyframe
     * itself publishes immediately as a preview while the landing runs.
     * With [exact] false the seek LANDS on that keyframe instead: picture
     * and sound arrive at once, the position is only as precise as the
     * file's keyframe spacing. The right trade for skip buttons; keep
     * exact for timeline scrubbing.
     */
    fun seek(ptsNanos: Long, exact: Boolean = true) =
        submit(Command.Seek(ptsNanos.coerceAtLeast(0), exact))

    /**
     * Seeks [deltaNanos] relative to the intended playhead -- the right
     * primitive for +N/-N buttons. Rapid presses accumulate to one
     * destination regardless of how far behind the clock's anchor lags
     * during a landing. The delta resolves on the decode thread, against
     * its own playhead state -- resolving here would race the publish
     * loop's bookkeeping. [exact] as in [seek]; inexact presses
     * accumulate against the position actually landed.
     */
    fun seekBy(deltaNanos: Long, exact: Boolean = true) =
        submit(Command.SeekBy(deltaNanos, exact))

    /**
     * Advances exactly one frame and leaves the player paused on it --
     * the frame-inspection gesture. A playing player pauses first; at
     * the end of the stream the last frame stays. Time (sound included)
     * re-anchors to the stepped frame, so [resume] continues from it.
     */
    fun stepForward() = submit(Command.StepForward)

    /**
     * Steps back to the previous frame, paused on it. The previous
     * frame's timestamp is only knowable by decoding from the keyframe
     * toward the shown one (frame rates vary mid-stream), so this costs
     * a keyframe run like an exact seek -- instant on dense keyframes,
     * advertised through [State.Seeking] on sparse ones. Repeated
     * backsteps reuse the discovered run and pay one run, not two.
     */
    fun stepBackward() = submit(Command.StepBackward)

    /**
     * Linear 0..1 volume; no-op for silent playback.
     *
     * Clamped here rather than trusted, and NaN refused outright, because the
     * value does not stop at this library: [dev.hivens.skinema.audio.PcmSink]
     * is a documented seam and a consumer's implementation multiplies samples
     * by whatever arrives. The bundled sink clamps for itself, which is why
     * this went unseen -- the defect only appears through the seam.
     *
     * NaN is refused rather than clamped for the reason [setRate] gives:
     * coerceIn does not stop it, since every comparison with NaN is false. It
     * reaches a gain control that accepts it without complaint, and the line
     * then scales every sample by NaN -- silence, until some later call
     * happens to set a real number.
     */
    fun setVolume(volume: Float) {
        if (volume.isNaN()) return
        audioPipeline?.setVolume(volume.coerceIn(0f, 1f))
    }

    /** Playback speed; 1.0 until [setRate] changes it. */
    @Volatile
    var rate: Float = 1f
        private set

    /**
     * Playback speed, pitch preserved (atempo), clamped to [0.5, 4.0] --
     * the stretcher's quality envelope. With sound the change re-anchors
     * in place and costs the same ~line-buffer hold as a seek; silent
     * players scale their wall clock. Survives seeks, pauses and track
     * switches. A player on an explicit consumer clock owns its own time
     * and ignores this.
     */
    fun setRate(rate: Float) {
        // NaN is not a rate, and coerceIn does not stop it: every comparison
        // with NaN is false, so both bounds fall through and the clamp this
        // method documents returns NaN unchanged. It then reaches the tempo
        // and the clock, where it poisons the arithmetic -- measured as a
        // picture frozen on one frame with the position pinned and state
        // still reporting Playing, recoverable only by setting a real rate.
        if (rate.isNaN()) return
        submit(Command.SetRate(rate.coerceIn(0.5f, 4f)))
    }

    /**
     * Switches the sound to another of [audioTracks], in place: the
     * picture keeps playing, the sound re-anchors at the playhead. A
     * track that cannot open or that ends before the playhead is
     * refused and the current one plays on.
     */
    fun selectAudioTrack(streamIndex: Int) {
        audioPipeline?.selectTrack(streamIndex)
    }

    /**
     * Probes [file] (.srt, .ass -- anything libav reads as subtitles)
     * and appends its tracks to [subtitleTracks] under negative ids, on
     * the video's own timeline. Returns the new tracks; an unreadable
     * file returns an empty list and playback never notices. Frameless
     * players take none -- text rendering wants video geometry that
     * does not exist there.
     */
    fun addExternalSubtitles(file: Path): List<SubtitleTrack> {
        if (frameless) return emptyList()
        val probed = probeSubtitleFile(file) { externalSubtitleIds.decrementAndGet() }
        if (probed.isEmpty()) return emptyList()
        synchronized(subtitleTracksLock) { subtitleTracks = subtitleTracks + probed }
        return probed
    }

    /**
     * Turns subtitles on at one of [subtitleTracks] (null turns them
     * off). The track runs on its own thread against the master clock;
     * nothing subtitle-related exists until the first selection. A text
     * track is refused as a no-op when libass is not loadable; a track
     * that fails to open degrades to no subtitles -- playback never
     * notices either way.
     */
    fun selectSubtitleTrack(id: Int?) = submit(Command.SelectSubtitles(id))

    /** Id of the subtitle track on screen; null when off or failed. */
    val activeSubtitleTrack: Int?
        get() = subtitlePipeline?.takeIf { !it.isDead }?.track?.id

    /**
     * The freshest subtitle overlay, or null when nothing newer arrived
     * since the previous call -- [acquireFrame]'s contract for text.
     * Gate drawing on [activeSubtitleTrack]: after a deselect the last
     * acquired overlay is stale, not cleared.
     */
    fun acquireSubtitles(): SubtitleOverlay? = subtitlePipeline?.acquire()

    /**
     * Announces the size subtitles should rasterize at -- the video's
     * displayed rect, in pixels. VideoSurface posts it on every resize;
     * a consumer drawing frames itself should do the same, or accept
     * storage-resolution text scaled along with the pixels.
     *
     * Idempotent, so posting it on every frame is allowed: the same size twice
     * costs one comparison and queues nothing. It was not, and the difference
     * mattered -- the size used to be compared on the subtitle thread, after
     * that thread had been woken to read the command, so a caller posting from
     * its draw loop handed an unbounded queue sixty announcements a second and
     * the pump, which reads a non-empty queue as work pending, refilled a
     * packet at a time and never reached its own render cadence.
     */
    fun setSubtitleCanvasSize(width: Int, height: Int) {
        announcedWidth = width
        announcedHeight = height
        subtitlePipeline?.setCanvasSize(width, height)
    }

    /**
     * The last size a consumer announced, kept because the pipeline it is
     * meant for may not exist yet.
     *
     * A selection is marshalled onto the decode thread and builds the
     * pipeline there, so announcing a size right after asking for a track --
     * the natural order, and the one a consumer writes -- reached a null
     * field and was dropped without a word. The text then rasterized at the
     * video's storage size: 64x48 on the fixture that caught this, against
     * the 800x600 asked for. A surface that resizes later papers over it,
     * which is why it survived; a consumer drawing frames itself, or a
     * window that never resizes, does not get that second chance.
     *
     * Two fields rather than a pair, because [setSubtitleCanvasSize] may be
     * called on every frame and the allocation would be the next thing to
     * notice. Zero is "never announced", which is also the only size not
     * worth re-stating.
     */
    @Volatile
    private var announcedWidth = 0

    @Volatile
    private var announcedHeight = 0

    /**
     * Current media position in nanoseconds; zero until playback starts.
     *
     * Deliberately NOT clamped to [durationNanos]. It was, to stop a progress
     * bar ticking a few milliseconds past its own end between the last sample
     * and the decision to end -- but a container's declared duration is not a
     * number libav guarantees in either direction, and a file that understates
     * itself then pinned the position while the picture went on playing:
     * measured 500 ms reported against 2900 ms of frames actually shown. A
     * transient overshoot of tens of milliseconds is the smaller lie, and
     * tying a second reported value to an untrustworthy first one is how one
     * format's fix becomes another format's defect.
     */
    fun positionNanos(): Long = if (::clock.isInitialized) clock.mediaNanos() else 0L

    /**
     * Set the moment close is asked for, and read wherever the decode thread
     * would otherwise only notice a Close at the head of its queue. A seek's
     * decode-forward run peeks that head, and any command it does not act on
     * -- a pause, a rate change, a subtitle selection -- hid the Close behind
     * it: close() then waited out its join timeout and returned while the
     * thread carried on decoding, native session and all.
     */
    @Volatile
    private var closing = false

    /**
     * Queues a command, unless there is nobody left to take it.
     *
     * The queue is unbounded and the decode thread is its only consumer, so a
     * consumer holding a Failed or Closed player behind a live timeline added
     * one node per press, for as long as it kept pressing. Both pipelines
     * were given this guard when the same leak was found in them; the player
     * itself never got it. A thread dying immediately after the check costs
     * one node, which is the difference between a leak and a straggler.
     */
    private fun submit(command: Command) {
        if (closing || !thread.isAlive) return
        commands.put(command)
    }

    /**
     * The moment the whole teardown must be done by, published by [closeAsync]
     * so every join inside it spends the one budget the caller is counting.
     * Zero until a close is asked for.
     */
    @Volatile
    private var closeDeadlineNanos = 0L

    /**
     * Tears the player down without waiting for it: every side is told at
     * once and this returns.
     *
     * The door for a consumer that cannot block at all -- a dispose on a UI
     * thread. What it gives up against [close] is only the certainty that the
     * native memory has gone by the time it returns; the daemon threads free
     * it either way. What it keeps is the half a caller taking its own
     * resources back actually needs: no PCM enters the sink after this
     * returns, and a write already inside the sink is broken out of rather
     * than waited on. [state] settles [State.Closed] when the teardown
     * finishes.
     */
    fun closeAsync() {
        closing = true
        if (closeDeadlineNanos == 0L) closeDeadlineNanos = System.nanoTime() + CLOSE_BUDGET_NANOS
        commands.put(Command.Close)
        // Told from here rather than left to the decode thread, so their exits
        // overlap with its own -- and so a decode thread still inside an open
        // does not hold the announcement for the length of one.
        audioPipeline?.announceClose()
        subtitlePipeline?.announceClose()
    }

    override fun close() {
        closeAsync()
        joinWithin(thread, closeDeadlineNanos)
    }

    /**
     * The teardown's deadline: the one [closeAsync] published, or a fresh
     * budget when the teardown began somewhere else -- a pacer failure puts
     * its own Close in the queue, and nobody promised anything for that.
     */
    private fun teardownDeadline(): Long =
        closeDeadlineNanos.takeIf { it != 0L } ?: (System.nanoTime() + CLOSE_BUDGET_NANOS)

    /** Tells both sides to go, then waits for them inside [deadlineNanos]. */
    private fun tearDownSides(deadlineNanos: Long = teardownDeadline()) {
        audioPipeline?.announceClose()
        subtitlePipeline?.announceClose()
        runCatching { audioPipeline?.awaitExit(deadlineNanos) }
        runCatching { subtitlePipeline?.awaitExit(deadlineNanos) }
    }

    private fun joinWithin(thread: Thread?, deadlineNanos: Long) {
        val ms = (deadlineNanos - System.nanoTime()) / 1_000_000
        // join(0) waits forever, which is the opposite of what an exhausted
        // budget means.
        if (thread == null || ms <= 0) return
        thread.join(ms)
    }

    // -- Decode thread --------------------------------------------------------

    private fun run() {
        // The audio thread reports whether this file has sound; that
        // decides whose clock rules before any pacing starts.
        val audioClock = audioPipeline?.let { pipe ->
            try {
                pipe.clockFuture.get(5, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                // The device is taking too long to open. Abandon audio and
                // close the pipeline: letting it resolve its own clock later,
                // behind the wall clock adopted here, is the two-clock
                // split-brain the MediaClock seam exists to prevent, and it
                // would orphan the device. (#17)
                pipe.close()
                null
            } catch (_: Throwable) {
                null
            }
        }
        clock = explicitClock ?: audioClock ?: PlaybackClock()
        audioMastered = explicitClock == null && audioClock != null
        // Tracks publish only over a LIVE pipeline (the no-device path
        // enumerated them too, but nothing would serve a switch), and
        // before the video open so the frameless branch sees them.
        if (audioClock != null) {
            audioTracks = audioPipeline.tracks
        }

        val decoder = try {
            frameSourceFactory(path)
        } catch (t: Throwable) {
            // Frameless is the answer to a file with nothing to show, and to
            // nothing else. Every throw used to take this door when the file
            // had sound: an undecodable video codec, a truncated stream with
            // no dimensions, a hardware-decode request the machine could not
            // honour -- all played on as audio-only with the cause dropped on
            // the floor, while the constructor's own documentation promises
            // Failed for the last of those. A consumer's fallback never ran
            // because nothing ever told it to.
            // Frameless is for a file with nothing to SHOW, and whether the
            // sound then plays is a separate question. It used to be asked
            // here as one: with no output line the pipeline resolves a null
            // clock, and an audio-only file on a machine without a device
            // failed outright -- where the constructor's own documentation
            // promises it degrades to silent playback on the wall clock and
            // runs the usual lifecycle. Asking only for audio to have been
            // REQUESTED separates the two.
            if (audioPipeline != null && t is NoVideoStreamException) {
                // No video stream: frameless mode, with or without sound.
                frameless = true
                durationNanos = audioPipeline.durationNanos
                tags = audioPipeline.tags
                chapters = audioPipeline.chapters
                coverArt = audioPipeline.coverArt
                // Guarded like the framed path below is. A throw out of the
                // frameless loop left the pipeline open and playing behind a
                // decode thread that had died: close() then joined a thread
                // that was already gone and returned, with the sound still
                // running and the device still held.
                var framelessReturned = false
                try {
                    // Nobody else starts it on this path. With sound the
                    // pipeline's own clock is already running; without, this
                    // wall clock is the only thing that moves and it would
                    // otherwise sit at zero forever.
                    if (ownsClock) clock.start(0)
                    framelessLoop()
                    framelessReturned = true
                } catch (framelessFailure: Throwable) {
                    publishState(State.Failed(framelessFailure))
                }
                tearDownSides()
                // After the device, for the reason the framed path gives.
                if (framelessReturned && state !is State.Failed) publishState(State.Closed)
            } else {
                publishState(State.Failed(t))
                tearDownSides()
            }
            return
        }
        // close() can have come and gone while this was opening -- the wait
        // for the audio clock alone holds it for up to five seconds -- and
        // nothing here looked. So a player closed during the open announced
        // itself Playing and started a pacer AFTER close() had returned, and
        // the caller was told none of it.
        if (closing) {
            runCatching { decoder.close() }
            tearDownSides()
            publishState(State.Closed)
            return
        }
        // Everything below is inside the guard, including reading the file's
        // own metadata and starting the pacer. Outside it, a throw there --
        // or a thread that will not start -- leaked the decode session and
        // the audio pipeline for good and pinned the state at Opening, with
        // close() joining a thread that had already unwound.
        var pacer: Thread? = null
        var loopReturned = false
        try {
            durationNanos = decoder.durationNanos()
            tags = decoder.tags()
            chapters = decoder.chapters()
            coverArt = decoder.coverArt()
            rotationDegrees = decoder.rotationDegrees()
            hardwareActive = decoder.hardwareActive()
            synchronized(subtitleTracksLock) { subtitleTracks = subtitleTracks + decoder.subtitleTracks() }
            pacer = Thread(::runPacer, "skinema-pace").apply {
                isDaemon = true
                start()
            }
            if (ownsClock) clock.start(0)
            if (startPaused) enterStartPaused(decoder) else publishState(State.Playing)
            decodeLoop(decoder)
            loopReturned = true
        } catch (t: Throwable) {
            publishState(State.Failed(t))
        } finally {
            val deadline = teardownDeadline()
            queue.close()
            // Announced before anything is joined, and that ordering is the
            // whole of it: the three sides do not depend on one another, so
            // told one at a time and joined in turn their waits summed, where
            // told together they overlap and the cost is the slowest side.
            audioPipeline?.announceClose()
            subtitlePipeline?.announceClose()
            joinWithin(pacer, deadline)
            runCatching { decoder.close() }
            runCatching { audioPipeline?.awaitExit(deadline) }
            runCatching { subtitlePipeline?.awaitExit(deadline) }
            // Published after the teardown rather than before it. A consumer
            // polls this and reads Closed as leave to release what it lent
            // the player -- its own sink above all -- while the audio thread
            // is still writing into that sink until the close above returns.
            // A failure has already published its own state and keeps it.
            if (loopReturned && state !is State.Failed) publishState(State.Closed)
        }
    }

    /**
     * Opens onto the first frame and stays on it.
     *
     * Straight to [State.Paused], never through [State.Playing]: the pacer is
     * already running by the time this is reached, so the transient would be
     * observable, and a player asked to start paused reporting itself playing
     * is the kind of lie the state contract is built to refuse.
     *
     * The frame is committed FORCED, which is what a seek landing does -- the
     * pacer publishes forced frames whatever the state says, so the picture is
     * on screen while the player waits. Without it a paused start is a black
     * rectangle, and showing a poster frame is the reason to ask for one.
     *
     * A file that yields nothing here is at its end already; the EOF flag says
     * so, and the decode loop acts on it when something resumes.
     */
    private fun enterStartPaused(decoder: FrameSource) {
        // The pair pauseNow uses, in the same order and for the same reason:
        // the sound stops, then the clock it may be mastering stops with it.
        audioPipeline?.pause()
        clock.pause()
        publishState(State.Paused)
        val first = decoder.nextFrame(convert = false)
        if (first != null) noteCaptions(decoder, first.ptsNanos)
        if (first == null) {
            eofPending = true
            return
        }
        enqueue(decoder, first, forced = true)
        anchorPausedAt(first.ptsNanos)
    }

    /**
     * Enters the finished state and stops the timeline with it.
     *
     * Position kept climbing past the end of the file otherwise -- past its
     * duration, without limit -- because nothing told the clock the playback
     * was over: a device that is no longer fed reports a frozen frame count,
     * and one detached onto the wall clock reports the wall. A progress bar
     * ran past its own end and never stopped.
     */
    private fun enterEnded() {
        // Land on the duration rather than wherever the timeline happened to
        // stop. The last frame's own display time is part of the file, so the
        // clock stops a frame period short of the end and a progress bar never
        // reaches it; a track that outlasts the picture overshoots instead,
        // because the wall clock kept the last stretch. Both are the same
        // wrongness -- the end of the file is the duration.
        // Stopped first, then placed: the other order lets the timeline tick
        // between the two calls and lands a microsecond past the end.
        clock.pause()
        durationNanos?.let { clock.seek(it) }
        publishState(State.Ended)
    }

    /**
     * Whether this frameless lap is over.
     *
     * A live audio side says so itself, and that is the only mark worth
     * having while it can still play. A side that has gone -- died, or never
     * opened a device at all -- sets its ended flag together with the flag
     * that says it is gone, and nothing ever clears either: read as the mark,
     * that fired ten times a second forever, pinning the position near zero
     * under a state that still said Playing. With no sound coming, the wall
     * clock is the only thing moving and the file's own duration is where it
     * stops.
     */
    private fun framelessLapDone(): Boolean {
        val pipe = audioPipeline
        if (pipe != null && pipe.alive) return pipe.isEnded
        val end = durationNanos ?: return true
        return clock.mediaNanos() >= end
    }

    /**
     * Whether a frameless lap has anything to turn into.
     *
     * [framelessLapDone] answers true the moment it cannot tell: a side that
     * never opened a device sets its ended flag on the way out, and a
     * container that declares no duration leaves nothing to measure against.
     * Looping on that answer is a wrap ten times a second for as long as the
     * file stays open, with the position pinned at zero and the state still
     * reporting Playing -- reachable from something as ordinary as asking for
     * sound on a file that has neither pictures nor a decodable track.
     *
     * The framed path already refuses to turn a lap that produced nothing
     * ([lapProducedFrames]); this is that rule for a lap made of sound.
     */
    private fun framelessLapCanTurn(): Boolean =
        audioPipeline?.alive == true || durationNanos != null

    /** Audio-only playback: commands and lifecycle, no frames. */
    private fun framelessLoop() {
        if (startPaused) {
            // No picture to land on, so this is the whole of it: the sound
            // holds and the lap-done check below never runs, since it asks
            // for Playing.
            audioPipeline?.pause()
            clock.pause()
            publishState(State.Paused)
        } else {
            publishState(State.Playing)
        }
        while (true) {
            val cmd = commands.poll(100, TimeUnit.MILLISECONDS)
            if (cmd != null && !handle(cmd, decoder = null)) return
            if (state is State.Playing && framelessLapDone()) {
                val pipe = audioPipeline
                if (loop && framelessLapCanTurn()) {
                    // Audio-only, so there is no picture to end the lap: this
                    // side owns it. The landing handshake goes with the seek,
                    // without which the sink stays muted from here on -- and
                    // only for a side still able to answer one.
                    if (pipe != null && pipe.alive) {
                        pipe.seek(0)
                        pipe.videoLanded()
                    }
                    clock.seek(0)
                    clock.resume()
                } else {
                    enterEnded()
                }
            }
        }
    }

    /**
     * The fill side: keeps the queue stocked with converted frames while
     * the pacer presents them. One decode per pass, commands drained at
     * the top, so a command never waits behind more than a single frame.
     */
    private fun decodeLoop(decoder: FrameSource) {
        while (true) {
            var cmd = commands.poll()
            while (cmd != null) {
                if (!handle(cmd, decoder)) return
                cmd = commands.poll()
            }

            noteUnwatched()
            if (state !is State.Playing || !presenting) {
                // Paused, ended, or drawing for nobody: idle until the next
                // command. Under KeepTime the clock runs on through this, so
                // what is skipped is the decoding, not the timeline.
                //
                // The pacer holding its inventory would stop this side anyway
                // -- a queue nothing drains fills and the fill side parks on
                // it -- so what this gate is worth is the queue's depth in
                // frames, decoded once per transition and thrown away. It
                // stays because the fill side asking the question itself is
                // what keeps the two from drifting apart: a pacer that one day
                // drains for a reason of its own would otherwise start this
                // side decoding again for nobody.
                val idle = commands.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (!handle(idle, decoder)) return
                continue
            }

            if (eofPending) {
                if (!queue.isEmpty) {
                    // The pacer is still presenting the tail; stay on the
                    // command queue while it drains.
                    val c = commands.poll(20, TimeUnit.MILLISECONDS) ?: continue
                    if (!handle(c, decoder)) return
                    continue
                }
                // The file is over when BOTH streams are, whether the next
                // thing is a lap or the end: a short clip over a long track
                // still has sound to play, and a track that ran out first --
                // or a device in the middle of an outage -- has nothing left
                // to say about when the lap ends.
                if (audioPipeline?.hasSoundLeft == true) {
                    val c = commands.poll(20, TimeUnit.MILLISECONDS) ?: continue
                    if (!handle(c, decoder)) return
                    continue
                }
                eofPending = false
                if (loop && !lapProducedFrames) {
                    // Nothing came of this lap, so the next one has nothing to
                    // come of either -- and turning it costs a demuxer restart
                    // for a source that cannot seek. Spinning on that is what
                    // this used to do.
                    enterEnded()
                    continue
                }
                if (loop) {
                    when (awaitLapPlayedOut(decoder)) {
                        LapWait.CLOSE -> return
                        LapWait.LEFT_PLAYING -> {
                            // A pause stopped the lap where it stood; the
                            // decoder is still at the end of the stream, so
                            // the EOF stands and whatever resumes play finds
                            // it here.
                            eofPending = true
                            continue
                        }
                        LapWait.SUPERSEDED -> {
                            // A seek landed while we waited: it repositioned
                            // the decoder and voided the EOF on its way past.
                            // Restoring the EOF here put it back on a decoder
                            // that now sat wherever the user had asked, so the
                            // picture stood on the landing frame until the
                            // clock walked the rest of the lap out and wrapped
                            // -- the seek honoured by the clock and by the
                            // sound, and thrown away by the picture.
                            continue
                        }
                        LapWait.PLAYED_OUT -> {}
                    }
                    restartLap(decoder, resume = true)
                } else {
                    enterEnded()
                }
                continue
            }

            if (!queue.hasRoom) {
                // Inventory full; room appears as the pacer publishes.
                val c = commands.poll(20, TimeUnit.MILLISECONDS) ?: continue
                if (!handle(c, decoder)) return
                continue
            }

            val frame = decoder.nextFrame(convert = false)
            if (frame != null) noteCaptions(decoder, frame.ptsNanos)
            if (frame == null) {
                // Some sources (animated webp) cannot report a duration
                // until a full lap has been decoded; surface it once known.
                if (durationNanos == null) durationNanos = decoder.durationNanos()
                eofPending = true
                continue
            }

            val lateNanos = -clock.nanosUntilDue(frame.ptsNanos)
            if (lateNanos > CHASE_DROP_NANOS && !clockSettling()) {
                // Catch-up run (the clock jumped past this frame -- a loop
                // wrap, an audio re-anchor). Converting frames the policy
                // would drop costs several times their bare decode; one
                // converted guard per interval keeps the run reading as
                // motion. The guard ships forced so the pacer does not
                // re-judge -- deciding twice double-drops.
                if (!shouldPublishLateFrame(lateNanos, System.nanoTime() - lastPublishWallNanos)) continue
                enqueue(decoder, frame, forced = true)
                continue
            }
            enqueue(decoder, frame, forced = false)
        }
    }

    /** Converts the decoder's current frame into a queue cell. */
    private fun enqueue(decoder: FrameSource, raw: VideoDecoder.RgbaFrame, forced: Boolean) {
        lapProducedFrames = true
        // Whether the GPU took this stream is only knowable once a frame has
        // come back from it, and the open-time reading is the request. The
        // answer only ever goes from hardware to software (a hwaccel that
        // could not initialise), so one comparison per frame settles it.
        if (hardwareActive && !decoder.hardwareActive()) hardwareActive = false
        // The seek path commits a preview and a landing back to back; at
        // depth 1 the landing must wait out the pacer's pop of the
        // preview (forced frames pop within microseconds). Normal fill
        // checks hasRoom before decoding and never waits here.
        while (true) {
            val tick = queue.changeTick()
            if (queue.hasRoom) break
            if (queue.isClosed) return
            queue.awaitChange(tick, PACE_RECHECK_NANOS)
        }
        val cell = queue.writeCell()
        val bytes = raw.width * raw.height * 4
        if (cell.rgba.size != bytes) cell.rgba = ByteArray(bytes)
        val converted = decoder.convertLast(cell.rgba)
        // On a size mismatch convertLast falls back to its internal reused
        // buffer, which must never be enqueued -- the next decode would
        // overwrite it. The pre-sizing above makes this branch dead in
        // practice; the copy keeps it correct if a source ever disagrees.
        if (converted.rgba !== cell.rgba) cell.rgba = converted.rgba.copyOf()
        cell.width = converted.width
        cell.height = converted.height
        cell.ptsNanos = converted.ptsNanos
        queue.commit(forced)
    }

    /**
     * The audio thread anchors the clock from its own seek handling, and
     * it reads commands only between blocking writes -- after a seek
     * burst it can owe dozens of anchors while the clock still reads a
     * pre-seek position. Lateness computed against that reading is
     * fiction: a backward burst looks like a multi-second forward chase
     * and burns the decoder past the real position (the picture then
     * stands until the clock walks there). While seeks are owed, the
     * fill must not chase and the pacer must not drop.
     *
     * A dead pipeline owes nothing, whatever its counter says: it zeroes
     * that counter on its way out and can be handed a seek afterwards that
     * nobody will ever perform. [ownsClock] carries that case.
     */
    private fun clockSettling(): Boolean =
        !ownsClock && (audioPipeline?.pendingSeeks?.get() ?: 0) > 0

    private fun handle(cmd: Command, decoder: FrameSource?): Boolean = when (cmd) {
        Command.Close -> false
        Command.RoomFreed -> true
        Command.Pause -> {
            // A pause the consumer asked for is theirs from here: it must
            // outlive the picture being wanted again.
            pausedByUnwatch = false
            pauseNow()
            true
        }
        Command.StepForward -> {
            performStepForward(decoder)
            true
        }
        Command.StepBackward -> performStepBackward(decoder)
        Command.Resume -> {
            resumeNow(decoder)
            true
        }
        is Command.SetPresenting -> applyPresenting(cmd.presenting, decoder)
        is Command.SetRate -> {
            rate = cmd.rate
            if (ownsClock) {
                (clock as? PlaybackClock)?.setRate(cmd.rate.toDouble())
            } else {
                audioPipeline?.setTempo(cmd.rate.toDouble())
            }
            true
        }
        is Command.SelectSubtitles -> {
            applySubtitleSelection(cmd.id, decoder)
            true
        }
        is Command.Seek -> handleSeek(cmd.ptsNanos, cmd.exact, decoder)
        is Command.SeekBy -> {
            // Mid-burst the accumulated target wins (the clock stands at
            // the old anchor and must not be consulted). Outside a burst
            // the playhead is the frame on screen OR the just-landed
            // target, whichever is further -- a landing publishes
            // asynchronously (and a preview publishes the keyframe first),
            // so lastPublishedPts alone can briefly lag the intent.
            // Frameless players never publish; their intent carries alone.
            val base = if (seekInFlight) {
                intendedPositionNanos
            } else {
                maxOf(intendedPositionNanos, lastPublishedPts)
            }
            handleSeek((base + cmd.deltaNanos).coerceAtLeast(0), cmd.exact, decoder)
        }
    }

    // Whether anyone is taking the picture. Written on the decode thread,
    // read by the pacer, so both sides stop together.
    @Volatile
    private var presenting = true

    // Whether the consumer has ever said, in so many words, whether it is
    // watching. Once it has, the automatic notice steps aside for good: a
    // player told to stop presenting must not be revived by the very polling
    // its consumer does for some other reason, and one told to present must
    // not be stood down behind that instruction's back.
    @Volatile
    private var presentingSaid = false

    // When the mailbox was last read, and zero while it never has been. A
    // player nobody has EVER read frames from is not one that stopped being
    // watched -- it may be feeding something that is not a screen -- so the
    // automatic notice only ever applies after the first read.
    @Volatile
    private var lastAcquireNanos = 0L

    // Frames published into the mailbox since it was last read.
    //
    // Silence on its own is not the signal, and reading it as one was wrong in
    // the ordinary direction: a player legitimately producing nothing --
    // waiting a lap out, standing at the end -- would be called unwatched for
    // a consumer that merely had nothing to collect. What says nobody is
    // looking is pictures made and not taken. Written by the pacer, cleared by
    // whoever reads; a lost increment only delays the notice by a frame.
    @Volatile
    private var unreadPublishes = 0

    // Whether the pause standing now was this player's own doing. Only one it
    // imposed may be lifted when the picture is wanted again; a pause the
    // consumer asked for outlives being looked at.
    private var pausedByUnwatch = false

    // Where the last landing put the picture, -1 when it landed on nothing
    // (a seek past the end of the footage). Owned by the decode thread.
    private var landedPts = -1L

    private fun handleSeek(targetNanos: Long, exact: Boolean, decoder: FrameSource?, preview: Boolean = true): Boolean {
        // Reaching the end stops the clock; a seek is what revives the player
        // from there, so it starts the clock again. Without this the landing
        // publishes its frame, the state says Playing, and nothing moves ever
        // again -- a stopped clock makes no frame due. A step backward pauses
        // again on its own after landing, so this does not fight it.
        if (state is State.Ended) clock.resume()
        seekInFlight = true
        intendedPositionNanos = targetNanos
        audioPipeline?.seek(targetNanos)
        subtitlePipeline?.seek(targetNanos)
        landedPts = -1L
        val keepRunning = if (decoder != null) {
            performSeek(decoder, targetNanos, exact, preview)
        } else {
            // Frameless (audio-only): no landing to wait for.
            seekInFlight = false
            if (state is State.Ended) publishState(State.Playing)
            true
        }
        // Sound stays frozen at the anchor until the landing is done;
        // released here even when the landing ended the stream.
        audioPipeline?.videoLanded(landedPts)
        return keepRunning
    }

    private enum class LapWait { PLAYED_OUT, SUPERSEDED, LEFT_PLAYING, CLOSE }

    /**
     * When this lap's own time is up: the moment the last frame published
     * stops being shown, never later than the file's declared duration.
     *
     * The duration alone was the answer, and it is the wrong one whenever the
     * file's sound outlives its picture. By the time this runs the sound is
     * finished either way -- the EOF path holds the lap open for it first --
     * so what is left to wait out is the picture's own tail. Measured on two
     * seconds of picture under six of sound, played silently: the lap took
     * 6002 ms and the last frame stood on screen for 4110 of them, every lap.
     *
     * The bound stays because the lap must not close on a file that decodes
     * faster than it plays: a single frame, a truncated stream, a still. A
     * gap is only known once two frames have been published, and until then
     * the duration is all there is.
     */
    private fun lapEndNanos(): Long? {
        val declared = durationNanos ?: return null
        val gap = lastPublishGapNanos
        if (gap <= 0) return declared
        return minOf(declared, lastPublishedPts + gap)
    }

    /**
     * Holds the wrap until the lap has actually been watched.
     *
     * An empty queue means the last frame was PUBLISHED, not that its display
     * time has passed. A file short enough to decode faster than it plays --
     * a single frame, a truncated stream, a still handed to the player -- ran
     * its whole lap between two clock readings, so the decode thread looped on
     * itself at full speed and burned a core on content nothing was watching.
     * The lap is over when the file's own time is up.
     */
    private fun awaitLapPlayedOut(decoder: FrameSource): LapWait {
        val end = lapEndNanos() ?: return LapWait.PLAYED_OUT
        while (state is State.Playing && clock.mediaNanos() < end) {
            val cmd = commands.poll(20, TimeUnit.MILLISECONDS) ?: continue
            val seeked = cmd is Command.Seek || cmd is Command.SeekBy
            if (!handle(cmd, decoder)) return LapWait.CLOSE
            // A seek that landed while we waited has already put the decoder
            // and the clock where the user asked. Wrapping on top of it would
            // throw that away and restart the lap from zero, so the wrap is
            // off: the wrap has not repositioned anything yet, so there is
            // nothing to preserve by going through with it.
            if (seeked) return LapWait.SUPERSEDED
            // A pause (or a step, which pauses) stops the lap where it is.
            // Wrapping on top of that rewound the decoder to zero, restarted
            // the sound and took the clock off pause -- a paused player whose
            // position walked on and whose picture no longer matched it.
            if (state !is State.Playing) return LapWait.LEFT_PLAYING
        }
        return if (state is State.Playing) LapWait.PLAYED_OUT else LapWait.LEFT_PLAYING
    }

    /**
     * Seek landing. The demuxer lands on the keyframe at-or-before the
     * target; an exact seek then decodes (and drops) forward until the
     * target is reached, an inexact one takes the keyframe as the
     * destination. Either way the first frame out publishes immediately:
     * for inexact it IS the landing, for exact it is a preview the run
     * then refines -- the screen answers the press in milliseconds while
     * a sparse-keyframe run costs its seconds in the background.
     *
     * The decode-forward run can span seconds of footage, so newer seeks
     * queued meanwhile supersede the landing in progress -- rapid presses
     * cost one landing at the final target, not a landing each. Returns
     * false when a Close arrived mid-landing.
     */
    private fun performSeek(decoder: FrameSource, targetNanos: Long, exact: Boolean, preview: Boolean = true): Boolean {
        // Whatever inventory was decoded toward the old position is stale;
        // the landing must be the next thing on screen. Repositioning the
        // decoder also voids a pending EOF.
        queue.clear()
        eofPending = false
        // Remember what to return to (Playing/Paused/Ended) and advertise
        // the landing so a consumer can show a loading affordance.
        stateBeforeSeek.compareAndSet(null, state.takeIf { it != State.Seeking } ?: State.Playing)
        publishState(State.Seeking)
        var target = targetNanos
        var exactMode = exact
        var previewing = preview
        decoder.seekTo(target)
        val debugStart = if (DEBUG_SEEK) System.nanoTime() else 0L
        var dropped = 0
        var atKeyframe = true
        var previewedPts = Long.MIN_VALUE
        var landedFromKeyframe = Long.MIN_VALUE
        while (true) {
            // Room tokens carry no payload and must not hide a queued
            // seek behind them.
            if (closing) return false
            while (commands.peek() == Command.RoomFreed) commands.poll()
            val next = commands.peek()
            val superseded = when (next) {
                is Command.Seek -> next.ptsNanos
                is Command.SeekBy -> (intendedPositionNanos + next.deltaNanos).coerceAtLeast(0)
                Command.Close -> return false
                else -> null
            }
            if (superseded != null) {
                commands.poll()
                target = superseded
                exactMode = when (next) {
                    is Command.Seek -> next.exact
                    is Command.SeekBy -> next.exact
                    else -> exactMode
                }
                intendedPositionNanos = target
                audioPipeline?.seek(target)
                subtitlePipeline?.seek(target)
                decoder.seekTo(target)
                dropped = 0
                atKeyframe = true
                // The superseding press is a user seek; it gets its preview
                // even when the step landing it replaced suppressed one.
                previewing = true
                landedFromKeyframe = Long.MIN_VALUE
                continue
            }
            // Bare decode while dropping: converting frames that are thrown
            // away costs several times the decode itself.
            val f = decoder.nextFrame(convert = false)
            if (f != null) noteCaptions(decoder, f.ptsNanos)
            if (DEBUG_SEEK && f != null) {
                if (landedFromKeyframe == Long.MIN_VALUE) landedFromKeyframe = f.ptsNanos
                dropped++
            }
            if (f == null) {
                // The end of the VIDEO stream, which is not the end of the
                // file: a clip laid over a longer track still has sound to
                // play. Ending here dropped the rest of that track and
                // snapped the picture back to the first frame, from an
                // ordinary drag of the timeline into the audio-only tail.
                // Hand it to the EOF path -- the one place that knows a file
                // is over when BOTH streams are.
                //
                // A looping player used to take a shortcut here and wrap the
                // picture on the spot, which is the same mistake read the
                // other way round: the sound was never told, so it played on
                // from the target while frames arrived at pts zero and the
                // chase threw every one of them away. Measured on a 2s
                // picture under a 6s track: the screen stood still for two
                // seconds, until the sound reached its own end. mpv, ffplay,
                // VLC and Media3 all agree on the rule this now follows --
                // the last picture stays up, the sound plays out, and the lap
                // turns when the FILE ends.
                // With nothing left anywhere, the timeline still has to move to
                // where the press asked. It used to be left where the press came
                // FROM: neither side sets it in this case -- the video branch
                // does not touch the clock, the audio side's crop returns
                // nothing and it detaches to wall time without one, and
                // finishLanding only lands a pts of zero or more. The EOF path
                // below then waits out awaitLapPlayedOut against that stale
                // reading, at wall speed.
                //
                // Measured on a looping 6 s file seeked past its end from 0.7 s:
                // the picture stood still for 5.3 s with the position crawling
                // up to 5989 ms and the state reporting Playing the whole way.
                // On an hour-long background that is most of an hour.
                //
                // This changes none of the rules above -- the picture stays up,
                // and where there is sound left the audio side still owns the
                // clock and is left alone. It only stops the lap being replayed
                // in real time before it is allowed to turn.
                if (audioPipeline?.hasSoundLeft != true) {
                    durationNanos?.let { clock.seek(minOf(targetNanos, it)) }
                }
                finishSeek(State.Playing)
                eofPending = true
                // Only the Playing arm of the decode loop ever consumes that
                // flag, and finishSeek restores whatever ran before the burst
                // -- so on a PAUSED player the seek went nowhere at all: the
                // queue was cleared, no landing replaced it, and the position
                // still read where the press had left from. With nothing left
                // to play, settle it here instead.
                if (state !is State.Playing && audioPipeline?.hasSoundLeft != true) {
                    eofPending = false
                    if (loop) {
                        restartLap(decoder, resume = false)
                        // The lap turned and nothing is going to fill it: only
                        // the Playing arm of the decode loop decodes, and this
                        // player is paused. So the first frame of the new lap
                        // is landed here, the way a paused start lands its
                        // poster -- forced, so the pacer publishes it whatever
                        // the state says. Without it the wrap moved the
                        // timeline to zero and left the picture on whatever the
                        // press had jumped from, and the two disagreed until
                        // something resumed.
                        val first = decoder.nextFrame(convert = false)
                        if (first == null) {
                            eofPending = true
                        } else {
                            noteCaptions(decoder, first.ptsNanos)
                            enqueue(decoder, first, forced = true)
                            landedPts = first.ptsNanos
                            anchorPausedAt(first.ptsNanos)
                        }
                    } else {
                        enterEnded()
                    }
                }
                return true
            }
            if (!exactMode) {
                // The keyframe is the destination: re-anchor everything to
                // where the stream actually starts, sound included --
                // leaving the audio at the requested target would play it
                // up to a keyframe interval ahead of the picture.
                if (ownsClock) clock.seek(f.ptsNanos)
                landedPts = f.ptsNanos
                intendedPositionNanos = f.ptsNanos
                audioPipeline?.seek(f.ptsNanos)
                subtitlePipeline?.seek(f.ptsNanos)
                enqueue(decoder, f, forced = true)
                if (DEBUG_SEEK) {
                    val ms = (System.nanoTime() - debugStart) / 1_000_000
                    System.err.println(
                        "[seek] target=${target / 1_000_000}ms landed=${f.ptsNanos / 1_000_000}ms (keyframe) in ${ms}ms",
                    )
                }
                finishSeek(State.Playing)
                return true
            }
            if (f.ptsNanos >= target) {
                if (ownsClock) clock.seek(f.ptsNanos)
                landedPts = f.ptsNanos
                // Forced: the pacer publishes the landing immediately, even
                // while the player resolves back to Paused.
                enqueue(decoder, f, forced = true)
                if (DEBUG_SEEK) {
                    val ms = (System.nanoTime() - debugStart) / 1_000_000
                    val kfGapMs = (target - landedFromKeyframe) / 1_000_000
                    System.err.println(
                        "[seek] target=${target / 1_000_000}ms keyframeGap=${kfGapMs}ms decoded=$dropped frames in ${ms}ms",
                    )
                }
                finishSeek(State.Playing)
                return true
            }
            if (atKeyframe) {
                // Preview: show the keyframe while the run decodes toward
                // the exact target. A superseding burst within one keyframe
                // interval re-sees the same frame -- skip the re-convert.
                atKeyframe = false
                if (previewing && f.ptsNanos != previewedPts) {
                    previewedPts = f.ptsNanos
                    enqueue(decoder, f, forced = true)
                }
            }
        }
    }

    private fun resumeNow(decoder: FrameSource?) {
        if (state !is State.Paused) return
        // The sink's buffered tail keeps sounding (and advancing the device
        // clock) for a beat after a pause lands; resuming re-anchors sound to
        // the frame actually on screen, sample-precise. Frameless players have
        // no frame to anchor to and just resume.
        if (audioPipeline != null && decoder != null) {
            val at = clock.mediaNanos()
            audioPipeline.seek(at)
            audioPipeline.videoLanded(at)
        }
        audioPipeline?.resume()
        clock.resume()
        publishState(State.Playing)
        pausedByUnwatch = false
    }

    /**
     * Takes the player in or out of being watched. Returns false when a Close
     * arrived inside the landing the return can run -- the same contract every
     * other handler keeps, and dropping it would swallow the Close.
     */
    private fun applyPresenting(now: Boolean, decoder: FrameSource?): Boolean {
        if (now == presenting) return true
        presenting = now
        if (!now) {
            // What stopping costs the timeline is the policy's to say; that
            // the pictures stop is not a policy, it is the point.
            if (unwatched == WhenUnwatched.Freeze && state is State.Playing) {
                pausedByUnwatch = true
                pauseNow()
            }
            return true
        }
        unreadPublishes = 0
        if (pausedByUnwatch) {
            resumeNow(decoder)
            return true
        }
        // KeepTime, coming back: the file ran on without the viewer while the
        // decoder stood where it was left, so the gap between them is exactly
        // what nobody watched. Rejoin the clock on a keyframe -- decoding the
        // gap to catch up would spend on pictures that are already too late to
        // show, which is the cost this whole mechanism exists to stop.
        if (state is State.Playing && decoder != null) {
            return handleSeek(clock.mediaNanos(), exact = false, decoder)
        }
        return true
    }

    /**
     * Notices a mailbox that stopped being read.
     *
     * The consumer that says nothing is the ordinary one: a Compose surface
     * polls every frame while its window is on screen and simply stops when it
     * is not, and nothing in that tells the player.
     *
     * Three things have to hold, and each is a way of being wrong that was
     * tried. The mailbox must have been read at least once, or a player
     * feeding something that is not a screen would be stood down. Enough
     * pictures must have been PUBLISHED into it and not taken -- silence on
     * its own says nothing, because a player waiting a lap out or standing at
     * its end produces nothing to take, and a handful says little more,
     * because a slow file hands over a handful while an ordinary consumer
     * merely polls its position. And the silence has to have lasted, because
     * a burst of sixty frames is a chase, not a consumer leaving.
     */
    private fun noteUnwatched() {
        if (presentingSaid || !presenting || frameless) return
        val last = lastAcquireNanos
        if (last == 0L) return
        if (unreadPublishes < UNREAD_PUBLISHES_BEFORE_UNWATCHED) return
        if (System.nanoTime() - last < UNWATCHED_AFTER_NANOS) return
        applyPresenting(false, null)
    }

    private fun pauseNow() {
        if (state is State.Playing) {
            audioPipeline?.pause()
            clock.pause()
            publishState(State.Paused)
        }
    }

    /**
     * Selection always spawns a fresh pipeline and lets the old one die
     * asynchronously (a joined close behind a blocking read would hitch
     * a frame). Subtitles own no device, so replacement IS the switch:
     * the newcomer publishes within a tick. Runs on the decode thread,
     * after [clock] resolved -- a selection queued before Playing just
     * waits its turn.
     */
    private fun applySubtitleSelection(id: Int?, decoder: FrameSource?) {
        val current = subtitlePipeline
        if (id == null) {
            current?.announceClose()
            subtitlePipeline = null
            return
        }
        if (current != null && !current.isDead && current.track.id == id) return
        val track = subtitleTracks.firstOrNull { it.id == id } ?: return
        // No libass, no text rendering: refuse like the audio switch
        // refuses an unopenable track. Bitmap tracks never need it.
        if (track.isText && !Ass.available) return
        current?.announceClose()
        val fresh = SubtitlePipeline(
            path = track.externalPath ?: path,
            clock = clock,
            track = track,
            storageSize = decoder?.videoSize(),
        )
        // Opening a track sets the canvas from the video's storage size, so
        // a size the consumer announced earlier has to be re-stated here --
        // for the first pipeline, which did not exist when it was announced,
        // and for every switch after, which would otherwise drop back to
        // storage resolution mid-playback.
        //
        // Published BEFORE the announcement is read, which is the order that
        // closes the gap rather than moves it. The consumer writes the size
        // and then looks for a pipeline to hand it to; reading first meant
        // both sides could look past each other -- this one takes the old
        // value, that one finds no pipeline yet, and the new size is lost
        // until something resizes the window. Publishing first leaves the
        // consumer somewhere to put it, and re-stating a value that already
        // arrived costs nothing.
        subtitlePipeline = fresh
        if (announcedWidth > 0 && announcedHeight > 0) fresh.setCanvasSize(announcedWidth, announcedHeight)
    }

    /**
     * Takes the closed captions off the frame just decoded, if it carried any.
     *
     * Called at every site that decodes, playback and seek landings alike,
     * because cc_dec assembles a row out of a byte pair per frame: a landing
     * that dropped its captions would arrive with a half-built line and no way
     * to finish it. A reposition flushes that state on the other side.
     *
     * The FIRST payload is also what makes the track exist. There is no way to
     * know a file has captions without decoding one -- they are SEI, not a
     * stream -- so nothing is advertised until the bytes are in hand, and then
     * the track appears the way an animated webp's duration does.
     */
    private fun noteCaptions(decoder: FrameSource, ptsNanos: Long) {
        val bytes = decoder.captionBytes() ?: return
        if (!captionsSeen) {
            captionsSeen = true
            synchronized(subtitleTracksLock) { subtitleTracks = subtitleTracks + captionTrack }
        }
        subtitlePipeline?.takeIf { it.track.id == SubtitleTrack.CLOSED_CAPTION_ID }?.submitCaptions(bytes, ptsNanos)
    }

    /** Re-anchors time at a stepped frame while the player stays paused. */
    private fun anchorPausedAt(pts: Long) {
        intendedPositionNanos = pts
        if (ownsClock) {
            clock.seek(pts)
        } else {
            audioPipeline?.seek(pts)
            audioPipeline?.videoLanded(pts)
        }
        subtitlePipeline?.seek(pts)
    }

    private fun performStepForward(decoder: FrameSource?) {
        if (decoder == null) return
        pauseNow()
        if (state !is State.Paused) return
        val forcedPts = queue.forceHead()
        if (forcedPts == null) {
            // Nothing in inventory: decode exactly one. EOF keeps the
            // last frame on screen.
            val f = decoder.nextFrame(convert = false) ?: return
            noteCaptions(decoder, f.ptsNanos)
            enqueue(decoder, f, forced = true)
            anchorPausedAt(f.ptsNanos)
            return
        }
        // Wait out the pacer's pop: a rapid second step must not re-mark
        // the same head and lose a press.
        while (!queue.isClosed) {
            val tick = queue.changeTick()
            val head = queue.peekHead()
            if (head == null || !head.forced || head.ptsNanos != forcedPts) break
            queue.awaitChange(tick, PACE_RECHECK_NANOS)
        }
        anchorPausedAt(forcedPts)
    }

    private fun performStepBackward(decoder: FrameSource?): Boolean {
        if (decoder == null) return true
        pauseNow()
        val shown = lastPublishedPts
        if (shown <= 0) return true
        val cached = stepBackRun
        val cachedIdx = cached?.indexOf(shown) ?: -1
        val target: Long
        if (cachedIdx > 0) {
            target = cached!![cachedIdx - 1]
        } else {
            // Discovery pass: the predecessor's pts -- only knowable by
            // decoding from the keyframe toward the shown frame. Index 0
            // means the shown frame heads its run; the predecessor lives
            // behind the previous keyframe, rediscovered below.
            val run = mutableListOf<Long>()
            decoder.seekTo(shown)
            var f = decoder.nextFrame(convert = false)
            if (f != null && f.ptsNanos >= shown) {
                // The shown frame IS a keyframe, so the seek landed on it and
                // there is nothing before it in this run. Ask for the keyframe
                // strictly before instead -- in the source's own units. A
                // nanosecond earlier used to be the request, and every
                // container rounds that straight back onto the same frame, so
                // the run came out empty, the memo went unwritten and the step
                // republished the frame already on screen. Not just after a
                // scrub: a backstep could never cross a keyframe at all.
                decoder.seekBefore(shown)
                f = decoder.nextFrame(convert = false)
            }
            while (f != null && f.ptsNanos < shown) {
                run += f.ptsNanos
                f = decoder.nextFrame(convert = false)
            }
            if (run.isNotEmpty()) stepBackRun = run.toLongArray()
            // No predecessor (the shown frame is the first): the pass
            // still moved the demuxer, so land back on the shown frame.
            target = run.lastOrNull() ?: shown
        }
        // From Ended there is no prior state to restore and finishSeek
        // would resolve to Playing; a step always lands paused. The
        // landing skips the keyframe preview: for a one-frame step the
        // preview is a backward picture jump, not feedback.
        val fromEnded = state is State.Ended
        if (fromEnded) stateBeforeSeek.set(State.Paused)
        val keepRunning = handleSeek(target, exact = true, decoder, preview = false)
        when {
            state is State.Playing -> pauseNow()
            fromEnded && state is State.Paused -> {
                // The landing revived the audio thread; freeze it back.
                audioPipeline?.pause()
                clock.pause()
            }
        }
        // Wait out the pacer's publish, the way a forward step does. The
        // playhead a step measures from is [lastPublishedPts] -- the screen,
        // written by the pacer -- while handleSeek returns as soon as the
        // landing is QUEUED. A second press arriving in that window read the
        // pre-step value and computed the same target again, so a burst of
        // presses moved the picture one frame instead of one per press.
        // Bounded: a landing that was superseded or dropped must not park the
        // decode thread.
        val landed = landedPts
        if (landed >= 0) {
            val deadline = System.nanoTime() + STEP_PUBLISH_WAIT_NANOS
            while (!queue.isClosed && System.nanoTime() < deadline) {
                val tick = queue.changeTick()
                if (lastPublishedPts == landed || (queue.peekHead() == null && !publishing)) break
                queue.awaitChange(tick, PACE_RECHECK_NANOS)
            }
        }
        return keepRunning
    }

    /**
     * Puts every side back at the start of the file.
     *
     * The picture owns the lap, so it restarts both others -- including the
     * landing handshake, without which the sound stays muted from the second
     * lap on. Only a pipeline still on its feet: a seek into a dead one
     * raises a landing counter nobody will lower, and the video side then
     * treats every frame as still settling.
     */
    private fun restartLap(decoder: FrameSource, resume: Boolean) {
        lapProducedFrames = false
        decoder.seekTo(0)
        audioPipeline?.takeIf { it.alive }?.let {
            it.seek(0)
            it.videoLanded()
        }
        // The subtitle side used to learn about a lap only by noticing the
        // clock jump backward by more than a second, which a lap shorter than
        // that never does. Nothing goes visibly wrong today -- the demux
        // horizon is thirty seconds, so a short file is resident after one
        // lap and renders from what it already holds -- so this is here to
        // stop that being load-bearing, and its effect is deliberately not
        // asserted anywhere: there is nothing to assert while the resident
        // state covers it, and reaching in to count repositions would put a
        // seam on the player for a fact no consumer can see.
        subtitlePipeline?.seek(0)
        clock.seek(0)
        if (resume) clock.resume()
    }

    /**
     * Resolves [State.Seeking] back to the lifecycle.
     *
     * What a burst interrupted is what it returns to, so a paused player stays
     * paused on the frame it landed on. [landed] answers for one that was
     * playing, and outranks the restored pause when the landing ended the
     * stream -- there is nothing left there to be paused on.
     */
    private fun finishSeek(landed: State) {
        seekInFlight = false
        val prior = stateBeforeSeek.getAndSet(null)
        publishState(
            when {
                prior == State.Paused && landed != State.Ended -> State.Paused
                else -> landed
            },
        )
    }

    // -- Pacer thread ---------------------------------------------------------

    /**
     * The pacer's thread body, and the only thing standing between a throw in
     * [paceLoop] and a player that hangs.
     *
     * Every other thread here catches; this one did not, and it is not a
     * thread that cannot throw: it rebuilds the mailbox around every geometry
     * change (three full frames -- an allocation a 4K stream can lose), and
     * it reads the clock, which on a caller-supplied MediaClock or PcmSink is
     * the consumer's own code.
     *
     * What its death cost is the reason this exists. Nothing announced it:
     * the picture stopped, [state] went on reporting Playing, and the
     * position ran on under it -- so a consumer had no failure to fall back
     * from, and a player it then closed took the full join budget before
     * returning. Publishing the failure is what ends that, and it is what
     * keeps the decode thread out of trouble too: a Failed player takes none
     * of the presses that would have it wait for a free cell.
     *
     * Closing the queue is for the one thread that cannot be warned -- a
     * decode thread already inside that wait when the pacer dies. Its only
     * escape is a closed queue, and the queue is normally closed by that very
     * thread, in a teardown it is not going to reach.
     */
    private fun runPacer() {
        try {
            paceLoop()
        } catch (t: Throwable) {
            pacerFailure = t
            publishState(State.Failed(t))
            // The producer's waits ask only whether the queue is closed. It
            // is now, in the sense they are asking about: there is no
            // consumer left.
            queue.close()
            commands.put(Command.Close)
        }
    }

    /**
     * Owns presentation: waits out each queued frame's pts against the
     * clock and publishes it into the mailbox. Living on its own thread
     * is the point of the queue -- a stalled decode is no longer the
     * publisher, so whatever inventory exists keeps presenting through
     * the stall. Exits when the decode thread closes the queue.
     */
    private fun paceLoop() {
        var lastFlushes = queue.flushCount
        var lastClockReading = Long.MIN_VALUE
        var starveWarnedPts = Long.MIN_VALUE
        while (!queue.isClosed) {
            // The tick reads before the peek: a mutation in between makes
            // the next wait return immediately instead of sleeping stale.
            val tick = queue.changeTick()
            val head = queue.peekHead()
            if (head == null) {
                queue.awaitChange(tick, PACE_RECHECK_NANOS)
                continue
            }
            if (head.forced) {
                // Seek landings and chase guards: the decode side already
                // decided these publish, state gate and late policy aside.
                publishFromQueue()
                continue
            }
            if (state !is State.Playing || !presenting) {
                // Paused, a landing resolving, or nobody taking the picture:
                // hold the inventory. Forced frames publish above this, so a
                // seek still lands while unwatched and the picture is right
                // when it is wanted again.
                queue.awaitChange(tick, IDLE_RECHECK_NANOS)
                continue
            }

            val flushes = queue.flushCount
            if (flushes != lastFlushes) {
                lastFlushes = flushes
                lastClockReading = Long.MIN_VALUE
            }
            val clockNow = clock.mediaNanos()
            val regressed = lastClockReading != Long.MIN_VALUE &&
                clockNow < lastClockReading - CLOCK_NOISE_NANOS
            lastClockReading = clockNow
            if (regressed) {
                // The clock only jumps backward like this on a loop wrap (a
                // seek flushes the queue first, resetting the tracking
                // above): the queued tail belongs to the lap that just
                // ended -- show it now, not a lap later. The jump is judged
                // by direction, not magnitude: a sub-second lap wraps by
                // less than a second, so a fixed threshold stranded its
                // tail until the next lap reached those pts.
                while (true) {
                    val h = queue.peekHead() ?: break
                    if (h.forced || h.ptsNanos <= clockNow + CLOCK_NOISE_NANOS) break
                    publishFromQueue()
                }
                continue
            }

            val wait = head.ptsNanos - clockNow
            if (wait > 0) {
                // A frame standing more than a second ahead of the clock is
                // a frozen picture with running sound -- name both sides.
                if (DEBUG_SEEK && wait > 1_000_000_000L && starveWarnedPts != head.ptsNanos) {
                    starveWarnedPts = head.ptsNanos
                    System.err.println(
                        "[pace] frame=${head.ptsNanos / 1_000_000}ms waits ${wait / 1_000_000}ms for the clock (${clockNow / 1_000_000}ms)",
                    )
                }
                // Capped: the audio thread can re-anchor the clock at any
                // moment, and a sleep taken against a stale reading must
                // notice within one period. A queue mutation (a seek's
                // flush-and-landing) cuts the sleep short entirely.
                queue.awaitChange(tick, wait.coerceAtMost(PACE_RECHECK_NANOS))
                continue
            }

            if (-wait > CHASE_DROP_NANOS && clockSettling()) {
                // Deep lateness against a clock that still owes seek
                // anchors is fiction; hold the inventory until the audio
                // thread lands them.
                queue.awaitChange(tick, IDLE_RECHECK_NANOS)
                continue
            }
            if (!shouldPublishLateFrame(-wait, System.nanoTime() - lastPublishWallNanos)) {
                // Against the tick read before the peek: what gets dropped has
                // to be the frame that was judged, not whatever is at the head
                // by now. A seek lands in this window and puts its own frame
                // there.
                if (queue.dropHead(tick)) commands.put(Command.RoomFreed)
                continue
            }
            publishFromQueue()
        }
    }

    /**
     * Pops the queue head into the mailbox by swapping arrays with the
     * writing slot -- no pixel copy; the arrays cycle between the queue
     * and the mailbox. A geometry change rebuilds the mailbox around the
     * new size, as publish always has.
     */
    private fun publishFromQueue(): Boolean {
        val head = queue.peekHead() ?: return false
        val current = buffer
        val target = if (current == null || current.writing.rgba.size != head.byteCount) {
            TripleBuffer(
                FrameSlot(head.byteCount),
                FrameSlot(head.byteCount),
                FrameSlot(head.byteCount),
            )
        } else {
            current
        }
        val slot = target.writing
        // Raised BEFORE the head leaves the queue and lowered after the
        // playhead is written, because between those two the queue is empty
        // and [lastPublishedPts] still holds the previous frame -- a state
        // that reads exactly like "nothing left to publish" to anyone
        // waiting on it.
        publishing = true
        try {
            val frame = queue.poll(slot.rgba) ?: return false
            slot.rgba = frame.rgba
            slot.width = frame.width
            slot.height = frame.height
            slot.ptsNanos = frame.ptsNanos
            // Forward gaps only: a seek landing or a wrap publishes backwards,
            // and neither is a frame period.
            if (frame.ptsNanos > lastPublishedPts) lastPublishGapNanos = frame.ptsNanos - lastPublishedPts
            lastPublishedPts = frame.ptsNanos
            lastPublishWallNanos = System.nanoTime()
            unreadPublishes++
            target.publish()
        } finally {
            publishing = false
        }
        if (target !== current) buffer = target
        commands.put(Command.RoomFreed)
        return true
    }

    private companion object {
        /**
         * Longest uninterrupted pace sleep. The wait is computed from one
         * clock reading, and the audio thread re-anchors the clock from
         * its own seek handling -- which can run AFTER a fast video
         * landing, since it only reads its command queue between blocking
         * writes. A sleep taken against the stale reading is a frozen
         * picture over running sound for the whole seek distance; capping
         * it bounds that to one re-check period. Frame-rate waits exceed
         * the cap only on low-fps content, where an extra wakeup per
         * period is noise.
         */
        const val PACE_RECHECK_NANOS = 50_000_000L

        /** The pacer's pause-hold re-check cadence. */
        const val IDLE_RECHECK_NANOS = 20_000_000L

        /**
         * How long a step waits for its own landing to reach the screen
         * before carrying on regardless. Only a landing that was superseded
         * or dropped ever spends it; the ordinary case is a forced frame the
         * pacer publishes within microseconds.
         */
        const val STEP_PUBLISH_WAIT_NANOS = 500_000_000L

        /**
         * Slack absorbing a clock's own jitter when judging a backward
         * jump. A reading below the last by more than this -- with no seek
         * flush in between -- is a loop wrap: both clocks clamp position
         * noise to monotonic, and a seek empties the queue before the
         * pacer can act on its re-anchor, so nothing else moves time
         * backward under live inventory. Judging by direction past this
         * slack (rather than a one-second magnitude) is what lets a
         * sub-second lap's stranded tail present at the wrap.
         */
        const val CLOCK_NOISE_NANOS = 5_000_000L

        /**
         * How long [close] waits for the teardown -- every join inside it
         * spends this one budget rather than taking it each.
         *
         * A second, against the eleven the old per-join waits summed to. The
         * wait is no longer what keeps a caller's resources safe: the sink is
         * released the moment a close is announced, so what is left to wait
         * for is native memory the daemon threads free whether or not anyone
         * watches. Waiting longer buys only the certainty that it has already
         * happened, and a close that hangs an application's exit for seconds
         * costs more than that certainty is worth. A consumer that cannot
         * spend even this has [closeAsync].
         */
        const val CLOSE_BUDGET_NANOS = 1_000_000_000L

        /**
         * How long a mailbox that was being read may go unread before the
         * player treats itself as unwatched.
         *
         * Two seconds is a hundred and twenty frame periods at 60 fps and
         * still generous at any cadence a consumer plausibly draws at, which
         * is what it has to be: the cost of deciding too early is a picture
         * that stutters for a consumer whose loop is merely slow, and the cost
         * of deciding late is two seconds of decoding nobody sees.
         */
        const val UNWATCHED_AFTER_NANOS = 2_000_000_000L

        /**
         * Pictures published into an unread mailbox before nobody is taken to
         * be looking.
         *
         * Counted rather than timed, because what the waste is worth scales
         * with the frame rate and so should the patience: sixty frames is a
         * second of a 60 fps file and a minute of a one-frame-a-second one,
         * and the second one costs almost nothing to decode anyway. Timing it
         * instead made the answer depend on how fast the machine was -- a
         * consumer polling the position while a slow file played out looked
         * exactly like one that had gone away, and did so only on the slowest
         * runner in the matrix.
         *
         * The wall bound below still has to pass as well. It is the floor
         * under a burst: sixty frames can be published in a blink by a chase
         * or a landing run, and a blink is not a consumer leaving.
         */
        const val UNREAD_PUBLISHES_BEFORE_UNWATCHED = 60

        val DEBUG_SEEK = System.getenv("SKINEMA_DEBUG_SEEK") != null
    }
}

/**
 * Lateness past frame-rate jitter: a frame this overdue belongs to a
 * catch-up run, not to normal pacing slack.
 */
internal const val CHASE_DROP_NANOS = 250_000_000L

/**
 * The starvation guard's cadence: during a catch-up run one frame per
 * this interval still publishes, so the run reads as motion and an
 * overloaded machine degrades to a slideshow, not a freeze.
 */
internal const val CHASE_PUBLISH_INTERVAL_NANOS = 150_000_000L

/**
 * Whether a frame that missed its presentation time still converts and
 * publishes. Pure -- the policy half of the catch-up handling, applied
 * at decode time (convert or drop) and at pace time (publish or drop).
 */
internal fun shouldPublishLateFrame(lateNanos: Long, sincePublishNanos: Long): Boolean =
    lateNanos <= CHASE_DROP_NANOS || sincePublishNanos >= CHASE_PUBLISH_INTERVAL_NANOS
