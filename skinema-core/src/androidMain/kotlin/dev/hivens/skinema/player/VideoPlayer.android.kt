@file:OptIn(UnstableApi::class)

package dev.hivens.skinema.player

import android.content.Context
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
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.hivens.skinema.Debug
import dev.hivens.skinema.android.AppContext
import dev.hivens.skinema.android.ContainerInfo
import dev.hivens.skinema.android.FrameBridge
import dev.hivens.skinema.android.Input
import dev.hivens.skinema.android.PcmSinkAudioSink
import dev.hivens.skinema.android.layoutFor
import dev.hivens.skinema.audio.ChannelPreference
import dev.hivens.skinema.audio.PcmEncoding
import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.AudioTrack
import dev.hivens.skinema.libav.Chapter
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.SubtitleTrack
import dev.hivens.skinema.libav.VideoDecoder
import dev.hivens.skinema.subtitles.SubtitleOverlay
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
 */
actual class VideoPlayer actual constructor(
    path: Path,
    loop: Boolean,
    private val audio: Boolean,
    explicitClock: MediaClock?,
    sink: PcmSink?,
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

    /** Empty: the platform does not read chapters. */
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
            post { player?.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
        }

    @Volatile
    actual var source: Path = path
        private set

    @Volatile
    actual var sourceFailure: Throwable? = null
        private set

    // -- Player-thread state -----------------------------------------------------

    private var player: ExoPlayer? = null
    private var bridge: FrameBridge? = null
    private var everReady = false
    private var seekPending = false
    private var intendedNanos = 0L
    private var hasVideo = false
    private var userPaused = startPaused
    private var autoPaused = false
    private var stopMode: StopMode? = null
    private var videoTrackDisabled = false

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
    private var fresh = false

    @Volatile
    private var lastPublishedPts = 0L

    // Release time -> media time, filled as ExoPlayer releases each frame and
    // read when the frame comes out of the bridge carrying its release time.
    private val releaseToPts = ConcurrentHashMap<Long, Long>()

    @Volatile
    private var lastAcquireNanos = 0L

    @Volatile
    private var publishedSinceRead = false

    private var slotBytes = ByteArray(0)

    private fun onImage(image: Image) {
        if (closing) {
            image.close()
            return
        }
        val pts = releaseToPts.remove(image.timestamp) ?: lastPublishedPts
        if (releaseToPts.size > RELEASE_MAP_LIMIT) {
            releaseToPts.keys.sorted().take(releaseToPts.size - RELEASE_MAP_LIMIT / 2).forEach(releaseToPts::remove)
        }
        synchronized(mailbox) {
            latest?.close()
            latest = image
            latestPts = pts
            fresh = true
        }
        lastPublishedPts = pts
        publishedSinceRead = true
        noteChange()
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
            return FrameSlot().also {
                it.width = width
                it.height = height
                it.ptsNanos = latestPts
                it.rgba = slotBytes
            }
        }
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
        val p = player ?: return
        if (!hasVideo) return
        if (!watched && stopMode == null) {
            val mode = when (unwatched) {
                WhenUnwatched.Freeze -> StopMode.FROZE
                WhenUnwatched.KeepTime -> StopMode.KEPT
                WhenUnwatched.FollowSound -> if (audible()) StopMode.KEPT else StopMode.FROZE
            }
            if (mode == StopMode.FROZE && p.playWhenReady) {
                autoPaused = true
                p.pause()
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
        if (videoTrackDisabled == disabled) return
        videoTrackDisabled = disabled
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled).build()
    }

    // -- Commands ----------------------------------------------------------------------

    private fun post(block: () -> Unit) {
        if (closing) return
        handler.post {
            if (closing) return@post
            try {
                block()
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    actual fun pause() = post {
        userPaused = true
        player?.pause()
        recomputeState()
    }

    actual fun resume() = post {
        userPaused = false
        val p = player ?: return@post
        // Not the way back from a stop the player made for a hidden picture:
        // it plays, and the picture stays off until it is being taken again.
        if (stopMode == StopMode.FROZE) {
            autoPaused = false
            setVideoDisabled(true)
            stopMode = StopMode.KEPT
        }
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.play()
        recomputeState()
    }

    actual fun seek(ptsNanos: Long, exact: Boolean) = post { seekTo(ptsNanos.coerceAtLeast(0), exact) }

    actual fun seekBy(deltaNanos: Long, exact: Boolean) = post {
        val base = when {
            seekPending -> intendedNanos
            hasVideo -> lastPublishedPts
            else -> positionNanos()
        }
        seekTo((base + deltaNanos).coerceAtLeast(0), exact)
    }

    private fun seekTo(target: Long, exact: Boolean) {
        val p = player ?: return
        intendedNanos = target
        seekPending = true
        p.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        p.seekTo(target / NANOS_PER_MILLI)
        sampleClock(target, System.nanoTime(), false)
        recomputeState()
    }

    actual fun stepForward() = post {
        val p = player ?: return@post
        userPaused = true
        p.pause()
        // The first frame at or after a millisecond past the one on screen is
        // the next one: frames are never closer than that.
        seekTo((lastPublishedPts / NANOS_PER_MILLI + 1) * NANOS_PER_MILLI, exact = true)
    }

    actual fun stepBackward() = post {
        val p = player ?: return@post
        userPaused = true
        p.pause()
        val shown = lastPublishedPts
        val file = source
        // Which frame precedes the one on screen is only knowable by decoding
        // toward it, as on the desktop: run the platform decoder over the
        // stretch before it, off the player thread, and land an exact seek on
        // the answer.
        decodeWork.execute {
            val previous = runCatching { previousFramePts(file, shown) }
                .onFailure { Debug.trace("step backward discovery", it) }
                .getOrNull()
            post { seekTo(previous ?: (shown - NANOS_PER_MILLI).coerceAtLeast(0), exact = true) }
        }
    }

    private fun previousFramePts(file: Path, shown: Long): Long? {
        if (shown <= 0L) return 0L
        VideoDecoder.open(file, hardware).use { d ->
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
            this.rate = clamped
            player?.playbackParameters = PlaybackParameters(clamped)
        }
    }

    actual fun setSource(path: Path) {
        if (closing) return
        val picture = hasVideo
        decodeWork.execute {
            // Opened and decoded to a first frame before the player touches
            // it, so a file that will not play is refused and the one playing
            // carries on, as on the desktop.
            val refusal = runCatching {
                if (picture) {
                    VideoDecoder.open(path, hardware).use { it.nextFrame(convert = false) ?: error("$path gives no picture") }
                } else {
                    (AudioDecoder.openOrNull(path) ?: error("$path carries no sound")).close()
                }
            }.exceptionOrNull()
            if (refusal != null) {
                sourceFailure = refusal
                noteChange()
                return@execute
            }
            val info = ContainerInfo.read(Input.File(path))
            post {
                val p = player ?: return@post
                sourceFailure = null
                source = path
                tags = info.tags
                coverArt = info.coverArt
                durationNanos = null
                everReady = false
                seekPending = false
                lastPublishedPts = 0L
                sampleClock(0L, System.nanoTime(), false)
                p.setMediaItem(mediaItem(path))
                p.prepare()
                if (p.playbackState == Player.STATE_ENDED || !userPaused) p.play()
                publishState(State.Opening)
            }
        }
    }

    actual fun selectAudioTrack(streamIndex: Int) = post {
        val p = player ?: return@post
        val group = p.currentTracks.groups.getOrNull(streamIndex) ?: return@post
        if (group.type != C.TRACK_TYPE_AUDIO) return@post
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
    }

    /** Subtitles on Android are not implemented yet: no tracks are added. */
    actual fun addExternalSubtitles(file: Path): List<SubtitleTrack> = emptyList()

    /** Subtitles on Android are not implemented yet: nothing is selected. */
    actual fun selectSubtitleTrack(id: Int?) {}

    actual val activeSubtitleTrack: Int? get() = null

    actual fun acquireSubtitles(): SubtitleOverlay? = null

    actual fun setSubtitleCanvasSize(width: Int, height: Int) {}

    // -- Lifecycle --------------------------------------------------------------------

    private val decodeWork = Executors.newSingleThreadExecutor { r -> Thread(r, "skinema-player-work").apply { isDaemon = true } }

    init {
        handler.post { open(path, explicitClock, sink, audioTrack, startPaused) }
    }

    private fun mediaItem(path: Path) = MediaItem.fromUri(Uri.fromFile(path.toFile()))

    private fun open(path: Path, explicitClock: MediaClock?, sink: PcmSink?, audioTrack: Int?, startPaused: Boolean) {
        if (closing) return
        try {
            require(explicitClock == null) { "explicitClock is not supported on Android: ExoPlayer takes no external media clock" }
            val context = AppContext.get()
            val info = ContainerInfo.read(Input.File(path))
            tags = info.tags
            coverArt = info.coverArt
            val frames = FrameBridge(::onImage)
            bridge = frames
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
            val p = ExoPlayer.Builder(context, renderers)
                .setLooper(thread.looper)
                .setMediaSourceFactory(DefaultMediaSourceFactory(FileDataSource.Factory()))
                .build()
            player = p
            p.setVideoSurface(frames.inputSurface)
            p.addListener(listener)
            p.addAnalyticsListener(analytics)
            p.setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
                val pts = presentationTimeUs * NANOS_PER_MICRO
                releaseToPts[releaseTimeNs] = pts
                sampleClock(pts, releaseTimeNs, clockRunning)
            }
            p.repeatMode = if (loopField) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            p.volume = if (audio) volumeField else 0f
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audio)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            pendingAudioTrack = audioTrack
            p.setMediaItem(mediaItem(path))
            p.playWhenReady = !startPaused
            p.prepare()
            handler.postDelayed(tick, TICK_MILLIS)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private var pendingAudioTrack: Int? = null

    private val tick = object : Runnable {
        override fun run() {
            if (closing) return
            val p = player ?: return
            if (!hasVideo || stopMode != null) {
                sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), p.isPlaying)
            } else {
                clockRunning = p.isPlaying
            }
            checkMailbox()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    private fun recomputeState() {
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
            val p = player ?: return
            if (playbackState == Player.STATE_READY) {
                everReady = true
                if (seekPending) {
                    seekPending = false
                    intendedNanos = p.currentPosition * NANOS_PER_MILLI
                }
                sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), p.isPlaying)
            }
            if (playbackState == Player.STATE_ENDED) sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), false)
            recomputeState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = player ?: return
            sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), isPlaying)
            recomputeState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = recomputeState()

        override fun onPlayerError(error: PlaybackException) = fail(error)

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val duration = player?.duration ?: return
            durationNanos = if (duration == C.TIME_UNSET || duration < 0) null else duration * NANOS_PER_MILLI
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            bridge?.resize(videoSize.width, videoSize.height)
        }

        override fun onTracksChanged(tracks: Tracks) {
            val groups = tracks.groups
            if (groups.any { it.type == C.TRACK_TYPE_VIDEO }) hasVideo = true
            audioTrackList = groups.mapIndexedNotNull { i, g ->
                if (g.type != C.TRACK_TYPE_AUDIO) return@mapIndexedNotNull null
                val f = g.getTrackFormat(0)
                AudioTrack(
                    streamIndex = i,
                    language = f.language?.takeIf { it != "und" },
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
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
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
        }
    }

    actual fun closeAsync() {
        if (closing) return
        closing = true
        handler.post {
            runCatching { player?.release() }
            player = null
            runCatching { bridge?.close() }
            bridge = null
            synchronized(mailbox) {
                latest?.close()
                latest = null
            }
            decodeWork.shutdownNow()
            synchronized(changeMonitor) {
                stateField = State.Closed
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

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_MICRO = 1_000L
        const val TICK_MILLIS = 100L
        const val UNREAD_NANOS = 2_000_000_000L
        const val RELEASE_MAP_LIMIT = 64
        const val CLOSE_BUDGET_MILLIS = 3_000L

        // AudioFormat constants that arrive at API 31, named for the floor of 29.
        const val ENCODING_PCM_24BIT_PACKED = 21
        const val ENCODING_PCM_32BIT = 22

        /**
         * The platform decoders a policy allows for video, the desktop's three
         * words: OFF software only, REQUIRE hardware only, AUTO the platform's
         * own order. Audio decoders are left alone: the policy is about video.
         */
        /** Folds any layout to two channels before the device is asked, which is what Stereo means. */
        fun stereoFold() = ChannelMixingAudioProcessor().apply {
            for (channels in 1..8) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, 2))
        }

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
