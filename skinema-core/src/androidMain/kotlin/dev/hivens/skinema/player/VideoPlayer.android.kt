@file:OptIn(UnstableApi::class)

package dev.hivens.skinema.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.media.Image
import android.media.MediaCodecList
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.exoplayer.upstream.BandwidthMeter
import dev.hivens.skinema.Debug
import dev.hivens.skinema.android.AppContext
import dev.hivens.skinema.android.CueRasterizer
import dev.hivens.skinema.android.ContainerInfo
import dev.hivens.skinema.android.ForwardTape
import dev.hivens.skinema.android.FrameBridge
import dev.hivens.skinema.android.ImageFrameSource
import dev.hivens.skinema.android.ImageFrames
import dev.hivens.skinema.android.Input
import dev.hivens.skinema.android.PcmSinkAudioSink
import dev.hivens.skinema.android.PlayerDataSourceFactory
import dev.hivens.skinema.android.firstTrack
import dev.hivens.skinema.android.layoutFor
import dev.hivens.skinema.android.openExtractor
import dev.hivens.skinema.audio.ChannelPreference
import dev.hivens.skinema.audio.PcmEncoding
import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.libav.AudioTrack
import dev.hivens.skinema.libav.Chapter
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.MediaInput
import dev.hivens.skinema.libav.NoVideoStreamException
import dev.hivens.skinema.libav.ResolvedInput
import dev.hivens.skinema.libav.discard
import dev.hivens.skinema.libav.resolve
import dev.hivens.skinema.libav.SubtitleTrack
import dev.hivens.skinema.libav.VideoDecoder
import dev.hivens.skinema.subtitles.SubtitleOverlay
import java.nio.file.Files
import java.util.Locale
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import android.media.AudioFormat as PlatformAudioFormat

/**
 * One media file played through Media3 on Android.
 *
 * ExoPlayer decodes on the platform's codecs, paces against the audio device
 * and releases each frame at its own time; the frame is drawn on the GPU into
 * an image this player owns ([FrameBridge]) and published into a one-slot
 * mailbox, which is [acquireFrame]'s. Every call is safe from any thread: the
 * work is posted to the player's own thread, where ExoPlayer requires it.
 *
 * Where the contract differs from the desktop's, it is written on the member.
 * In general: frames arrive already turned for display, so [rotationDegrees]
 * is zero; `readAheadFrames` is not a knob here, since ExoPlayer buffers by
 * its own policy; and `explicitClock` is refused, since ExoPlayer takes no
 * external media clock to pace video against.
 *
 * An image file (a still, or an animated GIF, APNG or WebP) does not go
 * through ExoPlayer, which plays none of them as video. The player paces its
 * frames itself, from [ImageFrameSource], against a clock of its own, and
 * draws each one into the same bridge, so the mailbox, [acquireFrameBitmap]
 * and everything downstream see one kind of frame. A [setSource] moves
 * between the two freely.
 */
actual class VideoPlayer actual constructor(
    input: MediaInput,
    loop: Boolean,
    private val audio: Boolean,
    explicitClock: MediaClock?,
    private val sink: PcmSink?,
    @Suppress("UNUSED_PARAMETER") readAheadFrames: Int,
    audioTrack: Int?,
    private val hardware: HwAccel,
    private val unwatched: WhenUnwatched,
    startPaused: Boolean,
    volume: Float,
    private val audioChannels: ChannelPreference,
) : AutoCloseable {

    actual sealed interface State {
        actual data object Opening : State
        actual data object Playing : State
        actual data object Paused : State
        actual data object Ended : State
        actual data object Seeking : State
        actual data class Failed actual constructor(actual val cause: Throwable) : State
        actual data object Closed : State
    }

    actual class FrameSlot internal constructor() {
        actual var width: Int = 0
            internal set
        actual var height: Int = 0
            internal set
        actual var ptsNanos: Long = 0L
            internal set
        actual var rgba: ByteArray = ByteArray(0)
            internal set
    }

    private val thread = HandlerThread("skinema-player").apply { start() }
    private val handler = Handler(thread.looper)

    // -- State and change count ------------------------------------------------

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val changeMonitor = Object()

    @Volatile
    private var changes = 0L

    @Volatile
    private var stateField: State = State.Opening

    actual val state: State get() = stateField

    /** The desktop's rule: Failed is terminal, and the check and the write are one step. */
    private fun publishState(next: State) {
        synchronized(changeMonitor) {
            if (stateField is State.Failed && next !is State.Failed) return
            if (stateField == State.Closed) return
            if (stateField == next) return
            stateField = next
            changes++
            changeMonitor.notifyAll()
        }
    }

    private fun noteChange() {
        synchronized(changeMonitor) {
            changes++
            changeMonitor.notifyAll()
        }
    }

    actual val changeCount: Long get() = changes

    @Throws(InterruptedException::class)
    actual fun awaitChange(since: Long, timeoutNanos: Long): Long {
        synchronized(changeMonitor) {
            if (changes != since || timeoutNanos <= 0L) return changes
            changeMonitor.wait(timeoutNanos / 1_000_000L, (timeoutNanos % 1_000_000L).toInt())
            return changes
        }
    }

    // -- Metadata ----------------------------------------------------------------

    @Volatile
    actual var durationNanos: Long? = null
        private set

    @Volatile
    private var audioTrackList: List<AudioTrack> = emptyList()

    actual val audioTracks: List<AudioTrack> get() = audioTrackList

    @Volatile
    private var selectedAudioTrack: Int? = null

    actual val activeAudioTrack: Int? get() = if (audioTrackList.isEmpty()) null else selectedAudioTrack

    @Volatile
    private var audioFormat: PcmFormat? = null

    /** What the platform audio track was opened with, read off its initialisation. */
    actual val activeAudioFormat: PcmFormat? get() = audioFormat

    @Volatile
    actual var tags: Map<String, String> = emptyMap()
        private set

    @Volatile
    actual var chapters: List<Chapter> = emptyList()
        private set

    @Volatile
    actual var coverArt: ByteArray? = null
        private set

    /**
     * Zero, always. The platform applies a file's orientation as it renders,
     * so frames arrive already turned for display, which is what this
     * property's contract asks a consumer to do itself on the desktop.
     */
    actual var rotationDegrees: Int = 0
        private set

    @Volatile
    actual var hardwareActive: Boolean = false
        private set

    @Volatile
    actual var subtitleTracks: List<SubtitleTrack> = emptyList()
        private set

    // -- Settings ----------------------------------------------------------------

    @Volatile
    actual var rate: Float = 1f
        private set

    @Volatile
    private var volumeField: Float = if (volume.isNaN()) 1f else volume.coerceIn(0f, 1f)

    @Volatile
    private var loopField: Boolean = loop

    actual var loop: Boolean
        get() = loopField
        set(value) {
            loopField = value
            post {
                player?.repeatMode = if (value && seekable) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                // A still stands at its end all the time, so looping off ends
                // it now, which is the next end of the file.
                if (!value && clip?.animated == false && !clipEnded) {
                    clipEnded = true
                    recomputeState()
                }
            }
        }

    @Volatile
    actual var source: MediaInput = input
        private set

    /**
     * [source] as the readers open it, resolved on the player thread by the
     * open. Owned by this player: a switch releases the one it leaves, and a
     * close or a failure closes it.
     */
    // Why the constructor's input could not be resolved. The open fails with it.
    private var openFailure: Throwable? = null

    // Resolved here rather than at the open, as on the desktop, so [seekable]
    // answers for the input from the first call on.
    @Volatile
    private var resolved: ResolvedInput? = try {
        input.resolve()
    } catch (t: Throwable) {
        openFailure = t
        null
    }

    actual val seekable: Boolean get() = resolved !is ResolvedInput.Live

    // The streams Media3 may be reading, by the host of the URI they are
    // played under. A switch's probe registers its candidate here too.
    private val streams = ConcurrentHashMap<String, ResolvedInput>()
    private val streamIds = AtomicInteger(0)
    private val dataSources = PlayerDataSourceFactory { uri ->
        when (val s = streams[uri.host]) {
            is ResolvedInput.Seekable -> { { s.shared.cursor() } }
            is ResolvedInput.Live -> { { tapeOf(s).reader() } }
            else -> null
        }
    }

    /**
     * How Media3 is handed a forward-only stream. Matroska and WebM written
     * by an ordinary muxer keep their Cues at the end, and Media3's extractor
     * seeks there to read them, which such a stream cannot take: it would not
     * open at all. So for these the extractor reads on without them. A file,
     * or a stream that can seek, keeps the default and its cue-based seeking.
     */
    private val liveSources by lazy {
        ProgressiveMediaSource.Factory(
            dataSources,
            DefaultExtractorsFactory().setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES),
        )
    }

    /** Hands [item] for [input] to [p], through the forward-only path when [input] is one. */
    private fun setItem(p: ExoPlayer, input: ResolvedInput, item: MediaItem) {
        if (input is ResolvedInput.Live) p.setMediaSource(liveSources.createMediaSource(item)) else p.setMediaItem(item)
    }

    /** The one tape over a forward-only stream, kept by the stream for every reader of it. */
    private fun tapeOf(input: ResolvedInput.Live): ForwardTape = input.attach { ForwardTape(it) }

    @Volatile
    actual var sourceFailure: Throwable? = null
        private set

    // -- Player-thread state -----------------------------------------------------

    private var player: ExoPlayer? = null

    @Volatile
    private var bridge: FrameBridge? = null
    private var everReady = false

    // Read on ExoPlayer's playback thread too, where the frame callback asks
    // whether a frame released while the clock stands is a seek landing.
    @Volatile
    private var seekPending = false
    private var intendedNanos = 0L
    @Volatile
    private var hasVideo = false

    // Whether the player's shape (picture or sound alone) is fixed. It is
    // learnt from the first file and kept from then on, as on the desktop: a
    // sound player that is switched to a video plays it frameless.
    private var shapeSettled = false
    private var userPaused = startPaused
    private var autoPaused = false
    private var stopMode: StopMode? = null

    // The picture is off while nobody takes it: the video track disabled for
    // ExoPlayer, no frames composed for an image.
    private var videoTrackDisabled = false

    // The image file playing, when it is one. Its frames, its lap and whether
    // the lap ran out without looping: the image side's whole state.
    private var clip: ImageFrameSource? = null
    private var clipEnded = false
    private val clipAdvance = Runnable { advanceClip() }

    // Read from ExoPlayer's playback thread, where a frame released late by a
    // file just switched away from must not move the image's clock.
    @Volatile
    private var clipActive = false

    private enum class StopMode { FROZE, KEPT }

    @Volatile
    private var closing = false

    // -- Clock ---------------------------------------------------------------------

    // Sampled on the player thread and by the frame metadata callback, read
    // from anywhere: ExoPlayer may be asked for its position on its own thread
    // only, and positionNanos() is called from render loops.
    @Volatile
    private var clockBase = 0L

    @Volatile
    private var clockAt = 0L

    @Volatile
    private var clockRunning = false

    private fun sampleClock(positionNanos: Long, atNanos: Long, running: Boolean) {
        clockBase = positionNanos
        clockAt = atNanos
        clockRunning = running
    }

    actual fun positionNanos(): Long {
        val base = clockBase
        if (!clockRunning) return base
        return base + ((System.nanoTime() - clockAt) * rate).toLong()
    }

    // -- Frames ----------------------------------------------------------------------

    private val mailbox = Any()
    private var latest: Image? = null
    private var latestPts = 0L
    private var latestPremultiplied = false
    private var fresh = false

    // Images handed out by acquireFrameBitmap and not yet given back. A
    // superseded image is closed (returned to the reader) only once nothing
    // borrows it, so a frame being drawn is never recycled under the draw.
    private val borrowed = HashSet<Image>()

    @Volatile
    private var lastPublishedPts = 0L

    // Release time -> media time, filled as ExoPlayer releases each frame and
    // read when the frame comes out of the bridge carrying its release time.
    private val releaseToPts = ConcurrentHashMap<Long, Long>()

    // Timestamps of the images that carry a composed picture rather than a
    // decoded one. Those come out of the GPU premultiplied, as Android draws
    // every bitmap, and acquireFrame owes straight alpha.
    private val premultipliedTags: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var lastAcquireNanos = 0L

    @Volatile
    private var publishedSinceRead = false

    private var slotBytes = ByteArray(0)

    private fun onImage(image: Image) {
        if (closing) {
            release(image)
            return
        }
        val pts = releaseToPts.remove(image.timestamp) ?: lastPublishedPts
        val premultiplied = premultipliedTags.remove(image.timestamp)
        if (releaseToPts.size > RELEASE_MAP_LIMIT) {
            releaseToPts.keys.sorted().take(releaseToPts.size - RELEASE_MAP_LIMIT / 2).forEach(releaseToPts::remove)
        }
        if (premultipliedTags.size > RELEASE_MAP_LIMIT) {
            premultipliedTags.sorted().take(premultipliedTags.size - RELEASE_MAP_LIMIT / 2).forEach(premultipliedTags::remove)
        }
        synchronized(mailbox) {
            val old = latest
            latest = image
            latestPts = pts
            latestPremultiplied = premultiplied
            fresh = true
            if (old != null && old !in borrowed) release(old)
        }
        lastPublishedPts = pts
        publishedSinceRead = true
        noteChange()
        if (stepping) post { onStepFrame() }
    }

    actual fun acquireFrame(): FrameSlot? {
        lastAcquireNanos = System.nanoTime()
        publishedSinceRead = false
        if (!presentingSaid && !visibilityReported) post { mailboxRead() }
        synchronized(mailbox) {
            if (!fresh) return null
            val image = latest ?: return null
            fresh = false
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val rowBytes = width * 4
            val bytes = rowBytes * height
            if (slotBytes.size != bytes) slotBytes = ByteArray(bytes)
            val buffer = plane.buffer.duplicate()
            for (row in 0 until height) {
                buffer.position(row * plane.rowStride)
                buffer.get(slotBytes, row * rowBytes, rowBytes)
            }
            if (latestPremultiplied) unpremultiply(slotBytes)
            return FrameSlot().also {
                it.width = width
                it.height = height
                it.ptsNanos = latestPts
                it.rgba = slotBytes
            }
        }
    }

    /**
     * The freshest frame as a hardware [android.graphics.Bitmap], with no copy:
     * the image the GPU drew is wrapped as it is. Null when nothing newer
     * arrived since the last call, as [acquireFrame].
     *
     * The frame is borrowed. Its image stays out of the player's rotation until
     * [FrameBitmap.close], so a consumer that keeps one past the next frame
     * holds a buffer the player cannot reuse, and one that never closes them
     * stops the picture once the reader runs out. The Compose surface closes
     * each one as soon as the draw that used it has ended. Android only: the
     * desktop has no hardware frame to hand out.
     *
     * Shares the mailbox with [acquireFrame]: one reader per player, either way.
     */
    fun acquireFrameBitmap(): FrameBitmap? {
        lastAcquireNanos = System.nanoTime()
        publishedSinceRead = false
        if (!presentingSaid && !visibilityReported) post { mailboxRead() }
        synchronized(mailbox) {
            if (!fresh) return null
            val image = latest ?: return null
            fresh = false
            val buffer = image.hardwareBuffer ?: return null
            val bitmap = buffer.use { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) } ?: return null
            borrowed += image
            return FrameBitmap(image, bitmap, latestPts, ::giveBack, latestPremultiplied)
        }
    }

    private fun giveBack(image: Image) {
        synchronized(mailbox) {
            borrowed -= image
            if (image !== latest) release(image)
        }
    }

    private fun release(image: Image) {
        val frames = bridge
        if (frames != null) frames.release(image) else image.close()
    }

    // -- Watching ----------------------------------------------------------------------

    @Volatile
    private var presentingSaid = false

    @Volatile
    private var visibilityReported = false

    private var presenting = true
    private var reportedVisible: Boolean? = null

    actual fun setPresenting(presenting: Boolean) {
        presentingSaid = true
        post {
            this.presenting = presenting
            applyWatch(presenting)
        }
    }

    actual fun reportVisible(visible: Boolean?) {
        if (presentingSaid) return
        visibilityReported = visible != null
        post {
            if (presentingSaid) return@post
            reportedVisible = visible
            applyWatch(visible ?: true)
        }
    }

    private fun mailboxRead() {
        if (presentingSaid || visibilityReported) return
        applyWatch(true)
    }

    /** The mailbox notice: a mailbox that was being read and has gone unread for [UNREAD_NANOS]. */
    private fun checkMailbox() {
        if (presentingSaid || visibilityReported || stopMode != null) return
        val read = lastAcquireNanos
        if (read != 0L && publishedSinceRead && System.nanoTime() - read > UNREAD_NANOS) applyWatch(false)
    }

    /** What [WhenUnwatched] says to do with the picture being taken or not. On the player thread. */
    private fun applyWatch(watched: Boolean) {
        if (!hasVideo) return
        if (clip != null) {
            applyClipWatch(watched)
            return
        }
        val p = player ?: return
        if (!watched && stopMode == null) {
            val mode = when (unwatched) {
                WhenUnwatched.Freeze -> StopMode.FROZE
                WhenUnwatched.KeepTime -> StopMode.KEPT
                WhenUnwatched.FollowSound -> if (audible()) StopMode.KEPT else StopMode.FROZE
            }
            if (mode == StopMode.FROZE && p.playWhenReady) {
                autoPaused = true
                stopNow(p)
            } else {
                setVideoDisabled(true)
            }
            stopMode = mode
        } else if (watched && stopMode != null) {
            if (autoPaused && !userPaused) p.play()
            autoPaused = false
            setVideoDisabled(false)
            stopMode = null
        }
        recomputeState()
    }

    private fun audible(): Boolean {
        val p = player ?: return false
        return audio && selectedAudioTrack != null && volumeField > 0f && p.playbackState != Player.STATE_ENDED
    }

    private fun setVideoDisabled(disabled: Boolean) {
        val p = player ?: return
        // A forward-only stream keeps its picture track: bringing a disabled
        // track back makes Media3 reload the item from its start, which such a
        // stream cannot give. Its hidden picture goes on decoding instead.
        if (resolved is ResolvedInput.Live) return
        if (videoTrackDisabled == disabled) return
        videoTrackDisabled = disabled
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled).build()
    }

    // -- Commands ----------------------------------------------------------------------

    /**
     * Runs [block] on the player thread. A failed player takes no more
     * commands, as on the desktop, where the thread that would run them has
     * ended: nothing a later call does can bring playback back.
     */
    private fun post(block: () -> Unit) {
        if (closing) return
        handler.post {
            if (closing || stateField is State.Failed) return@post
            try {
                block()
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    actual fun pause() = post {
        userPaused = true
        if (clip != null) freezeClip() else player?.let(::stopNow)
        recomputeState()
    }

    /**
     * Pauses ExoPlayer and stops the clock at its position in the same step.
     * The state reads Paused at once, and ExoPlayer reports that it stopped
     * only later; the clock ran on from the last frame's release time until
     * then (which is a vsync ahead) and jumped back when the report came.
     */
    private fun stopNow(p: ExoPlayer) {
        val wasPlaying = p.playWhenReady
        p.pause()
        // Only a running clock is stopped here. One already standing reads
        // the frame on screen, and ExoPlayer's position after a seek is the
        // seek's target, which can sit just past the frame before it.
        if (wasPlaying) sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), false)
    }

    actual fun resume() = post {
        userPaused = false
        if (clip != null) {
            resumeClip()
            return@post
        }
        val p = player ?: return@post
        // Not the way back from a stop the player made for a hidden picture:
        // it plays, and the picture stays off until it is being taken again.
        if (stopMode == StopMode.FROZE) {
            autoPaused = false
            setVideoDisabled(true)
            stopMode = StopMode.KEPT
        }
        if (p.playbackState == Player.STATE_ENDED) {
            // A forward-only stream that ended is over: going back to its start
            // is a seek it cannot take.
            if (!seekable) return@post
            p.seekTo(0)
        }
        p.play()
        recomputeState()
    }

    actual fun seek(ptsNanos: Long, exact: Boolean) = post {
        // A stream that reads forward only cannot go anywhere else.
        if (!seekable) return@post
        seekTo(ptsNanos.coerceAtLeast(0), exact)
    }

    actual fun seekBy(deltaNanos: Long, exact: Boolean) = post {
        if (!seekable) return@post
        val base = when {
            clip != null -> positionNanos()
            seekPending -> intendedNanos
            hasVideo -> lastPublishedPts
            else -> positionNanos()
        }
        seekTo((base + deltaNanos).coerceAtLeast(0), exact)
    }

    private fun seekTo(target: Long, exact: Boolean) {
        if (clip != null) {
            // Every frame of an image can be reached, so exact or not lands
            // on the frame showing at the target.
            landClip(target)
            recomputeState()
            return
        }
        val p = player ?: return
        intendedNanos = target
        seekPending = true
        p.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        p.seekTo(target / NANOS_PER_MILLI)
        sampleClock(target, System.nanoTime(), false)
        recomputeState()
    }

    actual fun stepForward() = post {
        // Media3 steps by seeking, which a forward-only stream cannot take, so
        // on Android such a stream does not step at all.
        if (!seekable) return@post
        clip?.let { c ->
            val next = c.shown + 1
            stepClip(if (next < c.frameCount) next else if (loopField) 0 else c.shown)
            return@post
        }
        queueStep(forward = true)
    }

    actual fun stepBackward() = post {
        if (!seekable) return@post
        clip?.let { c ->
            stepClip((c.shown - 1).coerceAtLeast(0))
            return@post
        }
        queueStep(forward = false)
    }

    // Steps not yet taken, in the order asked. A step lands where the frame on
    // screen is, so each waits for the one before it (and for a seek in
    // flight) to put its frame up: two quick presses are two frames.
    private val queuedSteps = ArrayDeque<Boolean>()
    private var stepBusy = false
    private var stepFrom = 0L

    // Whether the busy step's own seek has gone out. A frame published before
    // it is one released before the step, still on its way when the player
    // paused, and lands nothing.
    private var stepSeeking = false

    // Which step a timer or a discovery's answer belongs to. Anything carrying
    // an older one is about a step that has gone, and does nothing.
    private var stepToken = 0L

    // Until when a step waits for frames already on their way to land, after
    // a press that paused a playing player: the frame on screen is not known
    // before then.
    private var stepAfter = 0L

    // Read on the frame thread, which tells the player thread a frame landed
    // only while something waits for one.
    @Volatile
    private var stepping = false

    private fun queueStep(forward: Boolean) {
        val p = player ?: return
        userPaused = true
        val wasPlaying = p.isPlaying
        stopNow(p)
        queuedSteps.addLast(forward)
        stepping = true
        if (wasPlaying && !stepBusy) {
            stepAfter = System.nanoTime() + STEP_SETTLE_PLAYING_MILLIS * NANOS_PER_MILLI
            stepLater(STEP_SETTLE_PLAYING_MILLIS)
        }
        runSteps()
    }

    /** Takes the next queued step, unless one is landing, a seek is, or frames from playing are still arriving. */
    private fun runSteps() {
        if (stepBusy || seekPending || System.nanoTime() < stepAfter) return
        val forward = queuedSteps.removeFirstOrNull()
        if (forward == null) {
            stepping = false
            return
        }
        stepBusy = true
        stepSeeking = false
        val token = ++stepToken
        val shown = lastPublishedPts
        stepFrom = shown
        if (forward) {
            // The first frame at or after a millisecond past the one on screen
            // is the next one: frames are never closer than that.
            issueStepSeek((shown / NANOS_PER_MILLI + 1) * NANOS_PER_MILLI)
            return
        }
        val file = checkNotNull(resolved)
        // For when the decode below cannot answer. An exact seek lands on the
        // first frame at or after its target, so a target a millisecond back,
        // or half a frame back, lands on the frame already shown. A frame and
        // a half back sits before the previous frame and after the one before
        // that, so at a steady rate the seek lands on the previous frame.
        val frameNanos = player?.videoFormat?.frameRate?.takeIf { it > 0f }?.let { (1_000_000_000.0 / it).toLong() } ?: FALLBACK_FRAME_NANOS
        val find = previousFrameFinder
        // Which frame precedes the one on screen is only knowable by decoding
        // toward it, as on the desktop: run the platform decoder over the
        // stretch before it, off the player thread, and land an exact seek on
        // the answer.
        try {
            decodeWork.execute {
                val previous = runCatching { find(file, shown) }
                    .onFailure { Debug.trace("step backward discovery", it) }
                    .getOrNull()
                post {
                    if (token != stepToken || !stepBusy) return@post
                    issueStepSeek(previous ?: (shown - frameNanos * 3 / 2).coerceAtLeast(0))
                }
            }
        } catch (_: RejectedExecutionException) {
            // The player is closing and its worker has stopped.
            dropSteps()
        }
    }

    /**
     * The busy step's seek. It lands when its frame is up (onStepFrame), when
     * the seek settles with none coming (the end of the file, see the state
     * listener), or at the latest after [STEP_GIVE_UP_MILLIS].
     */
    private fun issueStepSeek(target: Long) {
        seekTo(target, exact = true)
        stepSeeking = true
        stepLater(STEP_GIVE_UP_MILLIS)
    }

    /** Looks at the queue again after [millis], on the player thread, for the step current now. */
    private fun stepLater(millis: Long) {
        val token = stepToken
        handler.postDelayed({
            post {
                if (token != stepToken) return@post
                if (stepBusy) stepLanded() else runSteps()
            }
        }, millis)
    }

    /** A step's frame is up, or none is coming: the next step may go. */
    private fun stepLanded() {
        stepBusy = false
        stepSeeking = false
        stepToken++
        runSteps()
    }

    /** On the player thread, after a frame was published while a step waited. */
    private fun onStepFrame() {
        if (seekPending) return
        if (stepBusy) {
            if (stepSeeking && lastPublishedPts != stepFrom) stepLanded()
        } else {
            runSteps()
        }
    }

    /** A seek that settled while steps wait: READY, or ENDED for one that ran off the end. */
    private fun onStepSeekSettled(ended: Boolean) {
        if (!stepping) return
        // A landing at the end puts up no frame, and neither does one on the
        // frame already shown, so the step is not held for one past a short
        // wait.
        if (ended && stepBusy && stepSeeking) stepLanded() else stepLater(STEP_SETTLE_MILLIS)
    }

    private fun dropSteps() {
        queuedSteps.clear()
        stepBusy = false
        stepSeeking = false
        stepToken++
        stepAfter = 0L
        stepping = false
    }

    /**
     * Opens [input] in a throwaway ExoPlayer with every track type off, so the
     * container is parsed and no codec opens, and answers why it will not
     * play in a player of this shape: no such track, no decoder for it, or
     * the error Media3 gave. Null when it will. Off the player thread.
     */
    private fun probe(input: ResolvedInput, picture: Boolean): Throwable? {
        val label = input.label
        val probeThread = HandlerThread("skinema-probe").apply { start() }
        val probeHandler = Handler(probeThread.looper)
        val done = CountDownLatch(1)
        var answer: Throwable? = null
        var probePlayer: ExoPlayer? = null
        probeHandler.post {
            try {
                val context = AppContext.get()
                val p = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setMediaCodecSelector(codecSelector(hardware)))
                    .setLooper(probeThread.looper)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
                    .setBandwidthMeter(NoBandwidthMeter)
                    .setUsePlatformDiagnostics(false)
                    .build()
                probePlayer = p
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                p.addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        if (tracks.groups.isEmpty() || done.count == 0L) return
                        val type = if (picture) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
                        val groups = tracks.groups.filter { it.type == type }
                        answer = when {
                            groups.isEmpty() -> LibavException(if (picture) "$label gives no picture" else "$label carries no sound")
                            groups.none { it.isSupported(true) } -> {
                                val mime = groups.first().getTrackFormat(0).sampleMimeType
                                LibavException(
                                    if (picture && hardware == HwAccel.REQUIRE) "no hardware decoder takes $mime (HwAccel.REQUIRE)" else "no decoder on this device takes $mime",
                                )
                            }
                            else -> null
                        }
                        done.countDown()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        answer = error
                        done.countDown()
                    }
                })
                setItem(p, input, MediaItem.fromUri(uriOf(input)))
                p.prepare()
            } catch (t: Throwable) {
                answer = t
                done.countDown()
            }
        }
        try {
            if (!done.await(PROBE_MILLIS, TimeUnit.MILLISECONDS)) {
                answer = LibavException("$label did not open within ${PROBE_MILLIS / 1000} s")
            }
        } finally {
            probeHandler.post {
                runCatching { probePlayer?.release() }
                probeThread.quitSafely()
            }
        }
        return answer
    }

    /** How a step back finds the frame before the one shown; a test replaces it to make the discovery fail. */
    @Volatile
    internal var previousFrameFinder: (ResolvedInput, Long) -> Long? = ::previousFramePts

    private fun previousFramePts(input: ResolvedInput, shown: Long): Long? {
        if (shown <= 0L) return 0L
        openDecoder(input).use { d ->
            d.seekBefore(shown)
            var previous: Long? = null
            while (true) {
                val frame = d.nextFrame(convert = false) ?: break
                if (frame.ptsNanos >= shown) break
                previous = frame.ptsNanos
            }
            return previous
        }
    }

    actual fun setVolume(volume: Float) {
        if (volume.isNaN()) return
        volumeField = volume.coerceIn(0f, 1f)
        post { if (audio) player?.volume = volumeField }
    }

    actual fun setRate(rate: Float) {
        if (rate.isNaN()) return
        val clamped = rate.coerceIn(0.5f, 4f)
        post {
            // Re-anchored at the old rate first, or the time since the last
            // anchor would be counted at the new one.
            if (clip != null) sampleClock(positionNanos(), System.nanoTime(), clockRunning)
            this.rate = clamped
            player?.playbackParameters = PlaybackParameters(clamped)
            scheduleClip()
        }
    }

    actual fun setSource(input: MediaInput) {
        // A player that is gone takes no more files, and a stream handed to it
        // is still its to close: nothing else will.
        if (closing || stateField is State.Failed) {
            input.discard()
            return
        }
        // Held until the switch has resolved it, so a close in between closes
        // it rather than leaving it to nobody.
        pendingSwitches += input
        switchWhenShaped(input, System.nanoTime() + SHAPE_WAIT_LIMIT_NANOS)
    }

    private fun switchWhenShaped(input: MediaInput, deadline: Long): Unit = post {
        // A forward-only stream tells its shape only through the track report,
        // and the switch keeps that shape: it waits for the report, and not for
        // ever, since a feed that has not started may never send one.
        if (resolved is ResolvedInput.Live && !shapeSettled && player != null) {
            if (System.nanoTime() > deadline) {
                pendingSwitches -= input
                input.discard()
                sourceFailure = LibavException("the stream playing never said what it carries, so a switch could not keep its shape")
                noteChange()
                return@post
            }
            handler.postDelayed({ switchWhenShaped(input, deadline) }, SHAPE_WAIT_MILLIS)
            return@post
        }
        pendingSwitches -= input
        // Read on the player thread, behind the open, where the player
        // knows its shape: a switch asked for straight after construction
        // would otherwise find a picture player not yet knowing it has one.
        val picture = hasVideo
        decodeWork.execute {
            // Checked before the player touches it, so a file that will not
            // play is refused and the one playing carries on, as on the
            // desktop. An image is decoded to its first frame by the source
            // that will play it. Anything else is opened by Media3, the engine
            // that will play it, which parses the container and says whether
            // a decoder takes the track this player's shape needs: the
            // platform extractor reads a different set of containers, so a
            // check through it refused files the player plays and passed
            // files it does not.
            var candidate: ResolvedInput? = null
            val refusal = runCatching {
                if (closing) throw LibavException("the player was closed")
                val r = input.resolve().also { candidate = it }
                register(r)
                if (picture && isImageFile(r)) {
                    openDecoder(r).use { it.nextFrame(convert = false) ?: error("${r.label} gives no picture") }
                } else {
                    probe(r, picture)?.let { throw it }
                }
            }.exceptionOrNull()
            if (refusal != null) {
                // Closed rather than released: nothing of the player is going to
                // read it, and a reader stuck inside it would hold a release open.
                candidate?.let {
                    streams.values.remove(it)
                    it.close()
                }
                sourceFailure = refusal
                noteChange()
                return@execute
            }
            val opened = checkNotNull(candidate)
            val image = picture && isImageFile(opened)
            val info = if (image) null else readContainer(opened)
            post {
                // A player that had ended plays the next file, as on the desktop.
                val ended = if (clip != null) clipEnded else player?.playbackState == Player.STATE_ENDED
                if (ended) userPaused = false
                sourceFailure = null
                source = input
                resolved?.let(::letGo)
                resolved = opened
                everReady = false
                seekPending = false
                lastPublishedPts = 0L
                dropSteps()
                chosenAudioTrack = null
                reapplyChoices = false
                // Subtitles go off with the file they belonged to, as on the desktop.
                externals.clear()
                selectedSubtitleId = null
                pendingSubtitleId = null
                currentCues = emptyList()
                publishState(State.Opening)
                if (info == null) {
                    player?.let {
                        it.stop()
                        it.clearMediaItems()
                    }
                    publishCues()
                    startClip(opened)
                    return@post
                }
                closeClip()
                bridge?.acceptDecoderFrames(true)
                val p = player ?: buildPlayer()
                tags = info.tags
                coverArt = info.coverArt
                chapters = info.chapters
                durationNanos = null
                sampleClock(0L, System.nanoTime(), false)
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    // The picture as this player stands now: off while hidden
                    // and keeping time, whichever engine was hiding it, and
                    // off for good on a player whose shape is sound alone.
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, videoTrackDisabled || !picture)
                    .build()
                publishCues()
                p.repeatMode = if (loopField && seekable) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                setItem(p, opened, mediaItem(opened))
                p.prepare()
                // A player stopped for a hidden picture stays stopped: the
                // switch does not start a file nobody is watching.
                p.playWhenReady = !userPaused && !autoPaused
            }
        }
    }

    actual fun selectAudioTrack(streamIndex: Int) = post { overrideAudio(streamIndex) }

    // The sound track chosen by index, kept so it can be chosen again when a
    // re-preparation renames every track group (see addExternalSubtitles).
    private var chosenAudioTrack: Int? = null

    private fun overrideAudio(streamIndex: Int) {
        val p = player ?: return
        val group = p.currentTracks.groups.getOrNull(streamIndex) ?: return
        if (group.type != C.TRACK_TYPE_AUDIO) return
        chosenAudioTrack = streamIndex
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
    }

    // Set when the item is prepared again with side-loaded files attached:
    // Media3 then merges sources, and every group comes back under a new
    // identity that the overrides made before no longer match.
    private var reapplyChoices = false

    // -- Subtitles ----------------------------------------------------------------------

    private class External(val path: Path, val mime: String, val track: SubtitleTrack)

    private val externals = CopyOnWriteArrayList<External>()
    private val externalIds = AtomicInteger(0)
    private var embeddedSubtitles: List<SubtitleTrack> = emptyList()

    @Volatile
    private var selectedSubtitleId: Int? = null
    private var pendingSubtitleId: Int? = null
    private var currentCues: List<Cue> = emptyList()

    @Volatile
    private var canvasWidth = 0

    @Volatile
    private var canvasHeight = 0

    @Volatile
    private var videoWidth = 0

    @Volatile
    private var videoHeight = 0

    private val overlayLock = Any()
    private var overlay: SubtitleOverlay? = null
    private var overlayFresh = false
    private var overlayGeneration = 0L

    /**
     * Appends [file]'s track under a negative id, as on the desktop. The file
     * is side-loaded into the playing item, which ExoPlayer can only do by
     * preparing it again at the current position: the picture holds while it
     * does, and the sound pauses for as long as that takes. Formats are told
     * by extension (.srt, .ass, .ssa, .vtt, .ttml); an unreadable file or one
     * of another kind adds nothing, and so does an image file playing: its
     * frames are drawn by this player, not by ExoPlayer, which is what lays
     * out the cues.
     */
    actual fun addExternalSubtitles(file: Path): List<SubtitleTrack> {
        // Attaching a file prepares the item again from where it stands, which
        // a forward-only stream cannot be asked for.
        if (closing || !hasVideo || clipActive || resolved is ResolvedInput.Live) return emptyList()
        val mime = subtitleMimeFor(file) ?: return emptyList()
        if (!Files.isReadable(file)) return emptyList()
        val id = externalIds.decrementAndGet()
        val track = SubtitleTrack(
            id = id, streamIndex = 0, language = null, title = file.fileName?.toString(),
            codecName = mime, isText = true, isDefault = false, isForced = false, externalPath = file,
        )
        externals += External(file, mime, track)
        subtitleTracks = embeddedSubtitles + externals.map { it.track }
        post {
            val p = player ?: return@post
            val position = p.currentPosition
            val playing = p.playWhenReady
            reapplyChoices = true
            p.setMediaItem(mediaItem(checkNotNull(resolved)), position)
            p.prepare()
            p.playWhenReady = playing
        }
        return listOf(track)
    }

    actual fun selectSubtitleTrack(id: Int?) = post {
        val p = player ?: return@post
        if (id == null) {
            selectedSubtitleId = null
            pendingSubtitleId = null
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            currentCues = emptyList()
            publishCues()
            return@post
        }
        val group = subtitleGroup(id, p.currentTracks)
        if (group == null) {
            // A side-loaded file whose preparation has not landed yet.
            if (subtitleTracks.any { it.id == id }) pendingSubtitleId = id
            return@post
        }
        selectSubtitles(group, id)
    }

    private fun selectSubtitles(group: Tracks.Group, id: Int) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
        selectedSubtitleId = id
        noteChange()
    }

    /** The text group a skinema id names: a group index, or a side-loaded file's tagged id. */
    private fun subtitleGroup(id: Int, tracks: Tracks): Tracks.Group? {
        val groups = tracks.groups
        if (id >= 0) return groups.getOrNull(id)?.takeIf { it.type == C.TRACK_TYPE_TEXT }
        val tag = EXTERNAL_PREFIX + (-id)
        return groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.getTrackFormat(0).id?.endsWith(tag) == true }
    }

    actual val activeSubtitleTrack: Int? get() = selectedSubtitleId

    actual fun acquireSubtitles(): SubtitleOverlay? = synchronized(overlayLock) {
        if (!overlayFresh) return null
        overlayFresh = false
        overlay
    }

    actual fun setSubtitleCanvasSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == canvasWidth && height == canvasHeight) return
        canvasWidth = width
        canvasHeight = height
        post { if (selectedSubtitleId != null) publishCues() }
    }

    /** The cues on screen, drawn at the announced canvas size or, before one, the video's own. */
    private fun publishCues() {
        val width = canvasWidth.takeIf { it > 0 } ?: videoWidth
        val height = canvasHeight.takeIf { it > 0 } ?: videoHeight
        val next = CueRasterizer.rasterize(currentCues, width, height)
        synchronized(overlayLock) {
            next.generation = ++overlayGeneration
            overlay = next
            overlayFresh = true
        }
        noteChange()
    }

    // -- Lifecycle --------------------------------------------------------------------

    private val decodeWork = Executors.newSingleThreadExecutor { r -> Thread(r, "skinema-player-work").apply { isDaemon = true } }

    /** The file, with every side-loaded subtitle file attached under its tagged id. */
    private fun mediaItem(input: ResolvedInput): MediaItem = MediaItem.Builder()
        .setUri(uriOf(input))
        .setSubtitleConfigurations(
            externals.map { e ->
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(e.path.toFile()))
                    .setMimeType(e.mime)
                    .setId(EXTERNAL_PREFIX + (-e.track.id))
                    .setLabel(e.track.title)
                    .build()
            },
        )
        .build()

    private fun open(input: MediaInput, explicitClock: MediaClock?, audioTrack: Int?, startPaused: Boolean) {
        if (closing) return
        try {
            require(explicitClock == null) { "explicitClock is not supported on Android: ExoPlayer takes no external media clock" }
            bridge = FrameBridge(::onImage, ::fail)
            openFailure?.let { throw it }
            val opened = checkNotNull(resolved)
            register(opened)
            if (isImageFile(opened)) {
                startClip(opened)
            } else {
                hasVideo = carriesVideo(opened)
                val info = readContainer(opened)
                tags = info.tags
                coverArt = info.coverArt
                chapters = info.chapters
                val p = buildPlayer()
                pendingAudioTrack = audioTrack
                setItem(p, opened, mediaItem(opened))
                p.playWhenReady = !startPaused
                p.prepare()
            }
            handler.postDelayed(tick, TICK_MILLIS)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    /**
     * ExoPlayer, wired to the bridge and carrying every setting made so far.
     * Built when the first file that is not an image arrives, so a player
     * that only ever shows images never starts one.
     */
    private fun buildPlayer(): ExoPlayer {
        val context = AppContext.get()
        val frames = checkNotNull(bridge)
        val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink? = when {
                    // Ignored without sound, as on the desktop: nothing opens it.
                    sink != null && audio -> PcmSinkAudioSink(sink, audioChannels) { audioFormat = it }
                    audioChannels == ChannelPreference.Stereo -> DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(stereoFold()))
                        .build()
                    else -> super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
                }
        }.setMediaCodecSelector(codecSelector(hardware)).setEnableDecoderFallback(hardware == HwAccel.AUTO)
        // Local files only, so nothing about the network is asked: the default
        // bandwidth meter reads the network's country and watches its type,
        // and platform diagnostics report every playback session to the
        // system's media metrics service. Neither is wanted, both are on by
        // default.
        val p = ExoPlayer.Builder(context, renderers)
            .setLooper(thread.looper)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setBandwidthMeter(NoBandwidthMeter)
            .setUsePlatformDiagnostics(false)
            .build()
        player = p
        p.setVideoSurface(frames.inputSurface)
        p.addListener(listener)
        p.addAnalyticsListener(analytics)
        p.setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
            if (clipActive) return@setVideoFrameMetadataListener
            val pts = presentationTimeUs * NANOS_PER_MICRO
            releaseToPts[releaseTimeNs] = pts
            // A standing clock moves for a seek's landing frame and for
            // nothing else: a frame already on its way when a pause landed
            // would set it back to wherever the picture lagged the clock.
            if (clockRunning || seekPending) sampleClock(pts, releaseTimeNs, clockRunning)
        }
        // A forward-only stream cannot be turned: its end is the end.
        p.repeatMode = if (loopField && seekable) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.volume = if (audio) volumeField else 0f
        p.playbackParameters = PlaybackParameters(rate)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audio)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        return p
    }

    /**
     * Whether [input] has a picture, asked of the extractor at the open rather
     * than waited for from ExoPlayer's track report, which comes later: the
     * shape a [setSource] keeps has to be known from the start.
     */
    // A forward-only stream is not asked: its shape comes from the track report.
    private fun carriesVideo(input: ResolvedInput): Boolean = input !is ResolvedInput.Live && runCatching {
        readerOf(input).use { reader ->
            val extractor = openExtractor(reader)
            try {
                extractor.firstTrack("video/") >= 0
            } finally {
                extractor.release()
            }
        }
    }.getOrDefault(false)

    private fun readContainer(input: ResolvedInput): ContainerInfo = readerOf(input).use { ContainerInfo.read(it) }

    /** A reader of its own over [input]: the file, or a fresh cursor over the stream. Closing it closes only the cursor. */
    private fun readerOf(input: ResolvedInput): Input = when (input) {
        is ResolvedInput.File -> Input.File(input.path)
        is ResolvedInput.Seekable -> Input.Source(input.shared.cursor())
        is ResolvedInput.Live -> Input.Stream(tapeOf(input), owns = false)
    }

    private fun openDecoder(input: ResolvedInput): VideoDecoder = when (input) {
        is ResolvedInput.File -> VideoDecoder.open(input.path, hardware)
        is ResolvedInput.Seekable -> VideoDecoder.open(input.shared.cursor(), hardware)
        // Only the step back and an image's first frame come here, and neither does for a forward-only stream.
        is ResolvedInput.Live -> throw LibavException("a stream that reads forward only has no frame to go back to")
    }

    /** What Media3 is handed for [input]: the file, or a URI the data source factory answers with the stream. */
    private fun uriOf(input: ResolvedInput): Uri = when (input) {
        is ResolvedInput.File -> Uri.fromFile(input.path.toFile())
        is ResolvedInput.Seekable, is ResolvedInput.Live ->
            Uri.parse("${PlayerDataSourceFactory.STREAM_SCHEME}://${streams.entries.first { it.value === input }.key}")
    }

    private fun register(input: ResolvedInput) {
        if (input !is ResolvedInput.File) streams["s" + streamIds.incrementAndGet()] = input
    }

    /**
     * Releases [input], which closes a stream once the last reader of it has
     * let go, and keeps it until the close in case that reader is stuck.
     */
    private fun letGo(input: ResolvedInput) {
        streams.values.remove(input)
        released += input
        input.release()
    }

    // Inputs a switch moved off, and switches not yet resolved. Both are this
    // player's to close.
    private val released = CopyOnWriteArrayList<ResolvedInput>()
    private val pendingSwitches: MutableSet<MediaInput> = ConcurrentHashMap.newKeySet()

    // -- Image files --------------------------------------------------------------

    // An image is read whole, which a forward-only stream is not made for:
    // such a stream is played as a stream.
    private fun isImageFile(input: ResolvedInput): Boolean = input !is ResolvedInput.Live &&
        runCatching { readerOf(input).use { ImageFrames.isImage(it.head(16)) } }.getOrDefault(false)

    /** Puts the image [input] holds on screen in place of whatever played, on its first frame. */
    private fun startClip(input: ResolvedInput) {
        if (hardware == HwAccel.REQUIRE) throw LibavException("no hardware decoder takes an image (HwAccel.REQUIRE)")
        val image = ImageFrames.read(readerOf(input).use { it.readAll() })
        closeClip()
        val c = ImageFrameSource(image)
        clip = c
        clipActive = true
        hasVideo = true
        hardwareActive = false
        durationNanos = c.durationNanos()
        tags = emptyMap()
        chapters = emptyList()
        coverArt = null
        audioTrackList = emptyList()
        selectedAudioTrack = null
        audioFormat = null
        embeddedSubtitles = emptyList()
        subtitleTracks = emptyList()
        videoWidth = image.width
        videoHeight = image.height
        bridge?.acceptDecoderFrames(false)
        bridge?.resize(image.width, image.height, redraw = false)
        landClip(0L)
        recomputeState()
    }

    private fun closeClip() {
        handler.removeCallbacks(clipAdvance)
        clip?.close()
        clip = null
        clipActive = false
        clipEnded = false
    }

    /** Whether the image's clock runs: an animation, not ended, and paused by nobody. */
    private fun clipRuns(): Boolean = clip?.animated == true && !clipEnded && !userPaused && !autoPaused

    /**
     * Shows the frame at [target], clamped to the lap, and anchors the clock
     * there. A still has one frame and no time: it reads Ended unless it
     * loops, as the desktop's does once its single frame has played, and the
     * clock never runs, where the desktop's turns a 40 ms lap over and over.
     */
    private fun landClip(target: Long) {
        val c = clip ?: return
        val at = if (c.animated) target.coerceIn(0L, c.lapNanos) else 0L
        c.show(c.frameAt(at))
        presentClip()
        clipEnded = !c.animated && !loopField
        sampleClock(at, System.nanoTime(), clipRuns())
        scheduleClip()
    }

    private fun stepClip(index: Int) {
        val c = clip ?: return
        userPaused = true
        freezeClip()
        landClip(c.ptsOf(index))
        recomputeState()
    }

    private fun freezeClip() {
        handler.removeCallbacks(clipAdvance)
        sampleClock(positionNanos(), System.nanoTime(), false)
    }

    private fun resumeClip() {
        // Not the way back from a stop the player made for a hidden picture,
        // as with a video: the clock runs and the frames stay off.
        if (stopMode == StopMode.FROZE) {
            autoPaused = false
            videoTrackDisabled = true
            stopMode = StopMode.KEPT
        }
        if (clipEnded) landClip(0L)
        if (clipRuns()) sampleClock(positionNanos(), System.nanoTime(), true)
        scheduleClip()
        recomputeState()
    }

    /** Draws the composed frame into the bridge, stamped so it comes out carrying its pts. */
    private fun presentClip() {
        val c = clip ?: return
        val frames = bridge ?: return
        val tag = maxOf(System.nanoTime(), lastClipTag + 1)
        lastClipTag = tag
        releaseToPts[tag] = c.ptsOf(c.shown)
        premultipliedTags += tag
        // A copy, since the next frame is composed onto the same canvas while
        // the bridge may still be uploading this one.
        frames.present(c.canvasBitmap.copy(Bitmap.Config.ARGB_8888, false), tag)
    }

    private var lastClipTag = 0L

    /** Wakes at the next frame's time, or at the end of the lap while the frames are off. */
    private fun scheduleClip() {
        handler.removeCallbacks(clipAdvance)
        val c = clip ?: return
        if (!clockRunning) return
        val next = c.shown + 1
        val event = if (!videoTrackDisabled && next < c.frameCount) c.ptsOf(next) else c.lapNanos
        val wallNanos = ((event - positionNanos()) / rate).toLong()
        // Rounded up, or the wake lands just short of the frame and spins.
        handler.postDelayed(clipAdvance, ((wallNanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI).coerceAtLeast(0L))
    }

    private fun advanceClip() {
        val c = clip ?: return
        if (!clockRunning) return
        var position = positionNanos()
        val lap = c.lapNanos
        if (position >= lap) {
            if (!loopField) {
                sampleClock(lap, System.nanoTime(), false)
                clipEnded = true
                recomputeState()
                return
            }
            position %= lap
            sampleClock(position, System.nanoTime(), true)
        }
        if (!videoTrackDisabled) {
            val index = c.frameAt(position)
            if (index != c.shown) {
                c.show(index)
                presentClip()
            }
        }
        scheduleClip()
    }

    /** [applyWatch] for an image. Nothing is ever heard, so FollowSound stops time with the picture. */
    private fun applyClipWatch(watched: Boolean) {
        val c = clip ?: return
        if (!watched && stopMode == null) {
            val mode = if (unwatched == WhenUnwatched.KeepTime) StopMode.KEPT else StopMode.FROZE
            if (mode == StopMode.FROZE && clockRunning) {
                autoPaused = true
                freezeClip()
            } else {
                videoTrackDisabled = true
                scheduleClip()
            }
            stopMode = mode
        } else if (watched && stopMode != null) {
            val lift = autoPaused && !userPaused
            autoPaused = false
            if (videoTrackDisabled) {
                videoTrackDisabled = false
                // Catches up with the clock. An ended one keeps the frame it
                // had, as on the desktop: nothing decoded the end.
                val index = c.frameAt(positionNanos().coerceAtMost(c.lapNanos))
                if (!clipEnded && index != c.shown) {
                    c.show(index)
                    presentClip()
                }
            }
            if (lift) sampleClock(positionNanos(), System.nanoTime(), true)
            scheduleClip()
            stopMode = null
        }
        recomputeState()
    }

    private var pendingAudioTrack: Int? = null

    private val tick = object : Runnable {
        override fun run() {
            if (closing) return
            if (clip != null) {
                checkMailbox()
                handler.postDelayed(this, TICK_MILLIS)
                return
            }
            val p = player ?: return
            if (!hasVideo || stopMode != null) {
                // No frames drive the clock here, so ExoPlayer's position does
                // while it plays. A standing clock is left where the pause put
                // it: ExoPlayer's position settles after a pause, at wherever
                // its own thread stopped, and following it would move a clock
                // the state already calls Paused.
                if (p.isPlaying || clockRunning) sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), p.isPlaying)
            } else {
                clockRunning = p.isPlaying
            }
            checkMailbox()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    private fun recomputeState() {
        if (clip != null) {
            publishState(
                when {
                    clipEnded -> State.Ended
                    userPaused || autoPaused -> State.Paused
                    else -> State.Playing
                },
            )
            return
        }
        val p = player ?: return
        val next = when {
            p.playbackState == Player.STATE_IDLE || !everReady -> State.Opening
            seekPending -> State.Seeking
            p.playbackState == Player.STATE_ENDED -> State.Ended
            p.playbackState == Player.STATE_BUFFERING -> State.Seeking
            p.playWhenReady -> State.Playing
            else -> State.Paused
        }
        publishState(next)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (clip != null) return
            val p = player ?: return
            if (playbackState == Player.STATE_READY) {
                val everReadyBefore = everReady
                everReady = true
                if (seekPending) {
                    seekPending = false
                    intendedNanos = p.currentPosition * NANOS_PER_MILLI
                    onStepSeekSettled(ended = false)
                }
                // Paused on a picture, the position is the frame on screen,
                // which the frame callback has set or is about to; the seek's
                // target is a millisecond past the frame before it.
                val pictureHolds = hasVideo && !videoTrackDisabled && !p.isPlaying && everReadyBefore
                if (!pictureHolds) sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), p.isPlaying)
            }
            if (playbackState == Player.STATE_ENDED) {
                // A seek past the last frame ends here without passing READY.
                if (seekPending) {
                    seekPending = false
                    onStepSeekSettled(ended = true)
                }
                sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), false)
            }
            recomputeState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (clip != null) return
            val p = player ?: return
            sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), isPlaying)
            recomputeState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = recomputeState()

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            // What a forward-only stream says about itself comes from Media3,
            // since nothing here reads it by offset.
            if (resolved !is ResolvedInput.Live) return
            tags = buildMap {
                mediaMetadata.title?.let { put("title", it.toString()) }
                mediaMetadata.artist?.let { put("artist", it.toString()) }
                mediaMetadata.albumTitle?.let { put("album", it.toString()) }
                mediaMetadata.albumArtist?.let { put("album_artist", it.toString()) }
                mediaMetadata.genre?.let { put("genre", it.toString()) }
            }
            mediaMetadata.artworkData?.let { coverArt = it }
        }

        override fun onPlayerError(error: PlaybackException) {
            // ExoPlayer stands idle while an image plays; a late word from
            // the file it was stopped on is not this player's failure.
            if (clip == null) fail(error)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (clip != null) return
            val duration = player?.duration ?: return
            durationNanos = if (duration == C.TIME_UNSET || duration < 0) null else duration * NANOS_PER_MILLI
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (clip != null) return
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
            }
            bridge?.resize(videoSize.width, videoSize.height)
        }

        override fun onCues(cueGroup: CueGroup) {
            if (clip != null) return
            if (selectedSubtitleId == null) return
            currentCues = cueGroup.cues
            publishCues()
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (clip != null) return
            val groups = tracks.groups
            val video = groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            // An empty report (the item cleared for the next one) settles nothing.
            if (!shapeSettled && groups.isNotEmpty()) {
                shapeSettled = true
                if (video.isNotEmpty() && !hasVideo) {
                    hasVideo = true
                    // A forward-only stream learns it has a picture only here,
                    // and a word on watching that came before was set aside
                    // for want of one: it applies now.
                    applyWatch(if (presentingSaid) presenting else reportedVisible ?: true)
                }
                // Nothing to show and nothing asked to be heard: the desktop
                // fails here too, since there is nothing to play.
                if (!hasVideo && !audio) {
                    fail(NoVideoStreamException("${resolved?.label} carries no video stream, and the player was made without sound"))
                    return
                }
            }
            // ExoPlayer plays on without a picture when no decoder takes it,
            // which the hardware policy and the open contract both forbid.
            // Supported allowing a stream past what the decoder declares (a
            // level above its stated maximum), which ExoPlayer selects and
            // plays anyway; only a track no decoder takes at all is refused.
            if (hasVideo && video.isNotEmpty() && video.none { it.isSupported(true) }) {
                val type = video.first().getTrackFormat(0).sampleMimeType
                val why = if (hardware == HwAccel.REQUIRE) "no hardware decoder takes $type (HwAccel.REQUIRE)" else "no decoder on this device takes $type"
                fail(LibavException(why))
                return
            }
            audioTrackList = groups.mapIndexedNotNull { i, g ->
                if (g.type != C.TRACK_TYPE_AUDIO) return@mapIndexedNotNull null
                val f = g.getTrackFormat(0)
                AudioTrack(
                    streamIndex = i,
                    language = threeLetterLanguage(f.language),
                    title = f.label,
                    channels = f.channelCount.coerceAtLeast(0),
                    sampleRate = f.sampleRate.coerceAtLeast(0),
                    isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                )
            }.takeIf { audio } ?: emptyList()
            selectedAudioTrack = groups.indices.firstOrNull { groups[it].type == C.TRACK_TYPE_AUDIO && groups[it].isSelected }
            pendingAudioTrack?.let { wanted ->
                pendingAudioTrack = null
                if (wanted != selectedAudioTrack) selectAudioTrack(wanted)
            }
            if (reapplyChoices && groups.isNotEmpty()) {
                reapplyChoices = false
                // Same groups in the same order, under new identities: the
                // choices are made again by index.
                chosenAudioTrack?.let { wanted -> if (groups.getOrNull(wanted)?.isSelected == false) overrideAudio(wanted) }
                selectedSubtitleId?.let { id -> subtitleGroup(id, tracks)?.takeIf { !it.isSelected }?.let { selectSubtitles(it, id) } }
            }
            embeddedSubtitles = groups.mapIndexedNotNull { i, g ->
                if (g.type != C.TRACK_TYPE_TEXT) return@mapIndexedNotNull null
                val f = g.getTrackFormat(0)
                if (f.id?.contains(EXTERNAL_PREFIX) == true) return@mapIndexedNotNull null
                val mime = f.sampleMimeType ?: f.codecs ?: "text"
                SubtitleTrack(
                    id = i, streamIndex = i, language = threeLetterLanguage(f.language), title = f.label,
                    codecName = mime, isText = mime !in BITMAP_SUBTITLES,
                    isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    isForced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                )
            }
            // A frameless player takes no subtitles, as on the desktop: there is no picture to lay text over.
            subtitleTracks = if (hasVideo) embeddedSubtitles + externals.map { it.track } else emptyList()
            pendingSubtitleId?.let { wanted ->
                subtitleGroup(wanted, tracks)?.let { group ->
                    pendingSubtitleId = null
                    selectSubtitles(group, wanted)
                }
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (clip != null) return
            sampleClock(newPosition.positionMs * NANOS_PER_MILLI, System.nanoTime(), player?.isPlaying == true)
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            hardwareActive = runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == decoderName }?.isHardwareAccelerated
            }.getOrNull() == true
        }

        override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
            hardwareActive = false
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            val channels = Integer.bitCount(audioTrackConfig.channelConfig).coerceAtLeast(1)
            val (encoding, bits) = when (audioTrackConfig.encoding) {
                PlatformAudioFormat.ENCODING_PCM_8BIT -> PcmEncoding.U8 to 8
                PlatformAudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.F32LE to 32
                ENCODING_PCM_32BIT -> PcmEncoding.S32LE to 32
                ENCODING_PCM_24BIT_PACKED -> PcmEncoding.S32LE to 24
                else -> PcmEncoding.S16LE to 16
            }
            audioFormat = PcmFormat(audioTrackConfig.sampleRate, channels, layoutFor(channels), encoding, bits)
        }
    }

    private fun fail(cause: Throwable) {
        publishState(State.Failed(cause))
        handler.post {
            runCatching { player?.release() }
            player = null
            closeClip()
            resolved?.close()
        }
    }

    actual fun closeAsync() {
        if (closing) return
        closing = true
        // From the caller's thread, and first: the player thread can be inside
        // a read of a stalled stream (the open reads the container), and the
        // teardown posted below would wait behind it. Breaking the reads waits
        // for nothing.
        resolved?.interrupt()
        streams.values.forEach { it.interrupt() }
        released.forEach { it.interrupt() }
        handler.post {
            runCatching { player?.release() }
            player = null
            closeClip()
            runCatching { bridge?.close() }
            bridge = null
            synchronized(mailbox) {
                latest?.close()
                latest = null
                borrowed.forEach(Image::close)
                borrowed.clear()
            }
            decodeWork.shutdownNow()
            resolved?.close()
            streams.values.forEach { it.close() }
            released.forEach { it.close() }
            pendingSwitches.forEach { it.discard() }
            synchronized(changeMonitor) {
                // A failure keeps its state through the close, as on the
                // desktop: it is the answer a consumer polls for.
                if (stateField !is State.Failed) stateField = State.Closed
                changes++
                changeMonitor.notifyAll()
            }
            thread.quitSafely()
        }
    }

    actual override fun close() {
        closeAsync()
        thread.join(CLOSE_BUDGET_MILLIS)
    }

    // Last, after every property above is initialised: the open runs on the
    // player thread, which can reach it before the constructor returns.
    init {
        handler.post { open(input, explicitClock, audioTrack, startPaused) }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** How often a switch looks again for the track report of a forward-only stream. */
        const val SHAPE_WAIT_MILLIS = 50L

        /** How long a switch waits for that report before it is refused. */
        const val SHAPE_WAIT_LIMIT_NANOS = 10_000_000_000L

        /** A frame's length when the track declares no rate: 30 fps, the common case. */
        const val FALLBACK_FRAME_NANOS = 33_333_333L

        /** How long after its seek settles a step waits for a frame before the next step goes anyway. */
        const val STEP_SETTLE_MILLIS = 400L

        /** How long a file a switch is asked for may take to open before it is refused. */
        const val PROBE_MILLIS = 10_000L

        /** The longest a step's seek is waited for at all. */
        const val STEP_GIVE_UP_MILLIS = 3_000L

        /** How long a press that paused a playing player waits for frames already on their way. */
        const val STEP_SETTLE_PLAYING_MILLIS = 120L
        const val NANOS_PER_MICRO = 1_000L
        const val TICK_MILLIS = 100L
        const val UNREAD_NANOS = 2_000_000_000L
        const val RELEASE_MAP_LIMIT = 64

        /** Tags a side-loaded subtitle file's format id, so its group can be found again. */
        const val EXTERNAL_PREFIX = "skinema-external-"

        /** The subtitle types Media3 decodes to pictures rather than text. */
        val BITMAP_SUBTITLES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_DVBSUBS, MimeTypes.APPLICATION_VOBSUB)

        /**
         * Media3 normalises a track's language to a BCP 47 tag ("en"), where
         * the desktop reports the container's own three-letter ISO 639 tag
         * ("eng"). Turned back into three letters so shared code reads one
         * form on both. The platform gives the terminology code, so a file
         * tagged with a bibliographic one ("ger") reads "deu" here.
         */
        fun threeLetterLanguage(tag: String?): String? {
            if (tag.isNullOrBlank() || tag == "und") return null
            return runCatching { Locale.forLanguageTag(tag).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: tag
        }

        fun subtitleMimeFor(file: Path): String? = when (file.fileName?.toString()?.substringAfterLast('.')?.lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> null
        }
        const val CLOSE_BUDGET_MILLIS = 3_000L

        // AudioFormat constants that arrive at API 31, named for the floor of 29.
        const val ENCODING_PCM_24BIT_PACKED = 21
        const val ENCODING_PCM_32BIT = 22

        /**
         * A meter that measures nothing, for a player that loads nothing over
         * a network. Media3 refuses its own NO_OP here, so this is that by
         * another name.
         */
        object NoBandwidthMeter : BandwidthMeter {
            override fun getBitrateEstimate(): Long = 0L
            override fun getTransferListener(): TransferListener? = null
            override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) {}
            override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {}
        }

        /** Folds any layout to two channels before the device is asked, which is what Stereo means. */
        fun stereoFold() = ChannelMixingAudioProcessor().apply {
            for (channels in 1..8) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, 2))
        }

        /**
         * The platform decoders a policy allows for video, the desktop's three
         * words: OFF software only, REQUIRE hardware only, AUTO the platform's
         * own order. Audio decoders are left alone: the policy is about video.
         */
        fun codecSelector(hardware: HwAccel) = MediaCodecSelector { mimeType, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            if (!mimeType.startsWith("video/")) return@MediaCodecSelector all
            when (hardware) {
                HwAccel.OFF -> all.filter(MediaCodecInfo::softwareOnly)
                HwAccel.REQUIRE -> all.filter(MediaCodecInfo::hardwareAccelerated)
                HwAccel.AUTO -> all
            }
        }
    }
}
