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
    private val audio: Boolean,
    private val explicitClock: MediaClock?,
    sink: PcmSink?,
    readAheadFrames: Int,
    audioTrack: Int?,
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
    ) : this(path, loop, audio, explicitClock, sink, readAheadFrames, audioTrack, { FrameSources.open(it, hardware) })

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
        data object Close : Command

        /**
         * The pacer freed a queue cell. Without this token the fill side
         * discovers room only when its command poll times out, putting
         * dead time on every frame of full-queue (steady-state) playback
         * -- which caps production below the frame rate of 60 fps
         * content. Handled as a no-op; its arrival is the point.
         */
        data object RoomFreed : Command
    }

    @Volatile
    var state: State = State.Opening
        private set

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
        if (audio) AudioPipeline(path, sink ?: JavaSoundSink(), loop, audioTrack) else null

    // Owned by the decode thread (selection runs there, where the clock
    // exists by construction); volatile because consumers poll
    // acquireSubtitles/activeSubtitleTrack from their render loops.
    @Volatile
    private var subtitlePipeline: SubtitlePipeline? = null
    private lateinit var clock: MediaClock

    // Whether an audio clock ever took over: settled once, when the audio
    // side reports what the file and the machine can actually do.
    private var audioMastered = false

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
    fun acquireFrame(): FrameSlot? = buffer?.acquire()

    /** Freezes playback; the surface keeps showing the last frame. */
    fun pause() = commands.put(Command.Pause)

    /** Continues from where [pause] froze, without a frame jump. */
    fun resume() = commands.put(Command.Resume)

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
        commands.put(Command.Seek(ptsNanos.coerceAtLeast(0), exact))

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
        commands.put(Command.SeekBy(deltaNanos, exact))

    /**
     * Advances exactly one frame and leaves the player paused on it --
     * the frame-inspection gesture. A playing player pauses first; at
     * the end of the stream the last frame stays. Time (sound included)
     * re-anchors to the stepped frame, so [resume] continues from it.
     */
    fun stepForward() = commands.put(Command.StepForward)

    /**
     * Steps back to the previous frame, paused on it. The previous
     * frame's timestamp is only knowable by decoding from the keyframe
     * toward the shown one (frame rates vary mid-stream), so this costs
     * a keyframe run like an exact seek -- instant on dense keyframes,
     * advertised through [State.Seeking] on sparse ones. Repeated
     * backsteps reuse the discovered run and pay one run, not two.
     */
    fun stepBackward() = commands.put(Command.StepBackward)

    /** Linear 0..1 volume; no-op for silent playback. */
    fun setVolume(volume: Float) {
        audioPipeline?.setVolume(volume)
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
        commands.put(Command.SetRate(rate.coerceIn(0.5f, 4f)))
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
    fun selectSubtitleTrack(id: Int?) = commands.put(Command.SelectSubtitles(id))

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
     */
    fun setSubtitleCanvasSize(width: Int, height: Int) {
        announcedCanvas = width to height
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
     */
    @Volatile
    private var announcedCanvas: Pair<Int, Int>? = null

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

    override fun close() {
        closing = true
        commands.put(Command.Close)
        thread.join(5_000)
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
                try {
                    // Nobody else starts it on this path. With sound the
                    // pipeline's own clock is already running; without, this
                    // wall clock is the only thing that moves and it would
                    // otherwise sit at zero forever.
                    if (ownsClock) clock.start(0)
                    framelessLoop()
                    state = State.Closed
                } catch (framelessFailure: Throwable) {
                    state = State.Failed(framelessFailure)
                }
            } else {
                state = State.Failed(t)
            }
            audioPipeline?.close()
            return
        }
        durationNanos = decoder.durationNanos()
        tags = decoder.tags()
        chapters = decoder.chapters()
        coverArt = decoder.coverArt()
        rotationDegrees = decoder.rotationDegrees()
        hardwareActive = decoder.hardwareActive()
        synchronized(subtitleTracksLock) { subtitleTracks = subtitleTracks + decoder.subtitleTracks() }
        val pacer = Thread(::paceLoop, "skinema-pace").apply {
            isDaemon = true
            start()
        }
        try {
            if (ownsClock) clock.start(0)
            state = State.Playing
            decodeLoop(decoder)
            state = State.Closed
        } catch (t: Throwable) {
            state = State.Failed(t)
        } finally {
            queue.close()
            pacer.join(1_000)
            runCatching { decoder.close() }
            runCatching { audioPipeline?.close() }
            runCatching { subtitlePipeline?.close() }
        }
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
        state = State.Ended
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

    /** Audio-only playback: commands and lifecycle, no frames. */
    private fun framelessLoop() {
        state = State.Playing
        while (true) {
            val cmd = commands.poll(100, TimeUnit.MILLISECONDS)
            if (cmd != null && !handle(cmd, decoder = null)) return
            if (state is State.Playing && framelessLapDone()) {
                val pipe = audioPipeline
                if (loop) {
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

            if (state !is State.Playing) {
                // Paused or Ended: idle until the next command.
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
            pauseNow()
            true
        }
        Command.StepForward -> {
            performStepForward(decoder)
            true
        }
        Command.StepBackward -> performStepBackward(decoder)
        Command.Resume -> {
            if (state is State.Paused) {
                // The sink's buffered tail keeps sounding (and advancing the
                // device clock) for a beat after a pause lands; resuming
                // re-anchors sound to the frame actually on screen,
                // sample-precise. Frameless players have no frame to anchor
                // to and just resume.
                if (audioPipeline != null && decoder != null) {
                    val at = clock.mediaNanos()
                    audioPipeline.seek(at)
                    audioPipeline.videoLanded(at)
                }
                audioPipeline?.resume()
                clock.resume()
                state = State.Playing
            }
            true
        }
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
            if (state is State.Ended) state = State.Playing
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
        state = State.Seeking
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
                    if (loop) restartLap(decoder, resume = false) else enterEnded()
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

    private fun pauseNow() {
        if (state is State.Playing) {
            audioPipeline?.pause()
            clock.pause()
            state = State.Paused
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
            current?.closeAsync()
            subtitlePipeline = null
            return
        }
        if (current != null && !current.isDead && current.track.id == id) return
        val track = subtitleTracks.firstOrNull { it.id == id } ?: return
        // No libass, no text rendering: refuse like the audio switch
        // refuses an unopenable track. Bitmap tracks never need it.
        if (track.isText && !Ass.available) return
        current?.closeAsync()
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
        announcedCanvas?.let { (w, h) -> fresh.setCanvasSize(w, h) }
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
     * Resolves [State.Seeking] back to the lifecycle. A seek that landed
     * resumes whatever was running before the burst started (a paused
     * player stays paused at the new frame); [ended]/[playing] only chooses
     * the fallback when there was no prior state to restore.
     */
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

    private fun finishSeek(landed: State) {
        seekInFlight = false
        val prior = stateBeforeSeek.getAndSet(null)
        state = when {
            prior == State.Paused && landed != State.Ended -> State.Paused
            else -> landed
        }
    }

    // -- Pacer thread ---------------------------------------------------------

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
            if (state !is State.Playing) {
                // Paused, or a landing resolving: hold the inventory.
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
